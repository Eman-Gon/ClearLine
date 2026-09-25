"""Allowlisted RawTree exports and bounded, version-aware history operations.

API verified against https://rawtree.com/docs/reference/api and
https://rawtree.com/docs/guides/query-data on 2026-09-25. RawTree is analytical
history; the caller owns durable outbox retries and stable event identifiers.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import hashlib
import json
import math
import os
import re
import statistics
from typing import Any
from urllib.parse import urlsplit
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

import httpx

from .common import IntegrationError


METRICS = ("duration_s", "word_count", "recording_wpm", "pause_count", "energy_rms", "pitch_mean_hz")
_COMMON = {"event_id", "session_id", "profile_ref", "created_at", "data_origin", "schema_version", "state_version"}
_FIELDS = {
    "clearline_events": _COMMON | {"event_type", "phase", "state_version", "input_revision", "action_id", "action_name", "action_status", "summary_ref"},
    "clearline_session_summaries": _COMMON | {"summary_id", "summary_version", "input_revision", "recording_task", "method_version", "metrics"},
    "clearline_checkpoints": _COMMON | {"state_version", "input_revision", "phase", "baseline_ref", "accepted_clip_refs", "current_measurements_ref", "recent_completed_action_refs", "completed_action_watermark", "evidence_refs", "execution_mode", "pending_action_name"},
    "clearline_resource_versions": _COMMON | {"source_ref", "evidence_ref", "version", "url", "retrieved_at", "content_hash", "verification_status", "facts", "request_id", "task_id", "search_request_id", "title", "description", "passages", "availability_verified", "untrusted_source"},
}
_UUID_FIELDS = {"session_id", "summary_id"}
_COUNTERS = {"schema_version", "version", "summary_version", "state_version", "input_revision", "completed_action_watermark"}
_PHASES = {"recording", "processing", "awaiting_input", "comparing", "awaiting_user_choice", "researching", "waiting_retry", "ready", "paused", "agent_unavailable"}
_TOOLS = {"get_baseline_summary", "compare_recording_metrics", "search_public_resources", "extract_public_page", "request_user_input", "finish_task"}
_QUALITY_REASONS = {"too_short", "too_long", "silent", "no_speech", "empty_transcript", "invalid_audio", "decode_failed", "missing_audio", "unsupported_media", "empty_audio"}
_ENUMS = {
    "event_type": {"session_created", "state_changed", "action_planned", "action_started", "action_succeeded", "action_failed", "action_unknown", "summary_saved", "sponsor_smoke_test"},
    "action_status": {"planned", "started", "succeeded", "failed", "unknown"},
    "execution_mode": {"liquid_local", "local_pipeline"},
    "verification_status": {"source_backed"},
}
_TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,199}$")
_PASSAGE = re.compile(r"^([a-f0-9]{64})#chars=(\d{1,7})-(\d{1,7})$")


def _invalid(message: str = "RawTree operation has invalid or disallowed fields.") -> IntegrationError:
    return IntegrationError("rawtree_invalid_input", message)


def _uuid(value: Any) -> str:
    try:
        return str(UUID(str(value)))
    except (ValueError, TypeError, AttributeError):
        raise _invalid("A valid UUID is required for this RawTree operation.") from None


def _token(value: Any) -> str:
    if not isinstance(value, str) or not _TOKEN.fullmatch(value):
        raise _invalid("Only bounded identifier values are permitted.")
    return value


def _reference(value: Any) -> str:
    if isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value):
        return value
    return _uuid(value)


def _passage_ref(value: Any, evidence_ref: str | None = None) -> str:
    match = _PASSAGE.fullmatch(value) if isinstance(value, str) else None
    if not match or int(match[2]) >= int(match[3]) or (evidence_ref and match[1] != evidence_ref):
        raise _invalid("A bounded passage reference within the exported evidence is required.")
    return value


def _timestamp(value: Any) -> str:
    if not isinstance(value, str) or len(value) > 40:
        raise _invalid("A timezone-aware ISO timestamp is required.")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise _invalid("A timezone-aware ISO timestamp is required.") from None
    if parsed.tzinfo is None:
        raise _invalid("A timezone-aware ISO timestamp is required.")
    return parsed.astimezone(timezone.utc).isoformat()


def _metrics(value: Any) -> dict[str, Any]:
    allowed = set(METRICS) | {"quality", "quality_reasons", "data_origin"}
    if not isinstance(value, dict) or set(value) - allowed:
        raise _invalid("Metrics contain disallowed fields.")
    result: dict[str, Any] = {}
    for field in METRICS:
        number = value.get(field)
        if number is not None and (type(number) not in (int, float) or not math.isfinite(number) or number < 0):
            raise _invalid("Metrics must be finite nonnegative numbers or null.")
        result[field] = number
    if "quality" in value:
        if value["quality"] not in {"accepted", "rejected", "unusable"}:
            raise _invalid()
        result["quality"] = value["quality"]
    if "quality_reasons" in value:
        reasons = value["quality_reasons"]
        if not isinstance(reasons, list) or len(reasons) > 10:
            raise _invalid()
        if any(not isinstance(reason, str) or reason not in _QUALITY_REASONS for reason in reasons):
            raise _invalid("Quality reasons must be known engineering filter codes.")
        result["quality_reasons"] = list(reasons)
    if "data_origin" in value:
        if value["data_origin"] not in {"synthetic", "consented_demo"}:
            raise _invalid()
        result["data_origin"] = value["data_origin"]
    return result


def _public_url(value: Any) -> str:
    if not isinstance(value, str) or len(value) > 2048:
        raise _invalid()
    parsed = urlsplit(value)
    if parsed.scheme not in {"https", "http"} or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise _invalid("Evidence requires a credential-free public source URL.")
    return value


def export_projection(table: str, event: dict[str, Any]) -> dict[str, Any]:
    """Validate a cloud-specific projection, never serialize a full checkpoint.

    A trusted caller must include ``export_approved=True`` for consented demo
    payloads. This local assertion is consumed here, not exported. Unknown keys
    fail closed, including names, transcripts, audio, secrets, and model text.
    """
    if table not in _FIELDS or not isinstance(event, dict):
        raise _invalid()
    origin = event.get("data_origin")
    if origin not in {"synthetic", "consented_demo"}:
        raise _invalid("Each RawTree export needs explicit demo provenance.")
    if origin == "consented_demo" and event.get("export_approved") is not True:
        raise IntegrationError("cloud_export_not_approved", "Cloud export was not approved for this demonstration input.")
    if set(event) - (_FIELDS[table] | {"export_approved"}):
        raise _invalid("Cloud export contains fields outside its allowlisted schema.")
    if not {"event_id", "data_origin"}.issubset(event):
        raise _invalid()
    result: dict[str, Any] = {}
    for field, value in event.items():
        if field == "export_approved":
            continue
        if field in _UUID_FIELDS:
            result[field] = _uuid(value)
        elif field in {"event_id", "action_id", "profile_ref", "content_hash"}:
            result[field] = _reference(value)
            if field in {"profile_ref", "content_hash"} and not re.fullmatch(r"[a-f0-9]{64}", result[field]):
                raise _invalid()
        elif field in _COUNTERS:
            if type(value) is not int or not 0 <= value <= 2**53 - 1:
                raise _invalid()
            result[field] = value
        elif field in {"created_at", "completed_at", "retrieved_at"}:
            result[field] = _timestamp(value)
        elif field == "metrics":
            result[field] = _metrics(value)
        elif field == "phase":
            if value not in _PHASES:
                raise _invalid()
            result[field] = value
        elif field in _ENUMS:
            if not isinstance(value, str) or value not in _ENUMS[field]:
                raise _invalid("Export enum value is not permitted.")
            result[field] = value
        elif field in {"action_name", "pending_action_name"}:
            if value not in _TOOLS:
                raise _invalid()
            result[field] = value
        elif field in {"accepted_clip_refs", "recent_completed_action_refs", "evidence_refs"}:
            if not isinstance(value, list) or len(value) > 20:
                raise _invalid()
            result[field] = [_reference(item) for item in value]
        elif field == "url":
            result[field] = _public_url(value)
        elif field == "facts":
            if not isinstance(value, dict) or set(value) - {"organization", "phone", "email", "address", "hours", "services", "availability"}:
                raise _invalid()
            facts = {}
            for name, fact in value.items():
                if fact is None:
                    facts[name] = None
                    continue
                if not isinstance(fact, dict) or set(fact) != {"value", "passage_ref"}:
                    raise _invalid("Exported facts require a value and exact supporting passage reference.")
                if not isinstance(fact["value"], str) or len(fact["value"]) > 1000:
                    raise _invalid()
                facts[name] = {"value": fact["value"], "passage_ref": _passage_ref(fact["passage_ref"], event.get("evidence_ref"))}
            result[field] = facts
        elif field == "passages":
            if not isinstance(value, list) or len(value) > 12:
                raise _invalid()
            passages = []
            for passage in value:
                if not isinstance(passage, dict) or set(passage) != {"passage_ref", "start", "end", "text"}:
                    raise _invalid()
                ref = _passage_ref(passage["passage_ref"], event.get("evidence_ref"))
                match = _PASSAGE.fullmatch(ref)
                if type(passage["start"]) is not int or type(passage["end"]) is not int or (passage["start"], passage["end"]) != (int(match[2]), int(match[3])):
                    raise _invalid()
                if not isinstance(passage["text"], str) or len(passage["text"]) > 2000 or len(passage["text"]) != passage["end"] - passage["start"]:
                    raise _invalid()
                passages.append(dict(passage))
            result[field] = passages
        elif field in {"title", "description"}:
            if value is not None and (not isinstance(value, str) or len(value) > (512 if field == "title" else 1500)):
                raise _invalid()
            result[field] = value
        elif field in {"request_id", "task_id", "search_request_id"}:
            result[field] = None if value is None else _token(value)
        elif field in {"availability_verified", "untrusted_source"}:
            if value is not (field == "untrusted_source"):
                raise _invalid("Public source extraction is untrusted evidence, not independent availability verification.")
            result[field] = value
        elif value is None and field.endswith("_ref"):
            result[field] = None
        elif field.endswith("_ref"):
            result[field] = _reference(value)
        else:
            result[field] = _token(value)
    if table == "clearline_session_summaries":
        required = {"session_id", "profile_ref", "summary_id", "summary_version", "created_at", "recording_task", "method_version", "metrics"}
        if not required.issubset(result):
            raise _invalid("Session exports require versioned, provenance-matched complete measurements.")
        metric_origin = result["metrics"].get("data_origin")
        if metric_origin is not None and metric_origin != origin:
            raise _invalid("Measurement provenance does not match the session export.")
    if table == "clearline_resource_versions":
        required = {"source_ref", "evidence_ref", "url", "retrieved_at", "content_hash", "verification_status", "facts", "version"}
        if not required.issubset(result):
            raise _invalid("Public source exports require versioned source-backed evidence.")
        if "passages" in result:
            by_ref = {passage["passage_ref"]: passage["text"] for passage in result["passages"]}
            for fact in result["facts"].values():
                if fact is not None and (fact["passage_ref"] not in by_ref or fact["value"] not in by_ref[fact["passage_ref"]]):
                    raise _invalid("Each exported fact must be present in its supporting passage.")
    return result


def public_resource_projection(evidence: dict[str, Any], *, event_id: str,
                               data_origin: str, export_approved: bool,
                               version: int = 1, session_id: str | None = None) -> dict[str, Any]:
    """Prepare an outbox payload from Nimble evidence, excluding full page text.

    The worker must commit this with the accepted action, then deliver it via
    append_event. ``export_approved`` must come from durable user consent.
    """
    fields = _FIELDS["clearline_resource_versions"] - _COMMON - {"version"}
    payload = {key: evidence[key] for key in fields if key in evidence}
    payload.update(event_id=event_id, data_origin=data_origin, export_approved=export_approved, version=version)
    if session_id is not None:
        payload["session_id"] = session_id
    export_projection("clearline_resource_versions", payload)
    return payload


class RawTreeClient:
    """Small HTTP client with no model-accessible SQL or arbitrary table API."""

    def __init__(self, api_key: str | None = None, database: str | None = None,
                 base_url: str = "https://api.rawtree.com", *,
                 client: httpx.AsyncClient | None = None, timeout: float = 20.0):
        self._api_key = api_key if api_key is not None else os.getenv("RAWTREE_API_KEY", "")
        self.database = database if database is not None else os.getenv("RAWTREE_DATABASE", "default")
        self.base_url = base_url.rstrip("/")
        parsed = urlsplit(self.base_url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path:
            raise _invalid("RAWTREE_BASE_URL must be an HTTPS origin.")
        if "tinybird" in parsed.hostname.lower():
            raise _invalid("Legacy Tinybird endpoints are not RawTree endpoints.")
        self.timeout = timeout
        self._owned_client = client is None
        self._client = client or httpx.AsyncClient(timeout=timeout, follow_redirects=False)

    @classmethod
    def from_env(cls, **kwargs: Any) -> "RawTreeClient":
        return cls(base_url=os.getenv("RAWTREE_BASE_URL", "https://api.rawtree.com"), **kwargs)

    async def aclose(self) -> None:
        if self._owned_client:
            await self._client.aclose()

    async def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        if not self._api_key or not self.database:
            raise IntegrationError("rawtree_not_configured", "RawTree API key and intended database are required.")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", self.database):
            raise _invalid("RawTree database name is invalid.")
        try:
            response = await self._client.post(
                self.base_url + path, json=payload, timeout=self.timeout,
                headers={"Authorization": f"Bearer {self._api_key}", "x-rawtree-database": self.database},
                follow_redirects=False,
            )
        except httpx.TimeoutException:
            raise IntegrationError("rawtree_timeout", "RawTree request timed out; retain the unfinished outbox/action.", True) from None
        except httpx.RequestError:
            raise IntegrationError("rawtree_network", "RawTree could not be reached.", True) from None
        if not 200 <= response.status_code < 300:
            status = response.status_code
            code = "rawtree_unauthorized" if status in {401, 403} else ("rawtree_rate_limited" if status == 429 else "rawtree_http_error")
            raise IntegrationError(code, f"RawTree returned HTTP {status}.", status in {408, 429} or status >= 500)
        try:
            result = response.json()
        except ValueError:
            raise IntegrationError("rawtree_invalid_response", "RawTree returned an invalid JSON response.") from None
        if not isinstance(result, dict):
            raise IntegrationError("rawtree_invalid_response", "RawTree returned an unexpected response shape.")
        return result

    async def append_event(self, table: str, event: dict[str, Any]) -> None:
        """Retry the same event_id from the caller's outbox; inserts can duplicate."""
        payload = export_projection(table, event)
        result = await self._post(f"/v1/tables/{table}", payload)
        if result.get("error") or result.get("success") is False:
            raise IntegrationError("rawtree_insert_failed", "RawTree did not accept the export.")

    async def _query(self, sql: str) -> list[dict[str, Any]]:
        result = await self._post("/v1/query", {"sql": sql})
        rows = result.get("data")
        if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
            raise IntegrationError("rawtree_invalid_response", "RawTree query response has no valid data row list.")
        if len(rows) > 10:
            raise IntegrationError("rawtree_invalid_response", "RawTree returned more rows than the bounded query permits.")
        return rows

    @staticmethod
    def _payload(row: dict[str, Any]) -> dict[str, Any]:
        value = row.get("payload", row.get("__raw_data", row))
        if isinstance(value, str):
            try:
                value = json.loads(value)
            except ValueError:
                raise IntegrationError("rawtree_invalid_response", "Stored RawTree payload is invalid JSON.") from None
        if not isinstance(value, dict):
            raise IntegrationError("rawtree_invalid_response", "Stored RawTree payload has an unexpected shape.")
        return value

    async def read_baseline(self, profile_id: str, session_id: str, *,
                            data_origin: str = "consented_demo", recording_task: str = "check_in",
                            measurement_version: str = "clearline-v1") -> dict[str, Any]:
        profile_id, session_id = _uuid(profile_id), _uuid(session_id)
        profile_ref = hashlib.sha256(profile_id.encode()).hexdigest()
        if data_origin not in {"synthetic", "consented_demo"}:
            raise _invalid()
        recording_task, measurement_version = _token(recording_task), _token(measurement_version)
        # Latest version is selected BEFORE provenance/method filters.
        # A newly differently measured session cannot resurrect
        # an older eligible version. Duplicate deliveries cannot crowd out five
        # distinct sessions because the limit is outside the partition filter.
        sql = f"""SELECT payload FROM (
  SELECT __raw_data AS payload, session_id, data_origin, recording_task,
    method_version, created_at,
    row_number() OVER (PARTITION BY session_id ORDER BY summary_version DESC, event_id DESC) AS latest_rank
  FROM clearline_session_summaries WHERE profile_ref = '{profile_ref}'
) WHERE latest_rank = 1 AND session_id != '{session_id}'
  AND data_origin = '{data_origin}' AND recording_task = '{recording_task}'
  AND method_version = '{measurement_version}'
ORDER BY created_at DESC, session_id DESC LIMIT 5"""
        rows = [self._payload(row) for row in await self._query(sql)]
        sessions: list[dict[str, Any]] = []
        seen: set[str] = set()
        for row in rows:
            # Defensively validate the returned selection as well as the SQL.
            if (row.get("profile_ref") != profile_ref or row.get("session_id") == session_id
                    or row.get("data_origin") != data_origin or row.get("recording_task") != recording_task
                    or row.get("method_version") != measurement_version):
                raise IntegrationError("rawtree_invalid_response", "RawTree baseline rows did not match the requested provenance.")
            sid = _uuid(row.get("session_id"))
            if sid in seen:
                continue
            seen.add(sid)
            row["metrics"] = _metrics(row.get("metrics"))
            sessions.append(row)
        sessions = sessions[:5]
        metrics: dict[str, Any] = {}
        for field in METRICS:
            values = [row["metrics"][field] for row in sessions if row["metrics"][field] is not None]
            metrics[field] = {"mean": statistics.fmean(values) if values else None, "std": statistics.stdev(values) if len(values) >= 2 else None, "session_count": len(values)}
        refs = [row["summary_id"] for row in sessions]
        return {
            "status": "available" if len(sessions) >= 2 else "insufficient_history",
            "session_count": len(sessions), "metrics": metrics,
            "baseline_source": "synthetic_demo_reference" if data_origin == "synthetic" else "consented_demo_history",
            "data_origin": data_origin, "recording_task": recording_task, "method_version": measurement_version,
            "summary_refs": refs, "sessions": refs,
            "baseline_ref": str(uuid5(NAMESPACE_URL, json.dumps(refs, sort_keys=True, separators=(",", ":")))) if refs else None,
            "minimum_sessions": 2,
            "missing_metrics": [field for field in METRICS if metrics[field]["mean"] is None],
            "interpretation": "No health interpretation is provided.",
        }

    async def read_latest_checkpoint(self, session_id: str) -> dict[str, Any] | None:
        session_id = _uuid(session_id)
        rows = await self._query(f"SELECT __raw_data AS payload FROM clearline_checkpoints WHERE session_id = '{session_id}' ORDER BY state_version DESC, event_id DESC LIMIT 1")
        payload = self._payload(rows[0]) if rows else None
        if payload is not None and payload.get("session_id") != session_id:
            raise IntegrationError("rawtree_invalid_response", "RawTree checkpoint did not match the requested session.")
        return payload

    async def read_evidence_version(self, evidence_ref: str, version: int | None = None) -> dict[str, Any] | None:
        evidence_ref = _token(evidence_ref)
        if version is not None and (type(version) is not int or not 0 <= version <= 2**53 - 1):
            raise _invalid()
        predicate = f" AND version = {version}" if version is not None else ""
        rows = await self._query(f"SELECT __raw_data AS payload FROM clearline_resource_versions WHERE evidence_ref = '{evidence_ref}'{predicate} ORDER BY version DESC, event_id DESC LIMIT 1")
        payload = self._payload(rows[0]) if rows else None
        if payload is not None and (payload.get("evidence_ref") != evidence_ref or (version is not None and payload.get("version") != version)):
            raise IntegrationError("rawtree_invalid_response", "RawTree evidence did not match the requested version.")
        return payload

    async def smoke_test(self) -> dict[str, Any]:
        """Insert one labeled synthetic event and query that exact ID back."""
        event_id = str(uuid4())
        event = {"event_id": event_id, "event_type": "sponsor_smoke_test", "data_origin": "synthetic", "schema_version": 1, "created_at": datetime.now(timezone.utc).isoformat()}
        await self.append_event("clearline_events", event)
        for attempt in range(3):
            rows = await self._query(f"SELECT event_id, data_origin FROM clearline_events WHERE event_id = '{event_id}' LIMIT 1")
            if rows and rows[0].get("event_id") == event_id and rows[0].get("data_origin") == "synthetic":
                return {"status": "passed", "service": "rawtree", "event_id": event_id, "database": self.database, "real_network": True}
            if attempt < 2:
                await asyncio.sleep(0.25 * (attempt + 1))
        raise IntegrationError("rawtree_smoke_readback_failed", "The synthetic event was inserted but did not read back from the intended database.", True)


async def _main() -> int:
    client = RawTreeClient.from_env()
    try:
        result = await client.smoke_test()
        print(json.dumps(result))
        return 0
    except IntegrationError as exc:
        print(json.dumps({"status": "blocked", "service": "rawtree", "code": exc.code, "retryable": exc.retryable, "message": str(exc)}))
        return 2
    finally:
        await client.aclose()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(_main()))
