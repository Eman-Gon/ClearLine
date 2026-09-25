"""Offline recovery integration: real SQLite/worker/adapters, mocked sponsor HTTP.

This reconstructs the worker in the same process after a committed Search and a
claimed next job. It is not a live sponsor test or an actual process-kill demo.
The accepted clip and source page are explicitly synthetic fixtures.
"""
from __future__ import annotations

import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from uuid import uuid4

import httpx

from backend.agent.runner import LiquidAgentRunner
from backend.integrations.liquid import LiquidClient
from backend.integrations.nimble import NimbleClient, source_ref_for_url
from backend.storage.store import Store, utcnow
from backend.worker.runner import Worker


SOURCE_URL = "https://example.org/synthetic-caregiver-support"
SOURCE_REF = source_ref_for_url(SOURCE_URL)


class _UnusedAudio:
    def process(self, *args, **kwargs):
        raise AssertionError("Accepted fixture clip must not be processed during recovery")


class _UnusedRawTree:
    async def read_baseline(self, *args, **kwargs):
        raise AssertionError("Resource recovery must not call RawTree baseline retrieval")


class RecoveryTests(unittest.IsolatedAsyncioTestCase):
    async def test_committed_search_survives_worker_reconstruction_before_extract(self):
        with tempfile.TemporaryDirectory(prefix="clearline-mocked-recovery-") as directory:
            settings = SimpleNamespace(max_action_count=12, upload_dir=directory)
            database = str(Path(directory) / "recovery.sqlite3")
            store = Store(database)
            self.addCleanup(store.close)
            session = store.create_session(str(uuid4()), "synthetic-token-hash", {
                "recording": True, "cloud_export": False,
            }, data_origin="synthetic")
            sid, clip_id = session["session_id"], str(uuid4())
            with store.transaction() as conn:
                now = utcnow()
                conn.execute("""INSERT INTO clips
                    (session_id,clip_id,status,media_path,mime_type,created_at,updated_at)
                    VALUES (?,?, 'accepted', NULL, 'audio/webm', ?,?)""", (sid, clip_id, now, now))
                store.update_session(conn, sid, {
                    "phase": "researching", "resource_revision": 1, "input_revision": 1,
                    "resource_request": {"category": "caregiver support groups", "city": "Oakland", "approved": True},
                    "accepted_clip_refs": [clip_id], "capture_finished": True,
                })
                store.enqueue_job(conn, sid, "agent", "synthetic-resource-start", {
                    "scope": "resources", "revision": 1, "step": 0,
                })

            requests = {"search": 0, "extract": 0, "model": []}

            def model_response(name, arguments, call_id):
                return httpx.Response(200, json={
                    "model": "clearline-liquid",
                    "choices": [{"finish_reason": "tool_calls", "message": {
                        "role": "assistant", "content": None,
                        "tool_calls": [{"id": call_id, "type": "function", "function": {
                            "name": name, "arguments": json.dumps(arguments),
                        }}],
                    }}],
                    "usage": {"prompt_tokens": 1200, "completion_tokens": 50, "total_tokens": 1250},
                })

            def sponsor_http(request):
                body = json.loads(request.content) if request.content else {}
                path = request.url.path
                if path == "/v1/models":
                    return httpx.Response(200, json={"data": [{"id": "clearline-liquid", "owned_by": "llamacpp"}]})
                if path == "/props":
                    return httpx.Response(200, json={"build_info": "mocked-test-runtime", "default_generation_settings": {"n_ctx": 4096}})
                if path == "/apply-template":
                    return httpx.Response(200, json={"prompt": json.dumps(body)})
                if path == "/tokenize":
                    return httpx.Response(200, json={"tokens": list(range(1200))})
                if path == "/v1/chat/completions":
                    requests["model"].append(body)
                    if len(requests["model"]) == 1:
                        return model_response("search_public_resources", {
                            "category": "caregiver support groups", "city": "Oakland",
                        }, "mocked_search_call")
                    if len(requests["model"]) == 2:
                        return model_response("extract_public_page", {"source_ref": SOURCE_REF}, "mocked_extract_call")
                    raise AssertionError("Recovery test should need exactly two model calls")
                if path == "/v2/search":
                    requests["search"] += 1
                    self.assertEqual(body["query"], "caregiver support groups in Oakland")
                    return httpx.Response(200, json={"request_id": "mocked-search-request", "results": [{
                        "url": SOURCE_URL, "title": "Synthetic support fixture",
                        "description": "Synthetic public source for offline recovery testing.",
                    }]})
                if path == "/v2/extract":
                    requests["extract"] += 1
                    self.assertEqual(body["url"], SOURCE_URL)
                    return httpx.Response(200, json={
                        "status": "success", "status_code": 200, "task_id": "mocked-extract-task",
                        "data": {"markdown": "Synthetic source fixture.\nPhone: 510-555-0100\nHours: Monday 9am to noon"},
                    })
                raise AssertionError(f"Unexpected mocked HTTP endpoint: {path}")

            async def public_fixture_dns(host):
                self.assertEqual(host, "example.org")
                return ["93.184.216.34"]

            async def new_worker(persistence):
                http = httpx.AsyncClient(transport=httpx.MockTransport(sponsor_http))
                self.addAsyncCleanup(http.aclose)
                agent = LiquidAgentRunner(
                    LiquidClient(client=http), _UnusedRawTree(),
                    NimbleClient(api_key="mocked-credential", client=http, resolver=public_fixture_dns),
                )
                return Worker(persistence, _UnusedAudio(), agent, None, settings), http

            worker, first_http = await new_worker(store)
            self.assertTrue(await worker.run_once())
            committed = store.get_session(sid)
            self.assertEqual(committed["errors"], [])
            self.assertEqual(committed["phase"], "researching")
            self.assertEqual(committed["completed_action_watermark"], 1)
            self.assertEqual(committed["sources"][0]["source_ref"], SOURCE_REF)
            self.assertEqual(committed["last_model_call"]["tool_calls"][0]["id"], "mocked_search_call")
            self.assertEqual(committed["last_tool_result"]["sources"][0]["source_ref"], SOURCE_REF)
            search_action = committed["recent_completed_action_refs"][0]
            self.assertEqual(store.get_action(search_action)["status"], "succeeded")
            self.assertEqual(store.latest_checkpoint(sid)["completed_action_watermark"], 1)

            # Simulate exit after the next durable job was claimed, before its
            # action began. Discard both transport and worker, then reopen SQLite.
            interrupted_job = store.claim_job()
            self.assertEqual(interrupted_job["payload"]["step"], 1)
            await first_http.aclose()
            store.close()
            del worker
            restored_store = Store(database)
            self.addCleanup(restored_store.close)
            recovered = restored_store.recover()
            self.assertEqual(recovered["jobs_requeued"], 1)
            restored = restored_store.get_session(sid)
            self.assertEqual(restored["last_tool_result"], committed["last_tool_result"])
            self.assertEqual(restored["accepted_clip_refs"], [clip_id])
            resumed_worker, _ = await new_worker(restored_store)
            self.assertTrue(await resumed_worker.run_once())

            final = restored_store.get_session(sid)
            self.assertEqual(final["errors"], [], "Actual Store/Worker/adapters must commit the Extract result")
            self.assertEqual(final["completed_action_watermark"], 2)
            self.assertEqual(final["resources"][0]["verification_status"], "source_backed")
            self.assertIsNone(final["resources"][0]["facts"]["availability"])
            self.assertEqual(final["accepted_clip_refs"], [clip_id])
            self.assertEqual(requests["search"], 1)
            self.assertEqual(requests["extract"], 1)
            self.assertEqual(len(requests["model"]), 2)
            # The second Liquid turn receives the exact prior call ID paired
            # with its committed result; a search cannot be silently repeated.
            resumed_messages = requests["model"][1]["messages"]
            self.assertEqual(resumed_messages[-2]["tool_calls"][0]["id"], "mocked_search_call")
            self.assertEqual(resumed_messages[-1]["role"], "tool")
            self.assertEqual(resumed_messages[-1]["tool_call_id"], "mocked_search_call")
            self.assertEqual(json.loads(resumed_messages[-1]["content"])["sources"][0]["source_ref"], SOURCE_REF)
            self.assertIn(SOURCE_REF, resumed_messages[1]["content"])
            with restored_store.connection() as conn:
                actions = conn.execute("SELECT name,status,attempts FROM actions WHERE session_id=? ORDER BY rowid", (sid,)).fetchall()
                self.assertEqual([tuple(row) for row in actions], [
                    ("search_public_resources", "succeeded", 1),
                    ("extract_public_page", "succeeded", 1),
                ])
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM clips WHERE session_id=?", (sid,)).fetchone()[0], 1)
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM jobs WHERE kind='audio'", ()).fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
