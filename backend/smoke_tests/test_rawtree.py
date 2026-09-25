"""Contract tests use httpx.MockTransport; they are not credentialed smoke tests."""
from __future__ import annotations

import hashlib
import json
import sqlite3
import unittest
from uuid import uuid4

import httpx

from backend.integrations.common import IntegrationError
from backend.integrations.rawtree import RawTreeClient, export_projection, public_resource_projection


def ident() -> str:
    return str(uuid4())


class RawTreeTests(unittest.IsolatedAsyncioTestCase):
    def make_client(self, handler, **kwargs):
        transport = httpx.MockTransport(handler)
        http = httpx.AsyncClient(transport=transport)
        self.addAsyncCleanup(http.aclose)
        return RawTreeClient(api_key="test-secret-never-print", database="clearline_test", client=http, **kwargs)

    async def test_append_uses_rawtree_headers_and_same_event_id_on_retry(self):
        requests = []
        def handle(request):
            requests.append(request)
            return httpx.Response(200, json={"inserted": 1})
        client = self.make_client(handle)
        event = {"event_id": ident(), "event_type": "session_created", "data_origin": "consented_demo", "export_approved": True}
        await client.append_event("clearline_events", event)
        await client.append_event("clearline_events", event)
        self.assertEqual(len(requests), 2)
        self.assertEqual(str(requests[0].url), "https://api.rawtree.com/v1/tables/clearline_events")
        self.assertEqual(requests[0].headers["authorization"], "Bearer test-secret-never-print")
        self.assertEqual(requests[0].headers["x-rawtree-database"], "clearline_test")
        self.assertEqual(json.loads(requests[0].content), json.loads(requests[1].content))
        self.assertNotIn("export_approved", json.loads(requests[0].content))

    async def test_unapproved_or_private_payloads_never_make_network_call(self):
        def fail(request):
            self.fail("Rejected data reached HTTP")
        client = self.make_client(fail)
        base = {"event_id": ident(), "event_type": "session_created", "data_origin": "consented_demo"}
        for extra in ({}, {"export_approved": True, "transcript": "Private"}, {"export_approved": True, "name": "Private"}, {"export_approved": True, "model_messages": []}, {"export_approved": True, "audio": "AAAA"}, {"export_approved": True, "api_key": "secret"}):
            with self.subTest(extra=list(extra)), self.assertRaises(IntegrationError):
                await client.append_event("clearline_events", {**base, **extra})
        with self.assertRaises(IntegrationError):
            await client.append_event("arbitrary_table", {**base, "export_approved": True})

    def summary(self, profile_id, session_id, version=1, index=1, **changes):
        return {
            "event_id": ident(), "session_id": session_id,
            "profile_ref": hashlib.sha256(profile_id.encode()).hexdigest(),
            "state_version": version, "created_at": f"2026-09-{index:02}T12:00:00+00:00",
            "data_origin": "synthetic", "export_approved": True,
            "summary_id": ident(), "summary_version": version, "input_revision": 0,
            "method_version": "clearline-v1", "recording_task": "check_in",
            "metrics": {"duration_s": 20, "word_count": 40, "recording_wpm": 120, "energy_rms": 0.2, "pause_count": None, "pitch_mean_hz": None, "quality": "accepted", "quality_reasons": [], "data_origin": "synthetic"},
            **changes,
        }

    def test_worker_summary_projection_is_accepted_and_transcript_is_rejected(self):
        summary = self.summary(ident(), ident())
        exported = export_projection("clearline_session_summaries", summary)
        self.assertNotIn("export_approved", exported)
        self.assertIsNone(exported["metrics"]["pitch_mean_hz"])
        summary["metrics"]["transcript"] = "Private"
        with self.assertRaises(IntegrationError):
            export_projection("clearline_session_summaries", summary)

    def test_actual_nimble_evidence_shape_preserves_source_facts_and_unknowns(self):
        from backend.integrations.nimble import _source_facts, source_ref_for_url
        url = "https://example.org/support"
        content = "Phone: (555) 123-4567\nEmail: support@example.org\nHours: Mon-Fri 9am-5pm"
        content_hash = hashlib.sha256(content.encode()).hexdigest()
        evidence_ref = hashlib.sha256(f"{url}:{content_hash}".encode()).hexdigest()
        facts, passages = _source_facts(content, evidence_ref)
        evidence = {"source_ref": source_ref_for_url(url), "evidence_ref": evidence_ref, "content_hash": content_hash,
                    "url": url, "title": "Public support", "description": None, "request_id": None, "task_id": None, "search_request_id": None,
                    "retrieved_at": "2026-09-25T12:00:00+00:00", "verification_status": "source_backed", "availability_verified": False,
                    "content": content, "content_format": "markdown", "excerpt": content, "excerpt_ref": f"{evidence_ref}#chars=0-{len(content)}",
                    "facts": facts, "passages": passages, "untrusted_source": True}
        event = public_resource_projection(evidence, event_id=ident(), data_origin="consented_demo", export_approved=True)
        self.assertNotIn("content", event)
        self.assertNotIn("excerpt", event)
        self.assertEqual(event["facts"]["email"]["value"], "support@example.org")
        self.assertIsNone(event["facts"]["availability"])
        self.assertIsNone(event["task_id"])
        exported = export_projection("clearline_resource_versions", event)
        self.assertEqual(exported["passages"], passages)
        self.assertFalse(exported["availability_verified"])
        event["facts"]["email"]["value"] = "invented@example.org"
        with self.assertRaises(IntegrationError):
            export_projection("clearline_resource_versions", event)

    async def test_actual_store_outbox_payloads_are_compatible(self):
        from backend.storage.store import Store
        store = Store(":memory:")
        self.addCleanup(store.close)
        state = store.create_session(ident(), "test-token-hash", {"recording": True, "cloud_export": True})
        evidence_ref = "b" * 64
        resource = {"source_ref": "a" * 64, "evidence_ref": evidence_ref, "version": 1,
                    "url": "https://example.org/support", "retrieved_at": "2026-09-25T12:00:00+00:00",
                    "content_hash": "c" * 64, "verification_status": "source_backed",
                    "facts": {"phone": None, "email": {"value": "support@example.org", "passage_ref": f"{evidence_ref}#chars=0-26"}, "hours": None, "address": None, "availability": None},
                    "request_id": None, "task_id": None}
        with store.transaction() as conn:
            store.save_summary(conn, state["session_id"], {"duration_s": 20, "word_count": 40, "recording_wpm": 120, "energy_rms": 0.2, "pause_count": None, "pitch_mean_hz": None, "quality": "accepted", "quality_reasons": []}, "clearline-v1")
            store.enqueue_export(conn, state, "resource_version", resource)
        sent = []
        def handle(request):
            sent.append((request.url.path, json.loads(request.content)))
            return httpx.Response(200, json={"inserted": 1})
        client = self.make_client(handle)
        for row in store.pending_outbox():
            await client.append_event(row["table_name"], row["payload"])
        self.assertEqual({path for path, _ in sent}, {"/v1/tables/clearline_events", "/v1/tables/clearline_checkpoints", "/v1/tables/clearline_session_summaries", "/v1/tables/clearline_resource_versions"})
        self.assertTrue(all("export_approved" not in payload for _, payload in sent))

    def test_free_text_is_not_accepted_as_metadata(self):
        event = {"event_id": ident(), "event_type": "secret-person-name", "data_origin": "synthetic"}
        with self.assertRaises(IntegrationError):
            export_projection("clearline_events", event)
        summary = self.summary(ident(), ident())
        summary["metrics"]["quality_reasons"] = ["secret-person-name"]
        with self.assertRaises(IntegrationError):
            export_projection("clearline_session_summaries", summary)

    async def test_baseline_deduplicates_latest_versions_before_eligibility_and_last_five(self):
        profile_id, current_session = ident(), ident()
        database = sqlite3.connect(":memory:")
        self.addCleanup(database.close)
        database.row_factory = sqlite3.Row
        database.execute("CREATE TABLE clearline_session_summaries (__raw_data TEXT, session_id TEXT, profile_ref TEXT, data_origin TEXT, recording_task TEXT, method_version TEXT, created_at TEXT, summary_version INTEGER, event_id TEXT)")
        rows = []
        selected_ids = []
        for index in range(1, 9):
            session_id = ident()
            original = self.summary(profile_id, session_id, index=index)
            rows.extend([original, original])  # duplicated outbox delivery
            if index == 8:
                rows.append(self.summary(profile_id, session_id, version=2, index=8, method_version="new-method"))
            else:
                selected_ids.append(original["summary_id"])
        rows.append(self.summary(profile_id, current_session, index=20))
        rows.append(self.summary(ident(), ident(), index=21))
        for row in rows:
            wire = export_projection("clearline_session_summaries", row)
            database.execute("INSERT INTO clearline_session_summaries VALUES (?,?,?,?,?,?,?,?,?)", (json.dumps(wire), wire["session_id"], wire["profile_ref"], wire["data_origin"], wire["recording_task"], wire["method_version"], wire["created_at"], wire["summary_version"], wire["event_id"]))
        captured = []
        def handle(request):
            sql = json.loads(request.content)["sql"]
            captured.append(sql)
            data = [dict(row) for row in database.execute(sql).fetchall()]
            return httpx.Response(200, json={"data": data})
        client = self.make_client(handle)
        result = await client.read_baseline(profile_id, current_session, data_origin="synthetic")
        self.assertEqual(result["session_count"], 5)
        self.assertEqual(result["summary_refs"], list(reversed(selected_ids[-5:])))
        self.assertEqual(result["status"], "available")
        self.assertEqual(result["baseline_source"], "synthetic_demo_reference")
        self.assertEqual(result["metrics"]["recording_wpm"]["mean"], 120)
        self.assertEqual(result["metrics"]["recording_wpm"]["std"], 0)
        self.assertIsNone(result["metrics"]["pitch_mean_hz"]["mean"])
        self.assertNotIn(profile_id, captured[0])

    async def test_no_history_is_not_normal_or_zero_measurements(self):
        client = self.make_client(lambda request: httpx.Response(200, json={"data": []}))
        result = await client.read_baseline(ident(), ident())
        self.assertEqual(result["status"], "insufficient_history")
        self.assertEqual(result["session_count"], 0)
        self.assertIsNone(result["metrics"]["recording_wpm"]["mean"])
        self.assertIsNone(result["baseline_ref"])

    async def test_queries_reject_sql_injection_before_network(self):
        def fail(request):
            self.fail("Invalid identifier reached HTTP")
        client = self.make_client(fail)
        for args in (("x' OR 1=1", ident(), {}), (ident(), ident(), {"measurement_version": "x'; DROP TABLE x"}), (ident(), ident(), {"data_origin": "all"})):
            with self.assertRaises(IntegrationError):
                await client.read_baseline(args[0], args[1], **args[2])
        with self.assertRaises(IntegrationError):
            await client.read_evidence_version("x' OR 1=1")

    async def test_response_error_classification_is_redacted(self):
        for status, retryable in ((401, False), (403, False), (429, True), (500, True), (302, False)):
            with self.subTest(status=status):
                client = self.make_client(lambda request, status=status: httpx.Response(status, json={"error": "test-secret-never-print"}))
                with self.assertRaises(IntegrationError) as raised:
                    await client.read_latest_checkpoint(ident())
                self.assertEqual(raised.exception.retryable, retryable)
                self.assertNotIn("test-secret-never-print", str(raised.exception))
        client = self.make_client(lambda request: httpx.Response(200, json={"rows": []}))
        with self.assertRaises(IntegrationError) as raised:
            await client.read_latest_checkpoint(ident())
        self.assertEqual(raised.exception.code, "rawtree_invalid_response")

    async def test_read_latest_checkpoint_and_exact_evidence_version(self):
        session_id, evidence_ref = ident(), "a" * 64
        def handle(request):
            sql = json.loads(request.content)["sql"]
            if "clearline_checkpoints" in sql:
                self.assertIn("ORDER BY state_version DESC", sql)
                return httpx.Response(200, json={"data": [{"payload": {"session_id": session_id, "state_version": 2}}]})
            self.assertIn("AND version = 3", sql)
            return httpx.Response(200, json={"data": [{"payload": {"evidence_ref": evidence_ref, "version": 3}}]})
        client = self.make_client(handle)
        self.assertEqual((await client.read_latest_checkpoint(session_id))["state_version"], 2)
        self.assertEqual((await client.read_evidence_version(evidence_ref, 3))["version"], 3)

    async def test_smoke_contract_writes_labeled_event_then_reads_same_id(self):
        inserted = []
        def handle(request):
            payload = json.loads(request.content)
            if request.url.path.startswith("/v1/tables/"):
                inserted.append(payload)
                return httpx.Response(200, json={"inserted": 1})
            self.assertIn(inserted[0]["event_id"], payload["sql"])
            return httpx.Response(200, json={"data": [{"event_id": inserted[0]["event_id"], "data_origin": "synthetic"}]})
        result = await self.make_client(handle).smoke_test()
        self.assertEqual(result["status"], "passed")
        self.assertEqual(inserted[0]["data_origin"], "synthetic")

    async def test_missing_configuration_fails_visibly(self):
        client = RawTreeClient(api_key="", database="")
        self.addAsyncCleanup(client.aclose)
        with self.assertRaises(IntegrationError) as raised:
            await client.smoke_test()
        self.assertEqual(raised.exception.code, "rawtree_not_configured")

    def test_legacy_and_insecure_base_urls_rejected(self):
        for url in ("https://api.tinybird.co", "http://api.rawtree.com", "https://user:secret@api.rawtree.com", "https://api.rawtree.com/v0"):
            with self.subTest(url=url), self.assertRaises(IntegrationError):
                RawTreeClient(api_key="test", database="test", base_url=url)


if __name__ == "__main__":
    unittest.main()
