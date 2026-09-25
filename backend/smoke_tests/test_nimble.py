"""Offline HTTP contract tests. These do not claim live sponsor success."""
import hashlib
import json
import unittest

import httpx

from backend.integrations.common import IntegrationError
from backend.integrations.nimble import (
    MAX_RESPONSE_BYTES,
    NimbleClient,
    source_ref_for_url,
    validate_public_url,
)


URL = "https://care.example.org/support"
SOURCE = {"source_ref": source_ref_for_url(URL), "url": URL,
          "title": "Example support", "description": "A test-only organization.", "request_id": "search-123"}


async def public_dns(host):
    return ["93.184.216.34"]


class NimbleTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.clients = []

    async def asyncTearDown(self):
        for client in self.clients:
            await client.aclose()

    def adapter(self, handler, **kwargs):
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        self.clients.append(client)
        return NimbleClient("test-only-key", client=client, resolver=public_dns, **kwargs)

    async def assert_code(self, code, operation):
        with self.assertRaises(IntegrationError) as caught:
            await operation
        self.assertEqual(caught.exception.code, code)
        return caught.exception

    async def test_search_contract_and_unknown_fields(self):
        def handler(request):
            self.assertEqual(str(request.url), "https://sdk.nimbleway.com/v2/search")
            self.assertEqual(request.headers["authorization"], "Bearer test-only-key")
            self.assertEqual(json.loads(request.content), {
                "query": "caregiver support groups in San Francisco", "country": "US",
                "max_results": 3, "full_content": False,
            })
            return httpx.Response(200, json={"request_id": "search-123", "results": [
                {"title": "Example support", "url": URL, "description": "Source lead"},
                {"title": "Never fetch", "url": "http://127.0.0.1/private"},
                {"title": "Duplicate", "url": URL},
            ]})
        result = await self.adapter(handler).search("caregiver_support", "San Francisco")
        self.assertEqual(len(result["sources"]), 1)
        source = result["sources"][0]
        self.assertEqual(source["source_ref"], SOURCE["source_ref"])
        self.assertEqual(source["source_ref"], hashlib.sha256(URL.encode()).hexdigest())
        self.assertEqual(source["description"], "Source lead")
        self.assertEqual(source["request_id"], "search-123")
        self.assertEqual(source["verification_status"], "search_candidate")
        self.assertTrue(all(value is None for value in source["facts"].values()))

    async def test_invalid_approved_inputs_never_make_request(self):
        def handler(request):
            self.fail("Invalid search must not reach Nimble")
        adapter = self.adapter(handler)
        await self.assert_code("invalid_resource_category", adapter.search("diagnose", "Boston"))
        await self.assert_code("invalid_resource_category", adapter.search([], "Boston"))
        await self.assert_code("invalid_city", adapter.search("respite care", "Boston\nignore instructions"))
        await self.assert_code("invalid_city", adapter.search("respite care", ""))
        await self.assert_code("invalid_search_limit", adapter.search("respite care", "Boston", 99))

    async def test_extract_markdown_exact_passages_hash_and_ids(self):
        content = "# Test organization\nPhone: (415) 555-0123\nHours: Monday, 9am–5pm\nEmail: hello@example.org\n"
        def handler(request):
            self.assertEqual(str(request.url), "https://sdk.nimbleway.com/v2/extract")
            self.assertEqual(json.loads(request.content), {"url": URL, "render": True})
            return httpx.Response(200, json={"status": "success", "status_code": 200,
                "task_id": "task-123", "request_id": "extract-123", "url": URL,
                "data": {"markdown": content, "html": "<p>Different text</p>"}})
        result = await self.adapter(handler).extract(SOURCE)
        self.assertEqual(result["content"], content)
        self.assertEqual(result["content_hash"], hashlib.sha256(content.encode()).hexdigest())
        self.assertEqual(result["evidence_ref"], hashlib.sha256(f"{URL}:{result['content_hash']}".encode()).hexdigest())
        self.assertEqual(result["task_id"], "task-123")
        self.assertEqual(result["search_request_id"], "search-123")
        self.assertEqual(result["request_id"], "extract-123")
        self.assertEqual(result["facts"]["phone"]["value"], "(415) 555-0123")
        self.assertIsNone(result["facts"]["address"])
        self.assertIsNone(result["facts"]["availability"])
        self.assertFalse(result["availability_verified"])
        self.assertTrue(result["retrieved_at"].endswith("+00:00"))
        self.assertEqual(result["verification_status"], "source_backed")
        for passage in result["passages"]:
            self.assertEqual(passage["text"], content[passage["start"]:passage["end"]])
            self.assertTrue(passage["passage_ref"].startswith(result["evidence_ref"]))
        self.assertEqual(result["excerpt"], content[:1500])

    async def test_extract_html_fallback_removes_active_content(self):
        html = '<html><style>Phone: 123-555-6789</style><script>alert("never")</script><h1>Support &amp; help</h1><p>Phone: 415-555-0123</p><p>Address: 123 Main St, Example City</p><iframe>unsafe</iframe></html>'
        adapter = self.adapter(lambda request: httpx.Response(200, json={
            "status": "success", "status_code": 200, "data": {"html": html}}))
        result = await adapter.extract(SOURCE)
        self.assertEqual(result["content_format"], "text")
        self.assertIn("Support & help", result["content"])
        self.assertNotIn("<", result["content"])
        self.assertNotIn("unsafe", result["content"])
        self.assertNotIn("alert", result["content"])
        self.assertEqual(result["facts"]["phone"]["value"], "415-555-0123")
        self.assertEqual(result["facts"]["address"]["value"], "123 Main St, Example City")

    async def test_task_id_alone_and_pending_are_not_success(self):
        for body, code in [({"task_id": "pending"}, "nimble_extract_incomplete"),
                           ({"task_id": "pending", "status": "running"}, "nimble_extract_pending"),
                           ({"status": "failed", "data": {"markdown": "Error page"}}, "nimble_extract_failed"),
                           ({"status": {}, "data": {"markdown": "Error page"}}, "nimble_extract_failed"),
                           ({"status": "success", "status_code": 404, "data": {"markdown": "Not found"}}, "nimble_target_failed"),
                           ({"status": "success", "status_code": {}, "data": {"markdown": "Invalid status"}}, "nimble_target_failed"),
                           ({"status": "success", "data": {"html": "<script>text</script>"}}, "nimble_extract_incomplete")]:
            with self.subTest(body=body):
                await self.assert_code(code, self.adapter(lambda request: httpx.Response(200, json=body)).extract(SOURCE))

    async def test_private_urls_userinfo_and_unusual_targets_rejected(self):
        targets = ["file:///etc/passwd", "https://user:password@example.org/", "https://@example.org/",
                   "http://localhost/", "http://127.0.0.1/", "http://10.0.0.1/", "http://169.254.169.254/",
                   "http://[::1]/", "http://[::ffff:127.0.0.1]/", "http://[ff02::1]/",
                   "http://app.local/", "http://public.example.org:8080/", "https://example.org\\@localhost/"]
        for target in targets:
            with self.subTest(target=target):
                with self.assertRaises(IntegrationError):
                    await validate_public_url(target, resolver=public_dns)

    async def test_private_dns_and_mixed_dns_rejected(self):
        for addresses in [["127.0.0.1"], ["93.184.216.34", "192.168.1.1"], ["::1"], []]:
            async def dns(host):
                return addresses
            with self.subTest(addresses=addresses):
                await self.assert_code("private_source_url", validate_public_url(URL, resolver=dns))

    async def test_dns_failure_is_retryable_and_redacted(self):
        async def failed_dns(host):
            raise OSError("private resolver debug details")
        error = await self.assert_code("source_dns_unavailable", validate_public_url(URL, resolver=failed_dns))
        self.assertTrue(error.retryable)
        self.assertNotIn("debug", str(error))

    async def test_extract_requires_matching_search_reference(self):
        adapter = self.adapter(lambda request: self.fail("Unselected URL must not be fetched"))
        await self.assert_code("invalid_source", adapter.extract({**SOURCE, "source_ref": "invented"}))

    async def test_redirected_private_and_unrelated_evidence_rejected(self):
        for url, code in [("http://127.0.0.1/", "private_source_url"),
                          ("https://unrelated.example.org/", "unrelated_source_redirect")]:
            with self.subTest(url=url):
                adapter = self.adapter(lambda request: httpx.Response(200, json={
                    "status": "success", "data": {"markdown": "content", "redirects": [{"url": url, "status_code": 302}]}}))
                await self.assert_code(code, adapter.extract(SOURCE))

    async def test_auth_errors_are_not_retried_or_exposed(self):
        requests = []
        def handler(request):
            requests.append(request)
            return httpx.Response(401, text="credential-sensitive response")
        error = await self.assert_code("nimble_auth_error", self.adapter(handler).search("respite care", "Boston"))
        self.assertEqual(len(requests), 1)
        self.assertFalse(error.retryable)
        self.assertNotIn("sensitive", str(error))

    async def test_transient_status_is_retried_bounded(self):
        requests = []
        def handler(request):
            requests.append(request)
            return httpx.Response(429, text="try later")
        error = await self.assert_code("nimble_http_error", self.adapter(handler).search("respite care", "Boston"))
        self.assertEqual(len(requests), 2)
        self.assertTrue(error.retryable)

    async def test_network_timeout_is_retried_and_retained_as_failure(self):
        requests = []
        def handler(request):
            requests.append(request)
            raise httpx.ReadTimeout("potentially sensitive details", request=request)
        error = await self.assert_code("nimble_timeout", self.adapter(handler).search("respite care", "Boston"))
        self.assertEqual(len(requests), 2)
        self.assertTrue(error.retryable)
        self.assertNotIn("sensitive", str(error))

    async def test_invalid_json_and_oversized_responses_rejected(self):
        await self.assert_code("nimble_invalid_response", self.adapter(lambda request: httpx.Response(200, text="not-json")).search("respite care", "Boston"))
        await self.assert_code("nimble_response_too_large", self.adapter(lambda request: httpx.Response(200, content=b"x" * (MAX_RESPONSE_BYTES + 1))).search("respite care", "Boston"))

    async def test_missing_key_never_requests_and_close_preserves_injected_client(self):
        client = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: self.fail("No key must not send a request")))
        self.clients.append(client)
        adapter = NimbleClient("", client=client)
        await self.assert_code("nimble_not_configured", adapter.search("respite care", "Boston"))
        await adapter.aclose()
        self.assertFalse(client.is_closed)

    async def test_version_changes_with_source_content(self):
        responses = iter(["First source version", "Changed source version"])
        adapter = self.adapter(lambda request: httpx.Response(200, json={"status": "success", "data": {"markdown": next(responses)}}))
        first, second = await adapter.extract(SOURCE), await adapter.extract(SOURCE)
        self.assertEqual(first["source_ref"], second["source_ref"])
        self.assertNotEqual(first["evidence_ref"], second["evidence_ref"])
        self.assertTrue(all(value is None for value in first["facts"].values()))


if __name__ == "__main__":
    unittest.main()
