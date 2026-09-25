"""Nimble public Search/Extract, with inert, source-backed evidence.

API contracts: https://docs.nimbleway.com/api-reference/search/search and
https://docs.nimbleway.com/api-reference/extract/extract. The controller owns
user approval, selecting an official/relevant search result, and durable state.
Returned text is untrusted source data; never execute or render it as HTML.
"""
from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import json
import os
import re
import socket
from datetime import datetime, timezone
from html.parser import HTMLParser
from typing import Any, Awaitable, Callable
from urllib.parse import urlsplit, urlunsplit

import httpx

from .common import IntegrationError


CATEGORY_QUERIES = {
    "caregiver support groups": "caregiver support groups",
    "caregiver_support": "caregiver support groups",
    "respite care": "respite care",
    "respite_care": "respite care",
    "caregiver education": "caregiver education",
    "caregiver_education": "caregiver education",
}
PUBLIC_RESOURCE_CATEGORIES = frozenset(CATEGORY_QUERIES)
NIMBLE_BASE_URL = "https://sdk.nimbleway.com"
MAX_RESPONSE_BYTES = 2_000_000
MAX_CONTENT_CHARS = 150_000
FACT_FIELDS = ("phone", "email", "address", "hours", "availability")
Resolver = Callable[[str], Awaitable[list[str]]]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def source_ref_for_url(url: str) -> str:
    return hashlib.sha256(url.encode("utf-8")).hexdigest()


def _optional_text(value: Any, limit: int) -> str | None:
    return value[:limit] if isinstance(value, str) and value.strip() else None


def _public_address(address: str) -> bool:
    if not isinstance(address, str):
        return False
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return False
    # IPv4-mapped IPv6 and transition addresses must not conceal private IPv4.
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped:
            return _public_address(str(ip.ipv4_mapped))
        if ip.sixtofour or ip.teredo:
            return False
    return ip.is_global and not ip.is_multicast and not ip.is_unspecified


def _public_url_shape(url: Any) -> str:
    if not isinstance(url, str) or not 1 <= len(url) <= 2048:
        raise IntegrationError("invalid_source_url", "Source URL is missing or too long.")
    if re.search(r"[\s\\\x00-\x1f\x7f]", url):
        raise IntegrationError("invalid_source_url", "Source URL contains unsafe characters.")
    try:
        parsed = urlsplit(url)
        host = (parsed.hostname or "").lower().rstrip(".")
        port = parsed.port
        invalid = parsed.scheme not in {"http", "https"} or not host
        invalid |= parsed.username is not None or parsed.password is not None
        invalid |= port not in {None, 80, 443} or "%" in host
    except (ValueError, UnicodeError):
        raise IntegrationError("invalid_source_url", "Source URL is malformed.") from None
    if invalid:
        raise IntegrationError("invalid_source_url", "Only public HTTP(S) URLs without credentials are allowed.")
    if host == "localhost" or host.endswith((".localhost", ".local", ".internal", ".home", ".lan")) or "." not in host and ":" not in host:
        raise IntegrationError("private_source_url", "Local source targets are not allowed.")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        if not _public_address(host):
            raise IntegrationError("private_source_url", "Non-public source targets are not allowed.")
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path or "/", parsed.query, ""))


async def _resolve(host: str) -> list[str]:
    records = await asyncio.get_running_loop().getaddrinfo(host, None, type=socket.SOCK_STREAM)
    return list({record[4][0] for record in records})


async def validate_public_url(url: str, *, resolver: Resolver | None = None) -> str:
    """Reject private literal and DNS targets immediately before extraction.

    Nimble resolves and fetches remotely. Its own DNS/redirect policy remains
    an upstream boundary; this local check cannot pin Nimble's DNS resolution.
    """
    normalized = _public_url_shape(url)
    host = urlsplit(normalized).hostname
    try:
        addresses = await asyncio.wait_for((resolver or _resolve)(host), timeout=5)
    except (OSError, TimeoutError):
        raise IntegrationError("source_dns_unavailable", "Public source DNS could not be verified.", True) from None
    if not addresses or not all(_public_address(address) for address in addresses):
        raise IntegrationError("private_source_url", "Source DNS must resolve only to public addresses.")
    return normalized


