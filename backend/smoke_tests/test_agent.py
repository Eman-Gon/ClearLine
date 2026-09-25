import json
import unittest
from copy import deepcopy
from unittest.mock import AsyncMock
from backend.agent.context import build_messages
from backend.agent.runner import LiquidAgentRunner, validate_action
from backend.integrations.common import IntegrationError

SID = "cd948283-6a4c-4784-94d7-8e18d043d663"
PID = "79c7a4e3-5ac5-443d-97a2-659da0f6940d"


def checkpoint():
    return {"session_id": SID, "profile_id": PID, "phase": "researching", "goal": "Find approved resources", "resource_request": {"category": "caregiver support groups", "city": "Oakland", "approved": True}, "permitted_tools": ["search_public_resources", "extract_public_page", "request_user_input", "finish_task"], "unresolved_requirements": ["extract a source"], "resources": [{"source_ref": "source-1", "url": "https://example.org/", "title": "Support"}]}


class ContextTests(unittest.TestCase):
    def test_history_does_not_inflate_prompt(self):
        state = checkpoint()
        before = build_messages(state)
        state["events"] = [{"transcript": "private words", "secret": "never include"}] * 100000
        self.assertEqual(before, build_messages(state))
        self.assertNotIn("private words", json.dumps(build_messages(state)))

    def test_obligations_are_not_silently_pruned(self):
        state = checkpoint()
        state["unresolved_requirements"] = ["needed"] * 9
        with self.assertRaises(IntegrationError):
            build_messages(state)

    def test_tool_result_is_restored_after_restart(self):
        state = checkpoint()
        state["last_model_call"] = {"role": "assistant", "tool_calls": [{"id": "call1", "type": "function", "function": {"name": "search_public_resources", "arguments": '{"category":"caregiver support groups","city":"Oakland"}'}}]}
        state["last_tool_result"] = {"sources": state["resources"], "raw_html": "not injected"}
        restored = json.loads(json.dumps(state))
        messages = build_messages(restored)
        self.assertEqual(messages[-1]["role"], "tool")
        self.assertEqual(messages[-1]["tool_call_id"], "call1")
        self.assertNotIn("not injected", json.dumps(messages))


class ValidationTests(unittest.TestCase):
    def test_unapproved_and_changed_search_rejected(self):
        state = checkpoint()
        action = {"name": "search_public_resources", "arguments": {"category": "caregiver support groups", "city": "Oakland"}}
        self.assertEqual(validate_action(action, state), action)
        state["resource_request"]["approved"] = False
        with self.assertRaises(IntegrationError):
            validate_action(action, state)
        state = checkpoint()
        action["arguments"]["city"] = "Boston"
        with self.assertRaises(IntegrationError):
            validate_action(action, state)

    def test_arbitrary_extract_and_early_finish_rejected(self):
        for action in ({"name": "extract_public_page", "arguments": {"source_ref": "unreturned"}}, {"name": "finish_task", "arguments": {"summary": "Done", "evidence_refs": []}}):
            with self.assertRaises(IntegrationError):
                validate_action(action, checkpoint())

    def test_identity_cannot_change(self):
        state = checkpoint()
        state["permitted_tools"] = ["get_baseline_summary"]
        with self.assertRaises(IntegrationError):
            validate_action({"name": "get_baseline_summary", "arguments": {"profile_id": SID, "session_id": SID}}, state)

    def test_comparison_can_finish_with_separate_resource_request(self):
        state = checkpoint()
        state.update(workflow_scope="comparison", unresolved_requirements=[])
        validate_action({"name": "finish_task", "arguments": {"summary": "Comparison complete", "evidence_refs": []}}, state)

    def test_committed_source_is_not_extracted_again(self):
        state = checkpoint()
        state["sources"] = deepcopy(state["resources"])
        state["resources"][0]["verification_status"] = "source_backed"
        with self.assertRaises(IntegrationError):
            validate_action({"name": "extract_public_page", "arguments": {"source_ref": "source-1"}}, state)


class RunnerTests(unittest.IsolatedAsyncioTestCase):
    async def test_select_then_execute_are_separate_persistence_boundaries(self):
        action = {"id": "call1", "name": "search_public_resources", "arguments": {"city": "Oakland", "category": "caregiver support groups"}}
        message = {"role": "assistant", "tool_calls": [{"id": "call1", "type": "function", "function": {"name": action["name"], "arguments": json.dumps(action["arguments"])}}]}
        liquid = AsyncMock()
        liquid.complete.return_value = {"tool_calls": [action], "message": message, "context": {"prompt_tokens": 500}}
        nimble = AsyncMock()
        nimble.search.return_value = {"sources": []}
        runner = LiquidAgentRunner(liquid, AsyncMock(), nimble)
        planned = await runner.advance(checkpoint())
        nimble.search.assert_not_called()
        self.assertEqual(planned["model_call"], message)
        # Worker durably stores planned before this call; no internal hidden loop.
        await runner.execute(json.loads(json.dumps(planned)), checkpoint())
        nimble.search.assert_awaited_once_with("caregiver support groups", "Oakland")

    async def test_chat_only_response_is_not_agent_success(self):
        liquid = AsyncMock()
        liquid.complete.return_value = {"tool_calls": [], "message": {"content": "Done"}}
        runner = LiquidAgentRunner(liquid, AsyncMock(), AsyncMock())
        with self.assertRaises(IntegrationError):
            await runner.advance(checkpoint())


if __name__ == "__main__":
    unittest.main()
