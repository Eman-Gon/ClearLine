"""One model-selected action per durable worker activation.

The worker owns identities, state transitions, action IDs and transactions. This
module never loops past a persistence boundary or treats unavailable AI as success.
"""
from __future__ import annotations

from uuid import UUID
from backend.integrations.common import AgentUnavailable, IntegrationError
from .context import build_messages


def _tool(name, description, properties, required=None):
    return {"type": "function", "function": {"name": name, "description": description, "parameters": {"type": "object", "properties": properties, "required": list(properties) if required is None else required, "additionalProperties": False}}}


STRING = {"type": "string", "minLength": 1, "maxLength": 100}
SCHEMAS = {
    "get_baseline_summary": _tool("get_baseline_summary", "Retrieve eligible previous completed sessions from approved history.", {"profile_id": STRING, "session_id": STRING}),
    "compare_recording_metrics": _tool("compare_recording_metrics", "Compute descriptive differences locally, never medical risk.", {"session_id": STRING, "baseline_ref": {"type": ["string", "null"], "maxLength": 200}}),
    "search_public_resources": _tool("search_public_resources", "Search the exact user-approved resource category and city.", {"category": STRING, "city": STRING}),
    "extract_public_page": _tool("extract_public_page", "Inspect a selected search source for exact source-backed passages.", {"source_ref": {"type": "string", "minLength": 1, "maxLength": 200}}),
    "request_user_input": _tool("request_user_input", "Pause and request missing information from the user.", {"reason_code": {"type": "string", "enum": ["missing_input", "missing_city", "unusable_audio", "insufficient_history", "source_unavailable", "clarification"]}, "question": {"type": "string", "minLength": 1, "maxLength": 500}}),
    "finish_task": _tool("finish_task", "Complete only when requirements are satisfied and evidence is stored.", {"summary": {"type": "string", "minLength": 1, "maxLength": 500}, "evidence_refs": {"type": "array", "items": {"type": "string", "maxLength": 200}, "maxItems": 8}}),
}


def validate_action(action, checkpoint):
    name = action.get("name")
    allowed = checkpoint.get("permitted_tools", [])
    if name not in SCHEMAS or name not in allowed:
        raise IntegrationError("invalid_tool_call", "Model selected a tool unavailable in this phase.")
    args = action.get("arguments")
    props = SCHEMAS[name]["function"]["parameters"]["properties"]
    if not isinstance(args, dict) or set(args) != set(props):
        raise IntegrationError("invalid_tool_call", "Model tool arguments do not match the schema.")
    for key, value in args.items():
        spec = props[key]
        if value is None and "null" in spec["type"]:
            continue
        if spec["type"] == "array":
            if not isinstance(value, list) or len(value) > 8 or any(not isinstance(v, str) or not v or len(v) > 200 for v in value):
                raise IntegrationError("invalid_tool_call", "Invalid evidence references.")
        elif not isinstance(value, str) or not value.strip() or len(value) > spec.get("maxLength", 100) or ("enum" in spec and value not in spec["enum"]):
            raise IntegrationError("invalid_tool_call", "Invalid bounded tool argument.")
    for field in ("profile_id", "session_id"):
        if field in args:
            try:
                valid = str(UUID(args[field])) == str(UUID(checkpoint[field]))
            except (ValueError, KeyError, TypeError):
                valid = False
            if not valid:
                raise IntegrationError("invalid_tool_call", "Model cannot change session or profile identity.")
    if name == "compare_recording_metrics" and args["baseline_ref"] != checkpoint.get("baseline_ref"):
        raise IntegrationError("invalid_tool_call", "Comparison must use the committed baseline reference.")
    if name in ("search_public_resources", "extract_public_page"):
        request = checkpoint.get("resource_request") or {}
        if request.get("approved") is not True:
            raise IntegrationError("consent_required", "Public resource research requires explicit approval.")
        if name == "search_public_resources" and any(args[k] != request.get(k) for k in ("city", "category")):
            raise IntegrationError("invalid_tool_call", "Search must match the approved category and city.")
        if name == "extract_public_page":
            _find_source(args["source_ref"], checkpoint)
            verified = checkpoint.get("resources") or []
            if isinstance(verified, list) and any(r.get("source_ref") == args["source_ref"] and r.get("verification_status") == "source_backed" for r in verified):
                raise IntegrationError("already_completed", "This source already has a committed result for the current request.")
    if name == "finish_task":
        if checkpoint.get("unresolved_requirements"):
            raise IntegrationError("unfinished_requirements", "Task still has unresolved requirements.")
        available = set(checkpoint.get("evidence_refs", []))
        if not set(args["evidence_refs"]).issubset(available):
            raise IntegrationError("invalid_tool_call", "Completion references uncommitted evidence.")
        researching = checkpoint.get("workflow_scope") == "resources" or ("workflow_scope" not in checkpoint and checkpoint.get("phase") == "researching")
        if researching and checkpoint.get("resource_request") and not args["evidence_refs"]:
            raise IntegrationError("unfinished_requirements", "Research completion requires extracted evidence.")
    return {"name": name, "arguments": args}


def _find_source(source_ref, checkpoint):
    sources = checkpoint.get("sources") or checkpoint.get("resources") or []
    if isinstance(sources, dict):
        sources = sources.get("sources", [])
    for source in sources:
        if source.get("source_ref") == source_ref:
            return source
    raise IntegrationError("invalid_source", "Extraction source was not returned by this approved search.")


class LiquidAgentRunner:
    def __init__(self, liquid, rawtree, nimble):
        self.liquid, self.rawtree, self.nimble = liquid, rawtree, nimble

    async def advance(self, checkpoint):
        if checkpoint.get("phase") == "paused":
            raise IntegrationError("paused", "Workflow is paused.")
        names = checkpoint.get("permitted_tools", [])
        if not names or any(n not in SCHEMAS for n in names):
            raise IntegrationError("invalid_checkpoint", "No valid permitted tool menu was supplied.")
        response = await self.liquid.complete(build_messages(checkpoint), [SCHEMAS[n] for n in names], tool_choice="required")
        calls = response.get("tool_calls", [])
        if len(calls) != 1:
            raise AgentUnavailable("Liquid must return exactly one parsed tool call.")
        action = validate_action(calls[0], checkpoint)
        action["model_call"] = response["message"]
        action["context_usage"] = {"usage": response.get("usage"), "context": response.get("context"), "model": response.get("identity")}
        return action

    async def execute(self, action, checkpoint):
        validated = validate_action(action, checkpoint)
        name, args = validated["name"], validated["arguments"]
        if name == "get_baseline_summary":
            return await self.rawtree.read_baseline(args["profile_id"], args["session_id"], data_origin=(checkpoint.get("metrics") or {}).get("data_origin", checkpoint.get("data_origin", "consented_demo")), recording_task=checkpoint.get("recording_task", "check_in"), measurement_version=checkpoint.get("method_version", checkpoint.get("measurement_version", "v1")))
        if name == "search_public_resources":
            return await self.nimble.search(args["category"], args["city"])
        if name == "extract_public_page":
            return await self.nimble.extract(_find_source(args["source_ref"], checkpoint))
        raise IntegrationError("worker_owned_tool", "This deterministic action must be executed by the durable worker.")

    async def aclose(self):
        for client in (self.liquid, self.rawtree, self.nimble):
            close = getattr(client, "aclose", None)
            if close:
                await close()