class _TextOnlyHTML(HTMLParser):
    """Extract visible text without executing scripts or retaining HTML tags."""
    BLOCKED = {"script", "style", "noscript", "template", "iframe", "object", "svg"}
    BREAKS = {"p", "div", "br", "li", "h1", "h2", "h3", "h4", "tr", "section", "address"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.blocked: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag in self.BLOCKED:
            self.blocked.append(tag)
        elif not self.blocked and tag in self.BREAKS:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if self.blocked:
            if tag == self.blocked[-1]:
                self.blocked.pop()
        elif tag in self.BREAKS:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.blocked:
            self.parts.append(data)


def html_to_text(html: str) -> str:
    parser = _TextOnlyHTML()
    parser.feed(html)
    parser.close()
    return "\n".join(line for raw in "".join(parser.parts).splitlines() if (line := " ".join(raw.split())))


def _source_facts(content: str, evidence_ref: str) -> tuple[dict, list]:
    """Copy only explicit labeled fields. No model inference about availability."""
    facts: dict[str, dict | None] = dict.fromkeys(FACT_FIELDS)
    passages = []
    patterns = {
        "phone": r"(?:Phone|Telephone|Tel)\s*:\s*((?:\+1[ .-]?)?(?:\(\d{3}\)|\d{3})[ .-]?\d{3}[ .-]?\d{4}(?:\s*(?:ext\.?|x)\s*\d{1,6})?)",
        "email": r"Email\s*:\s*([A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,})",
        "address": r"(?:Street address|Address)\s*:\s*(\d{1,6}\s+[^\n<>]{5,180})",
        "hours": r"(?:Office hours|Opening hours|Hours)\s*:\s*([^\n<>]{3,160})",
    }
    for field, pattern in patterns.items():
        match = re.search(pattern, content, re.IGNORECASE)
        if not match:
            continue
        start, end = match.span()
        passage_ref = f"{evidence_ref}#chars={start}-{end}"
        passages.append({"passage_ref": passage_ref, "start": start, "end": end, "text": content[start:end]})
        facts[field] = {"value": match.group(1).strip(), "passage_ref": passage_ref}
    return facts, passages


class NimbleClient:
    """One adapter per app lifespan; injected AsyncClients remain caller-owned."""
    def __init__(self, api_key: str | None = None, *, client: httpx.AsyncClient | None = None,
                 timeout_s: float = 35, max_attempts: int = 2, resolver: Resolver | None = None):
        if not 1 <= timeout_s <= 120 or not 1 <= max_attempts <= 3:
            raise ValueError("Nimble timeout must be 1–120 seconds; attempts must be 1–3.")
        self._api_key = api_key if api_key is not None else os.environ.get("NIMBLE_API_KEY", "")
        self._client = client or httpx.AsyncClient(follow_redirects=False)
        self._owns_client = client is None
        self._timeout_s = timeout_s
        self._max_attempts = max_attempts
        self._resolver = resolver

    async def aclose(self):
        if self._owns_client:
            await self._client.aclose()

    async def _post(self, endpoint: str, payload: dict) -> dict:
        if not self._api_key:
            raise IntegrationError("nimble_not_configured", "NIMBLE_API_KEY is not configured.")
        try:
            return await asyncio.wait_for(self._post_attempts(endpoint, payload), timeout=self._timeout_s)
        except TimeoutError:
            raise IntegrationError("nimble_timeout", "Nimble request exceeded its time budget.", True) from None

    async def _post_attempts(self, endpoint: str, payload: dict) -> dict:
        for attempt in range(self._max_attempts):
            try:
                async with self._client.stream(
                    "POST", NIMBLE_BASE_URL + endpoint, json=payload,
                    headers={"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"},
                    timeout=httpx.Timeout(self._timeout_s, connect=min(10, self._timeout_s)),
                    follow_redirects=False,
                ) as response:
                    if not 200 <= response.status_code < 300:
                        status = response.status_code
                        retryable = status in {408, 429, 500, 502, 503, 504}
                        code = "nimble_auth_error" if status in {401, 403} else "nimble_http_error"
                        raise IntegrationError(code, f"Nimble returned HTTP {status}.", retryable)
                    chunks = bytearray()
                    async for chunk in response.aiter_bytes():
                        chunks.extend(chunk)
                        if len(chunks) > MAX_RESPONSE_BYTES:
                            raise IntegrationError("nimble_response_too_large", "Nimble response exceeds the size limit.")
                try:
                    body = json.loads(chunks)
                except (ValueError, UnicodeError):
                    raise IntegrationError("nimble_invalid_response", "Nimble returned invalid JSON.") from None
                if not isinstance(body, dict):
                    raise IntegrationError("nimble_invalid_response", "Nimble response must be an object.")
                return body
            except httpx.TimeoutException:
                error = IntegrationError("nimble_timeout", "Nimble request timed out.", True)
            except httpx.RequestError:
                error = IntegrationError("nimble_network_error", "Nimble could not be reached.", True)
            except IntegrationError as exc:
                error = exc
            if not error.retryable or attempt + 1 >= self._max_attempts:
                raise error from None
            await asyncio.sleep(0.2 * (2 ** attempt))
        raise AssertionError("Unreachable retry state")

    async def search(self, category: str, city: str, max_results: int = 3) -> dict:
        """Search only the exact category/city already approved by the user."""
        if not isinstance(category, str) or category not in CATEGORY_QUERIES:
            raise IntegrationError("invalid_resource_category", "Unsupported public resource category.")
        if not isinstance(city, str) or not 1 <= len(city.strip()) <= 100 or not re.fullmatch(r"[\w .,()'’-]+", city):
            raise IntegrationError("invalid_city", "City must be a bounded place name.")
        if isinstance(max_results, bool) or not isinstance(max_results, int) or not 1 <= max_results <= 3:
            raise IntegrationError("invalid_search_limit", "A search may return at most three sources.")
        body = await self._post("/v2/search", {
            "query": f"{CATEGORY_QUERIES[category]} in {city.strip()}",
            "country": "US", "max_results": max_results, "full_content": False,
        })
        if not isinstance(body.get("results"), list):
            raise IntegrationError("nimble_invalid_response", "Nimble Search did not return results.")
        request_id = _optional_text(body.get("request_id"), 256)
        retrieved_at, sources, seen = _now(), [], set()
        for row in body["results"][:max_results]:
            if not isinstance(row, dict):
                raise IntegrationError("nimble_invalid_response", "Nimble returned an invalid search result.")
            try:
                url = _public_url_shape(row.get("url"))
            except IntegrationError:
                continue
            source_ref = source_ref_for_url(url)
            if source_ref in seen:
                continue
            seen.add(source_ref)
            sources.append({
                "source_ref": source_ref, "url": url,
                "title": _optional_text(row.get("title"), 512),
                "description": _optional_text(row.get("description"), 1500),
                "request_id": request_id, "retrieved_at": retrieved_at,
                "verification_status": "search_candidate", "facts": dict.fromkeys(FACT_FIELDS),
            })
        return {"sources": sources, "request_id": request_id, "retrieved_at": retrieved_at}

    async def extract(self, source: dict[str, Any]) -> dict:
        """Extract a controller-selected search candidate, with exact passages."""
        if not isinstance(source, dict):
            raise IntegrationError("invalid_source", "Extract requires a selected search candidate.")
        url = await validate_public_url(source.get("url"), resolver=self._resolver)
        source_ref = source_ref_for_url(url)
        if source.get("source_ref") != source_ref:
            raise IntegrationError("invalid_source", "Selected source reference does not match its URL.")
        body = await self._post("/v2/extract", {"url": url, "render": True})
        if "status" in body and body["status"] != "success":
            pending = isinstance(body["status"], str) and body["status"] in {"pending", "running", "queued", "processing"}
            raise IntegrationError("nimble_extract_pending" if pending else "nimble_extract_failed", "Nimble extraction has not completed successfully.", pending)
        status_code = body.get("status_code")
        if status_code is not None and (type(status_code) is not int or not 200 <= status_code < 300):
            retryable = type(status_code) is int and status_code in {408, 429, 500, 502, 503, 504}
            raise IntegrationError("nimble_target_failed", "The source page did not return a successful fetch status.", retryable)
        data = body.get("data")
        if not isinstance(data, dict):
            raise IntegrationError("nimble_extract_incomplete", "Nimble did not return completed page content.", True)
        # Refuse evidence that ultimately came from an unrelated or private URL.
        returned_urls = [body["url"]] if body.get("url") else []
        redirects = data.get("redirects", [])
        if not isinstance(redirects, list) or len(redirects) > 5:
            raise IntegrationError("nimble_invalid_response", "Invalid source redirect chain.")
        for redirect in redirects:
            if not isinstance(redirect, dict) or not redirect.get("url"):
                raise IntegrationError("nimble_invalid_response", "Invalid source redirect.")
            returned_urls.append(redirect["url"])
        for returned_url in returned_urls:
            verified = await validate_public_url(returned_url, resolver=self._resolver)
            if urlsplit(verified).hostname.removeprefix("www.") != urlsplit(url).hostname.removeprefix("www."):
                raise IntegrationError("unrelated_source_redirect", "Source redirected to an unselected organization.")
        markdown = data.get("markdown")
        if isinstance(markdown, str) and markdown.strip():
            content, content_format = markdown, "markdown"
        elif isinstance(data.get("html"), str) and data["html"].strip():
            content, content_format = html_to_text(data["html"]), "text"
        else:
            raise IntegrationError("nimble_extract_incomplete", "Nimble returned no readable source content.", True)
        if not content.strip():
            raise IntegrationError("nimble_extract_incomplete", "The extracted page contains no visible text.", True)
        if len(content) > MAX_CONTENT_CHARS:
            raise IntegrationError("nimble_content_too_large", "Extracted page exceeds the stored evidence limit.")
        content_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
        evidence_ref = hashlib.sha256(f"{url}:{content_hash}".encode("utf-8")).hexdigest()
        facts, passages = _source_facts(content, evidence_ref)
        excerpt = content[:1500]
        return {
            "source_ref": source_ref, "url": url,
            "title": _optional_text(source.get("title"), 512),
            "description": _optional_text(source.get("description"), 1500),
            "request_id": _optional_text(body.get("request_id"), 256),
            "search_request_id": _optional_text(source.get("request_id"), 256),
            "task_id": _optional_text(body.get("task_id"), 256),
            "retrieved_at": _now(), "content_hash": content_hash, "evidence_ref": evidence_ref,
            # The immutable content-addressed record has one representation.
            # A changed source body creates a new evidence_ref, not an overwrite.
            "version": 1,
            "verification_status": "source_backed", "availability_verified": False,
            "content": content, "content_format": content_format,
            "excerpt": excerpt, "excerpt_ref": f"{evidence_ref}#chars=0-{len(excerpt)}",
            "facts": facts, "passages": passages, "untrusted_source": True,
        }
