"""Transport contract tests; these mocks are not a live Liquid acceptance pass."""
from __future__ import annotations

import json
import unittest

import httpx

from backend.integrations.common import IntegrationError
from backend.integrations.liquid import LiquidClient


TOOLS = [{"type": "function", "function": {
    "name": "echo_nonce", "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
}}]
MESSAGES = [{"role": "user", "content": "Please call the tool."}]


def completion(*, arguments="{}", name="echo_nonce", call_id="call_1", content=None,
               calls=True, usage=True, model="clearline-liquid", finish_reason=None):
    message = {"role": "assistant", "content": content}
    if calls:
        message["tool_calls"] = [{"type": "function", "id": call_id,
                                  "function": {"name": name, "arguments": arguments}}]
    result = {"model": model, "choices": [{"message": message,
              "finish_reason": finish_reason or ("tool_calls" if calls else "stop")}]}
    if usage:
        result["usage"] = {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110}
    return result


class MockRuntime:
    def __init__(self, response=None, token_count=100, models=None, runtime_context=4096):
        self.response = response if response is not None else completion()
        self.token_count = token_count
        self.models = models if models is not None else {"data": [{"id": "clearline-liquid", "owned_by": "llamacpp"}]}
        self.runtime_context = runtime_context
        self.requests = []
        self.overrides = {}

    def __call__(self, request):
        payload = json.loads(request.content) if request.content else None
        self.requests.append((request, payload))
        path = request.url.path
        if path in self.overrides:
            return self.overrides[path]
        if path == "/v1/models":
            return httpx.Response(200, json=self.models)
        if path == "/props":
            return httpx.Response(200, json={"build_info": "test-build", "model_path": "/models/example-Q4_K_M.gguf",
                "model_ftype": "Q4_K_M", "default_generation_settings": {"n_ctx": self.runtime_context}})
        if path == "/apply-template":
            return httpx.Response(200, json={"prompt": json.dumps({
                "messages": payload["messages"], "tools": payload["tools"],
            })})
        if path == "/tokenize":
            return httpx.Response(200, json={"tokens": list(range(self.token_count))})
        if path == "/v1/chat/completions":
            return httpx.Response(200, json=self.response)
        raise AssertionError(f"Unexpected path {path}")


