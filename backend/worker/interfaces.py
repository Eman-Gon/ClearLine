"""Session 3 integration seam; no network adapters or model implementation here.

advance() selects ONE permitted action, returns {name, arguments}. The worker
assigns action_id before execute(), checkpoints it, then commits the result and
state together. execute() must be read-only externally (search/extract/baseline).

Results: get_baseline_summary -> baseline dict with status/session_count/metrics;
search_public_resources -> {sources: [{source_ref,url,title,...}], ...};
extract_public_page -> {source_ref,url,retrieved_at,content_hash,evidence_ref,
verification_status:'source_backed',facts:{field:{value,passage_ref}},...}.
Missing facts remain null. finish_task arguments: summary, evidence_refs.

Checkpoints are bounded local projections; they are NOT cloud export payloads.
Factories: backend.agent.create_agent_runner() and create_exporter().
"""
from typing import Protocol, Any


class AgentUnavailable(RuntimeError):
    code = 'agent_unavailable'
    retryable = False


class AgentRunner(Protocol):
    async def advance(self, checkpoint: dict[str, Any]) -> dict[str, Any]: ...
    async def execute(self, action: dict[str, Any], checkpoint: dict[str, Any]) -> dict[str, Any]: ...


class EventExporter(Protocol):
    async def append_event(self, table: str, payload: dict[str, Any]) -> None: ...


class UnavailableRunner:
    async def advance(self, checkpoint):
        raise AgentUnavailable('Liquid integration is not configured or available.')

    async def execute(self, action, checkpoint):
        raise AgentUnavailable('Sponsor integration is not configured or available.')
