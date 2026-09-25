"""Local llama.cpp transport with structured calls and measured context limits.

API references: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
The server's ``post_apply_template`` and chat handler share the same parser;
chat inference tokenizes that rendered prompt with add_special/parse_special true.
No key, raw-text tool parser, or fallback planner is used here.
"""
from __future__ import annotations

import ipaddress
import json
import math
import os
from pathlib import PurePath
import re
import secrets
from typing import Any
from urllib.parse import urlsplit, urlunsplit

import httpx

from .common import IntegrationError

_NAME = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
_CALL_ID = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
_MAX_JSON_BYTES = 2 * 1024 * 1024


def _invalid(message: str) -> IntegrationError:
    return IntegrationError("agent_unavailable", message)


def _nonnegative_int(value: Any) -> bool:
    return type(value) is int and value >= 0


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError("non-finite JSON number")


def _finite_float(value: str) -> float:
    number = float(value)
    if not math.isfinite(number):
        raise ValueError("non-finite JSON number")
    return number


def _local_base_url(value: str) -> str:
    try:
        parts = urlsplit(value)
        hostname = parts.hostname
        port = parts.port
        if (
            parts.scheme not in {"http", "https"}
            or not hostname
            or parts.username is not None
            or parts.password is not None
            or parts.query
            or parts.fragment
            or parts.path.rstrip("/") != "/v1"
        ):
            raise ValueError("invalid local API URL")
        # Pin localhost to a literal loopback address; do not depend on DNS.
        if hostname.lower() == "localhost":
            hostname = "127.0.0.1"
        address = ipaddress.ip_address(hostname)
        if not address.is_loopback or "%" in hostname:
            raise ValueError("not loopback")
        netloc = f"[{address}]" if address.version == 6 else str(address)
        if port is not None:
            netloc += f":{port}"
        return urlunsplit((parts.scheme, netloc, "/v1", "", ""))
    except (ValueError, TypeError) as exc:
        raise _invalid("LIQUID_BASE_URL must be a loopback HTTP(S) URL ending in /v1.") from exc