class LiquidTests(unittest.IsolatedAsyncioTestCase):
    async def client_for(self, runtime, **kwargs):
        http = httpx.AsyncClient(transport=httpx.MockTransport(runtime))
        self.addAsyncCleanup(http.aclose)
        return LiquidClient(client=http, **kwargs)

    async def test_real_request_shape_and_measured_schema_budget(self):
        runtime = MockRuntime()
        client = await self.client_for(runtime)
        result = await client.complete(MESSAGES, TOOLS)
        self.assertEqual(result["tool_calls"], [{"id": "call_1", "name": "echo_nonce", "arguments": {}}])
        self.assertEqual(result["context"]["prompt_tokens"], 100)
        self.assertEqual(result["usage"]["prompt_tokens"], 100)
        self.assertEqual(result["identity"]["runtime_build"], "test-build")
        self.assertEqual(result["identity"]["model_file"], "example-Q4_K_M.gguf")
        requests = {request.url.path: (request, payload) for request, payload in runtime.requests}
        template = next(payload for request, payload in runtime.requests if request.url.path == "/apply-template")
        self.assertEqual(template, requests["/v1/chat/completions"][1])
        self.assertEqual(template["tools"], TOOLS)
        self.assertEqual(template["max_tokens"], 768)
        tokenize = requests["/tokenize"][1]
        self.assertTrue(tokenize["add_special"])
        self.assertTrue(tokenize["parse_special"])
        self.assertTrue(all("authorization" not in request.headers for request, _ in runtime.requests))

    async def test_oversize_context_never_generates(self):
        runtime = MockRuntime(token_count=3073)
        client = await self.client_for(runtime)
        with self.assertRaises(IntegrationError) as raised:
            await client.complete(MESSAGES, TOOLS)
        self.assertEqual(raised.exception.code, "context_budget_exceeded")
        self.assertNotIn("/v1/chat/completions", [request.url.path for request, _ in runtime.requests])

    async def test_runtime_slot_context_smaller_than_configured_is_enforced(self):
        runtime = MockRuntime(token_count=1300, runtime_context=2048)
        client = await self.client_for(runtime)
        with self.assertRaises(IntegrationError) as raised:
            await client.complete(MESSAGES, TOOLS)
        self.assertEqual(raised.exception.code, "context_budget_exceeded")

    async def test_unavailable_tokenization_never_uses_estimates(self):
        for endpoint in ("/apply-template", "/tokenize"):
            with self.subTest(endpoint=endpoint):
                runtime = MockRuntime()
                runtime.overrides[endpoint] = httpx.Response(404, json={"error": "not supported"})
                client = await self.client_for(runtime)
                with self.assertRaises(IntegrationError) as raised:
                    await client.complete(MESSAGES, TOOLS)
                self.assertEqual(raised.exception.code, "context_budget_unavailable")
                self.assertNotIn("/v1/chat/completions", [request.url.path for request, _ in runtime.requests])

    async def test_template_cannot_silently_ignore_tools(self):
        runtime = MockRuntime()
        runtime.overrides["/apply-template"] = httpx.Response(200, json={"prompt": "user only"})
        client = await self.client_for(runtime)
        with self.assertRaises(IntegrationError) as raised:
            await client.complete(MESSAGES, TOOLS)
        self.assertEqual(raised.exception.code, "context_budget_unavailable")

    async def test_tool_name_in_user_prompt_is_not_schema_support(self):
        runtime = MockRuntime()
        runtime.overrides["/apply-template"] = httpx.Response(200, json={"prompt": "Please call echo_nonce"})
        client = await self.client_for(runtime)
        with self.assertRaises(IntegrationError) as raised:
            await client.complete(MESSAGES, TOOLS)
        self.assertEqual(raised.exception.code, "context_budget_unavailable")

    async def test_alias_mismatch_stops_before_inference(self):
        runtime = MockRuntime(models={"data": [{"id": "other-model"}]})
        client = await self.client_for(runtime)
        with self.assertRaises(IntegrationError):
            await client.complete(MESSAGES, TOOLS)
        self.assertEqual(len(runtime.requests), 1)

    async def test_model_identity_is_rechecked_after_runtime_restart(self):
        runtime = MockRuntime()
        client = await self.client_for(runtime)
        await client.complete(MESSAGES, TOOLS)
        runtime.models = {"data": [{"id": "new-model"}]}
        with self.assertRaises(IntegrationError):
            await client.complete(MESSAGES, TOOLS)
        self.assertEqual(sum(request.url.path == "/v1/chat/completions" for request, _ in runtime.requests), 1)

    async def test_invalid_structured_calls_are_rejected(self):
        bad_outputs = [
            completion(arguments="{'python': True}"),
            completion(arguments="[]"),
            completion(arguments={}),
            completion(arguments='{"city":"A","city":"B"}'),
            completion(arguments='{"value":NaN}'),
            completion(arguments='{"value":1e999}'),
            completion(name="execute_python"),
            completion(call_id=""),
            completion(call_id="bad id"),
            completion(calls=False, content="echo_nonce()", finish_reason="tool_calls"),
            completion(finish_reason="length"),
            completion(model="wrong-model"),
            {"model": "clearline-liquid", "choices": []},
        ]
        for output in bad_outputs:
            with self.subTest(output=output):
                client = await self.client_for(MockRuntime(response=output))
                with self.assertRaises(IntegrationError) as raised:
                    await client.complete(MESSAGES, TOOLS)
                self.assertEqual(raised.exception.code, "agent_unavailable")

    async def test_forced_tool_cannot_be_replaced_by_raw_text(self):
        client = await self.client_for(MockRuntime(response=completion(calls=False, content="echo_nonce()")))
        with self.assertRaises(IntegrationError):
            await client.complete(MESSAGES, TOOLS, {"type": "function", "function": {"name": "echo_nonce"}})

    async def test_missing_usage_is_null_and_preflight_stays_separate(self):
        runtime = MockRuntime(response=completion(usage=False))
        client = await self.client_for(runtime)
        result = await client.complete(MESSAGES, TOOLS)
        self.assertIsNone(result["usage"]["prompt_tokens"])
        self.assertEqual(result["context"]["prompt_tokens"], 100)

    async def test_posthoc_usage_overflow_does_not_execute_selected_call(self):
        output = completion()
        output["usage"]["prompt_tokens"] = 3500
        client = await self.client_for(MockRuntime(response=output))
        with self.assertRaises(IntegrationError) as raised:
            await client.complete(MESSAGES, TOOLS)
        self.assertEqual(raised.exception.code, "context_budget_exceeded")

    async def test_connection_failure_is_explicit_and_retryable(self):
        def offline(request):
            raise httpx.ConnectError("sensitive details must not be relayed", request=request)
        client = await self.client_for(offline)
        with self.assertRaises(IntegrationError) as raised:
            await client.complete(MESSAGES, TOOLS)
        self.assertEqual(raised.exception.code, "agent_unavailable")
        self.assertTrue(raised.exception.retryable)
        self.assertNotIn("sensitive", str(raised.exception))

    async def test_redirect_is_not_followed(self):
        runtime = MockRuntime()
        runtime.overrides["/v1/models"] = httpx.Response(307, headers={"Location": "https://example.com/v1/models"})
        client = await self.client_for(runtime)
        with self.assertRaises(IntegrationError):
            await client.verify_model()
        self.assertEqual(len(runtime.requests), 1)

    async def test_nonce_smoke_requires_tool_execution_then_result_use(self):
        runtime = MockRuntime()
        captured = []

        def handler(request):
            if request.url.path == "/v1/chat/completions":
                body = json.loads(request.content)
                captured.append(body)
                if len(captured) == 2:
                    nonce = json.loads(body["messages"][-1]["content"])["nonce"]
                    self.assertNotIn(nonce, json.dumps(captured[0]))
                    self.assertEqual(body["messages"][-1]["tool_call_id"], "call_1")
                    return httpx.Response(200, json=completion(calls=False, content=nonce))
            return runtime(request)

        client = await self.client_for(handler)
        result = await client.echo_nonce_smoke()
        self.assertEqual(result["status"], "passed")
        self.assertTrue(result["nonce_round_trip"])
        self.assertEqual(len(captured), 2)
        self.assertEqual(captured[0]["tool_choice"], {"type": "function", "function": {"name": "echo_nonce"}})

    async def test_nonce_smoke_rejects_failed_second_turn(self):
        runtime = MockRuntime()
        count = 0

        def handler(request):
            nonlocal count
            if request.url.path == "/v1/chat/completions":
                count += 1
                if count == 2:
                    return httpx.Response(200, json=completion(calls=False, content="guessed nonce"))
            return runtime(request)

        client = await self.client_for(handler)
        with self.assertRaises(IntegrationError):
            await client.echo_nonce_smoke()

    async def test_injected_client_lifecycle_is_preserved(self):
        client = await self.client_for(MockRuntime())
        await client.aclose()
        self.assertFalse(client._client.is_closed)


class LocalConfigurationTests(unittest.TestCase):
    def test_nonloopback_servers_and_credential_urls_rejected(self):
        for url in (
            "https://api.example.com/v1", "http://192.168.1.5:8080/v1", "http://0.0.0.0:8080/v1",
            "http://user:password@127.0.0.1:8080/v1", "http://127.0.0.1:8080/v1?key=secret",
            "http://localhost.example.com:8080/v1", "http://127.0.0.1:8080/other",
        ):
            with self.subTest(url=url), self.assertRaises(IntegrationError):
                LiquidClient(url)


if __name__ == "__main__":
    unittest.main()