class LiquidClient:
    """A client owned by one serial worker, with an injectable HTTP transport.

    ``complete`` returns a wire-format ``message`` plus parsed ``tool_calls``
    with dict arguments, ``usage``, ``context``, ``identity``, and ``finish_reason``.
    Callers still validate tool argument schemas and authorization before execution.
    Injected clients remain the caller's responsibility to close.
    """

    def __init__(
        self,
        base_url: str | None = None,
        model: str | None = None,
        *,
        client: httpx.AsyncClient | None = None,
        context_tokens: int = 4096,
        prompt_tokens: int = 3072,
        output_tokens: int = 768,
        timeout_s: float = 90,
    ) -> None:
        self.base_url = _local_base_url(base_url or os.getenv("LIQUID_BASE_URL", "http://127.0.0.1:8080/v1"))
        self.server_url = self.base_url[:-3]
        self.model = model or os.getenv("LIQUID_MODEL", "clearline-liquid")
        if not isinstance(self.model, str) or not 1 <= len(self.model) <= 256:
            raise _invalid("LIQUID_MODEL must be a nonempty served alias.")
        if (
            any(type(value) is not int or value <= 0 for value in (context_tokens, prompt_tokens, output_tokens))
            or context_tokens > 4096
            or prompt_tokens + output_tokens > context_tokens
        ):
            raise ValueError("Token limits must fit a positive context of at most 4096 tokens.")
        self.context_tokens = context_tokens
        self.prompt_tokens = prompt_tokens
        self.output_tokens = output_tokens
        self.timeout = httpx.Timeout(timeout_s, connect=min(timeout_s, 5))
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(trust_env=False, follow_redirects=False)
        self.identity: dict[str, Any] | None = None

    async def __aenter__(self) -> "LiquidClient":
        return self

    async def __aexit__(self, *args: Any) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def _json(self, method: str, url: str, *, body: dict[str, Any] | None = None,
                    error_code: str = "agent_unavailable") -> dict[str, Any]:
        try:
            response = await self._client.request(
                method, url, json=body, timeout=self.timeout, follow_redirects=False,
            )
        except httpx.RequestError as exc:
            raise IntegrationError(error_code, "Local Liquid runtime could not be reached.", True) from exc
        if not response.is_success:
            raise IntegrationError(
                error_code, f"Local Liquid runtime returned HTTP {response.status_code}.",
                response.status_code in {408, 429} or response.status_code >= 500,
            )
        try:
            if len(response.content) > _MAX_JSON_BYTES:
                raise ValueError("oversized response")
            payload = json.loads(response.content, object_pairs_hook=_strict_object,
                                 parse_constant=_reject_constant, parse_float=_finite_float)
            if not isinstance(payload, dict):
                raise ValueError("object required")
            return payload
        except (ValueError, UnicodeError, RecursionError) as exc:
            raise IntegrationError(error_code, "Local Liquid runtime returned malformed JSON.") from exc

    async def verify_model(self) -> dict[str, Any]:
        """Verify the configured alias and capture only exposed identity metadata."""
        result = await self._json("GET", f"{self.base_url}/models")
        models = result.get("data")
        if not isinstance(models, list):
            raise _invalid("Local Liquid model list is malformed.")
        matching = [item for item in models if isinstance(item, dict) and (
            item.get("id") == self.model or (
                isinstance(item.get("aliases"), list) and self.model in item["aliases"]
            )
        )]
        if len(matching) != 1 or not isinstance(matching[0].get("id"), str):
            raise _invalid("Configured Liquid alias does not uniquely match the served model.")
        entry = matching[0]
        meta = entry.get("meta") if isinstance(entry.get("meta"), dict) else {}
        identity: dict[str, Any] = {
            "configured_alias": self.model,
            "served_model": entry["id"],
            "owned_by": entry.get("owned_by") if isinstance(entry.get("owned_by"), str) else None,
            "model_metadata": {key: meta[key] for key in (
                "n_ctx", "n_ctx_train", "n_params", "n_vocab", "size", "ftype"
            ) if key in meta},
            "runtime_build": None,
            "model_file": None,
            "quantization": meta.get("ftype"),
            "runtime_context_tokens": meta.get("n_ctx") if _nonnegative_int(meta.get("n_ctx")) else None,
        }
        # /props is diagnostic only; old builds may expose identity solely in /models.
        try:
            props = await self._json("GET", f"{self.server_url}/props")
        except IntegrationError:
            props = {}
        if isinstance(props.get("build_info"), str):
            identity["runtime_build"] = props["build_info"][:512]
        if isinstance(props.get("model_path"), str):
            identity["model_file"] = PurePath(props["model_path"]).name
        if isinstance(props.get("model_ftype"), str):
            identity["quantization"] = props["model_ftype"][:100]
        settings = props.get("default_generation_settings")
        if isinstance(settings, dict) and _nonnegative_int(settings.get("n_ctx")):
            identity["runtime_context_tokens"] = settings["n_ctx"]
        self.identity = identity
        return dict(identity)

    def _request(self, messages: list[dict[str, Any]], tools: list[dict[str, Any]],
                 tool_choice: str | dict[str, Any]) -> tuple[dict[str, Any], set[str]]:
        if not isinstance(messages, list) or not messages or not isinstance(tools, list):
            raise _invalid("Liquid messages and tool schemas must be lists.")
        names: set[str] = set()
        for tool in tools:
            function = tool.get("function") if isinstance(tool, dict) else None
            name = function.get("name") if isinstance(function, dict) else None
            if (not isinstance(tool, dict) or tool.get("type") != "function" or not isinstance(name, str)
                    or not _NAME.fullmatch(name) or name in names):
                raise _invalid("Liquid function schemas contain invalid or duplicate names.")
            names.add(name)
        if isinstance(tool_choice, str):
            if tool_choice not in {"auto", "none", "required"}:
                raise _invalid("Unsupported Liquid tool choice.")
            if tool_choice == "required" and not tools:
                raise _invalid("A required Liquid tool choice needs at least one tool.")
        elif isinstance(tool_choice, dict):
            function = tool_choice.get("function")
            if (tool_choice.get("type") != "function" or not isinstance(function, dict)
                    or function.get("name") not in names):
                raise _invalid("Requested Liquid tool is not an offered function.")
        else:
            raise _invalid("Unsupported Liquid tool choice.")
        body = {
            "model": self.model, "messages": messages,
            "tools": tools, "tool_choice": tool_choice,
            "max_tokens": self.output_tokens, "temperature": 0,
            "stream": False, "parallel_tool_calls": False, "parse_tool_calls": True,
        }
        try:
            # Copy before awaits so callers cannot change the already-measured prompt.
            body = json.loads(json.dumps(body, allow_nan=False))
        except (ValueError, TypeError, RecursionError) as exc:
            raise _invalid("Liquid request must contain finite JSON values.") from exc
        return body, names

    async def _measure(self, body: dict[str, Any], names: set[str]) -> dict[str, Any]:
        code = "context_budget_unavailable"
        rendered = await self._json("POST", f"{self.server_url}/apply-template", body=body, error_code=code)
        prompt = rendered.get("prompt")
        if not isinstance(prompt, str) or not prompt:
            raise IntegrationError(code, "Liquid chat-template endpoint did not return a prompt.")
        # A template silently ignoring schemas cannot provide a useful tool-call budget.
        if body["tool_choice"] != "none" and any(name not in prompt for name in names):
            raise IntegrationError(code, "Liquid chat template did not include the offered tool names.")
        if names and body["tool_choice"] != "none":
            without_tools = await self._json("POST", f"{self.server_url}/apply-template", body={
                **body, "tools": [], "tool_choice": "none",
            }, error_code=code)
            if not isinstance(without_tools.get("prompt"), str) or without_tools["prompt"] == prompt:
                raise IntegrationError(code, "Liquid chat template did not incorporate the tool schemas.")
        tokenized = await self._json("POST", f"{self.server_url}/tokenize", body={
            "model": self.model, "content": prompt,
            "add_special": True, "parse_special": True, "with_pieces": False,
        }, error_code=code)
        tokens = tokenized.get("tokens")
        if not isinstance(tokens, list) or not tokens or any(not _nonnegative_int(value) for value in tokens):
            raise IntegrationError(code, "Liquid tokenizer did not return token IDs.")
        reported = (self.identity or {}).get("runtime_context_tokens")
        context_limit = min(self.context_tokens, reported) if _nonnegative_int(reported) else self.context_tokens
        input_limit = min(self.prompt_tokens, context_limit - self.output_tokens)
        if len(tokens) > input_limit:
            raise IntegrationError("context_budget_exceeded", "Templated Liquid prompt exceeds its token budget.")
        return {
            "prompt_tokens": len(tokens), "measurement": "llama_cpp_apply_template_tokenize",
            "prompt_token_limit": input_limit, "output_token_reserve": self.output_tokens,
            "context_token_limit": context_limit,
            "runtime_context_verified": reported is not None,
        }

    async def complete(self, messages: list[dict[str, Any]], tools: list[dict[str, Any]],
                       tool_choice: str | dict[str, Any] = "auto") -> dict[str, Any]:
        body, names = self._request(messages, tools, tool_choice)
        # Each activation verifies identity, including after a local server restart.
        identity = await self.verify_model()
        context = await self._measure(body, names)
        result = await self._json("POST", f"{self.base_url}/chat/completions", body=body)
        if result.get("model") not in {self.model, identity["served_model"]}:
            raise _invalid("Liquid completion model does not match the verified served model.")
        choices = result.get("choices")
        if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
            raise _invalid("Liquid must return exactly one structured assistant choice.")
        choice = choices[0]
        message = choice.get("message")
        finish_reason = choice.get("finish_reason")
        if not isinstance(message, dict) or message.get("role") != "assistant":
            raise _invalid("Liquid returned an invalid assistant message.")
        if finish_reason not in {"stop", "tool_calls"}:
            raise _invalid("Liquid completion was truncated or did not finish successfully.")
        content = message.get("content")
        if content is not None and not isinstance(content, str):
            raise _invalid("Liquid assistant content must be text or null.")
        raw_calls = message.get("tool_calls")
        if raw_calls is None:
            raw_calls = []
        if not isinstance(raw_calls, list) or len(raw_calls) > 1:
            raise _invalid("Liquid must select at most one structured tool per durable action.")
        parsed_calls: list[dict[str, Any]] = []
        wire_calls: list[dict[str, Any]] = []
        for call in raw_calls:
            if not isinstance(call, dict):
                raise _invalid("Liquid tool call is malformed.")
            function = call.get("function")
            call_id = call.get("id")
            if (call.get("type") != "function" or not isinstance(function, dict)
                    or not isinstance(call_id, str) or not _CALL_ID.fullmatch(call_id)):
                raise _invalid("Liquid tool call needs a valid function type and call ID.")
            name, raw_args = function.get("name"), function.get("arguments")
            if not isinstance(name, str) or name not in names or not isinstance(raw_args, str) or len(raw_args) > 16384:
                raise _invalid("Liquid selected an unknown tool or malformed argument encoding.")
            try:
                arguments = json.loads(raw_args, object_pairs_hook=_strict_object,
                                       parse_constant=_reject_constant, parse_float=_finite_float)
                if not isinstance(arguments, dict):
                    raise ValueError("tool arguments must be object")
            except (ValueError, RecursionError) as exc:
                raise _invalid("Liquid tool arguments must be an unambiguous JSON object.") from exc
            parsed_calls.append({"id": call_id, "name": name, "arguments": arguments})
            wire_calls.append({"id": call_id, "type": "function", "function": {
                "name": name, "arguments": json.dumps(arguments, ensure_ascii=False, separators=(",", ":")),
            }})
        if (finish_reason == "tool_calls" and not parsed_calls
                or not parsed_calls and (isinstance(tool_choice, dict) or tool_choice == "required")
                or parsed_calls and tool_choice == "none"):
            raise _invalid("Liquid did not honor the required structured tool-call contract.")
        if isinstance(tool_choice, dict) and parsed_calls[0]["name"] != tool_choice["function"]["name"]:
            raise _invalid("Liquid selected a different function than the required tool.")
        if not parsed_calls and not content:
            raise _invalid("Liquid returned neither an assistant response nor a tool call.")
        usage = result.get("usage")
        usage = usage if isinstance(usage, dict) else {}
        measured_usage = {
            key: usage.get(key) if _nonnegative_int(usage.get(key)) else None
            for key in ("prompt_tokens", "completion_tokens", "total_tokens")
        }
        if (measured_usage["prompt_tokens"] is not None
                and measured_usage["prompt_tokens"] > context["prompt_token_limit"]):
            raise IntegrationError("context_budget_exceeded", "Runtime reported prompt usage above the enforced limit.")
        if measured_usage["total_tokens"] is not None and measured_usage["total_tokens"] > context["context_token_limit"]:
            raise IntegrationError("context_budget_exceeded", "Runtime reported total usage above the context limit.")
        wire_message: dict[str, Any] = {"role": "assistant", "content": content}
        if wire_calls:
            wire_message["tool_calls"] = wire_calls
        return {
            "message": wire_message, "tool_calls": parsed_calls, "usage": measured_usage,
            "context": context, "identity": identity, "finish_reason": finish_reason,
        }

    async def echo_nonce_smoke(self) -> dict[str, Any]:
        """Run real tool selection, local execution, then model use of an unknown nonce."""
        tools = [{"type": "function", "function": {
            "name": "echo_nonce", "description": "Return a newly generated local nonce.",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        }}]
        messages = [{"role": "user", "content": (
            "Call echo_nonce with no arguments. Once its result is returned, reply with exactly "
            "the nonce from that result and no other text."
        )}]
        first = await self.complete(messages, tools, {"type": "function", "function": {"name": "echo_nonce"}})
        call = first["tool_calls"][0]
        if call["arguments"] != {}:
            raise _invalid("Liquid nonce test called echo_nonce with unsupported arguments.")
        # Execute only after tool selection: this nonce cannot be in the original prompt.
        nonce = secrets.token_hex(16)
        messages.extend([first["message"], {"role": "tool", "tool_call_id": call["id"],
                                            "content": json.dumps({"nonce": nonce})}])
        second = await self.complete(messages, tools, "none")
        if (second["message"]["content"] or "").strip() != nonce:
            raise _invalid("Liquid nonce test did not use the locally executed tool result.")
        return {
            "status": "passed", "execution_mode": "liquid_local", "identity": second["identity"],
            "structured_tool_call": True, "nonce_round_trip": True,
            "turns": [{"usage": turn["usage"], "context": turn["context"]} for turn in (first, second)],
        }
