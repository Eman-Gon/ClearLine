"""Durability, version selection and export privacy checks using real SQLite."""

import json
from pathlib import Path
import tempfile
import unittest
import uuid

from backend.storage import ConflictError, Store, utcnow


def metrics(wpm=120, origin="consented_demo"):
    return {"duration_s": 20.0, "word_count": 40, "recording_wpm": wpm,
            "pause_count": None, "energy_rms": 0.1, "pitch_mean_hz": None,
            "quality": "accepted", "quality_reasons": [], "data_origin": origin}


class StorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "state.sqlite3"
        self.store = Store(self.path)

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def session(self, profile="demo-person", origin="consented_demo", cloud=False, task="check_in"):
        return self.store.create_session(profile, "private-token-hash",
                                         {"recording": True, "cloud_export": cloud}, origin, task)

    def completed(self, wpm=120, profile="demo-person", origin="consented_demo", task="check_in", method="clearline-v1"):
        session = self.session(profile, origin, task=task)
        with self.store.transaction() as conn:
            summary = self.store.save_summary(conn, session["session_id"], metrics(wpm, origin), method)
            self.store.update_session(conn, session["session_id"], {
                "phase": "ready", "metrics": metrics(wpm, origin), "method_version": method,
                "current_measurements_ref": summary["summary_id"], "completed_at": utcnow(),
            })
        return session, summary

    def test_reading_does_not_mutate_state_or_events(self):
        session = self.session()
        with self.store.connection() as conn:
            before = conn.execute("SELECT COUNT(*) FROM events").fetchone()[0]
        for _ in range(20):
            self.assertEqual(self.store.get_session(session["session_id"])["state_version"], 1)
            self.store.latest_checkpoint(session["session_id"])
        with self.store.connection() as conn:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM events").fetchone()[0], before)

    def test_transition_and_job_roll_back_as_a_unit(self):
        session = self.session(cloud=True)
        with self.assertRaisesRegex(RuntimeError, "simulated"):
            with self.store.transaction() as conn:
                self.store.update_session(conn, session["session_id"], {"phase": "processing"})
                self.store.enqueue_job(conn, session["session_id"], "audio", "clip-1", {})
                raise RuntimeError("simulated interruption")
        self.assertEqual(self.store.get_session(session["session_id"])["phase"], "recording")
        with self.store.connection() as conn:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 0)
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM checkpoints").fetchone()[0], 1)
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM outbox").fetchone()[0], 2)

    def test_revision_check_is_atomic(self):
        session = self.session()
        with self.store.transaction() as conn:
            self.store.update_session(conn, session["session_id"], {"input_revision": 1}, expected_revision=0)
        with self.assertRaises(ConflictError):
            with self.store.transaction() as conn:
                self.store.update_session(conn, session["session_id"], {"phase": "processing"}, expected_revision=0)
        self.assertEqual(self.store.get_session(session["session_id"])["state_version"], 2)

    def test_jobs_are_idempotent_and_wait_for_audio(self):
        session = self.session()
        sid = session["session_id"]
        with self.store.transaction() as conn:
            summary = self.store.enqueue_job(conn, sid, "summarize", "capture", {})
            first = self.store.enqueue_job(conn, sid, "audio", "clip-1", {"clip_id": "clip-1"})
            second = self.store.enqueue_job(conn, sid, "audio", "clip-1", {"clip_id": "clip-1"})
        self.assertEqual(first["job_id"], second["job_id"])
        self.assertEqual(self.store.claim_job()["job_id"], first["job_id"])
        self.assertIsNone(self.store.claim_job())
        with self.store.transaction() as conn:
            self.store.complete_job(conn, first["job_id"])
        self.assertEqual(self.store.claim_job()["job_id"], summary["job_id"])

    def test_pause_survives_reopen_and_blocks_claims(self):
        session = self.session()
        with self.store.transaction() as conn:
            self.store.enqueue_job(conn, session["session_id"], "audio", "one", {})
            self.store.update_session(conn, session["session_id"], {"phase": "paused", "pause_requested": True})
        reopened = Store(self.path)
        reopened.recover()
        self.assertIsNone(reopened.claim_job())
        with reopened.transaction() as conn:
            reopened.update_session(conn, session["session_id"], {"phase": "processing", "pause_requested": False})
        self.assertIsNotNone(reopened.claim_job())
        reopened.close()

    def test_crash_completes_a_pending_pause_at_the_recovered_boundary(self):
        session = self.session()
        with self.store.transaction() as conn:
            self.store.enqueue_job(conn, session["session_id"], "audio", "one", {})
            self.store.update_session(conn, session["session_id"], {"phase": "processing"})
        self.store.claim_job()
        with self.store.transaction() as conn:
            self.store.update_session(conn, session["session_id"], {"pause_requested": True})
        reopened = Store(self.path)
        self.assertEqual(reopened.recover()["pauses_completed"], 1)
        self.assertEqual(reopened.get_session(session["session_id"])["phase"], "paused")
        self.assertIsNone(reopened.claim_job())
        reopened.close()

    def test_failed_audio_with_pending_clip_blocks_dependent_jobs(self):
        session = self.session()
        sid, cid = session["session_id"], str(uuid.uuid4())
        with self.store.transaction() as conn:
            conn.execute("INSERT INTO clips(session_id,clip_id,status,created_at,updated_at) VALUES(?,?,?,?,?)", (sid,cid,"queued",utcnow(),utcnow()))
            self.store.enqueue_job(conn, sid, "summarize", "capture", {})
            audio = self.store.enqueue_job(conn, sid, "audio", cid, {"clip_id": cid})
            self.store.fail_job(conn, audio["job_id"], "dependency unavailable")
        self.assertIsNone(self.store.claim_job())

    def test_restart_reuses_successes_and_marks_interrupted_actions_unknown(self):
        session = self.session()
        with self.store.transaction() as conn:
            job = self.store.enqueue_job(conn, session["session_id"], "agent", "step-1", {})
            done = self.store.plan_action(conn, session["session_id"], "get_baseline_summary", {}, "baseline")
            self.store.start_action(conn, done["action_id"])
            self.store.finish_action(conn, done["action_id"], {"status": "insufficient_history"})
            interrupted = self.store.plan_action(conn, session["session_id"], "search_public_resources", {"city": "Demo city"}, "search")
            self.store.start_action(conn, interrupted["action_id"])
        self.store.claim_job()
        reopened = Store(self.path)
        counts = reopened.recover()
        self.assertEqual(counts["jobs_requeued"], 1)
        self.assertEqual(counts["actions_unknown"], 1)
        self.assertEqual(reopened.claim_job()["job_id"], job["job_id"])
        self.assertEqual(reopened.get_action(done["action_id"])["result"], {"status": "insufficient_history"})
        self.assertEqual(reopened.get_action(interrupted["action_id"])["status"], "unknown")
        with reopened.transaction() as conn:
            same = reopened.plan_action(conn, session["session_id"], "get_baseline_summary", {}, "baseline")
            self.assertEqual(same["action_id"], done["action_id"])
            reopened.start_action(conn, done["action_id"])
        self.assertEqual(reopened.get_action(done["action_id"])["attempts"], 1)
        reopened.close()

    def test_no_history_is_explicit_with_nulls(self):
        session = self.session()
        baseline = self.store.baseline(session["session_id"])
        self.assertEqual(baseline["status"], "insufficient_history")
        self.assertEqual(baseline["session_count"], 0)
        self.assertIsNone(baseline["metrics"]["recording_wpm"]["mean"])
        self.assertIsNone(baseline["metrics"]["recording_wpm"]["standardized_delta"])

    def test_zero_variance_does_not_create_a_standardized_delta(self):
        self.completed()
        self.completed()
        current = self.session()
        with self.store.transaction() as conn:
            self.store.update_session(conn, current["session_id"], {"metrics": metrics(80)})
        comparison = self.store.baseline(current["session_id"])["metrics"]["recording_wpm"]
        self.assertEqual(comparison["mean"], 120)
        self.assertEqual(comparison["delta"], -40)
        self.assertEqual(comparison["std"], 0)
        self.assertIsNone(comparison["standardized_delta"])

    def test_latest_versions_only_provenance_method_task_and_profile_match(self):
        old, _ = self.completed(99)
        with self.store.transaction() as conn:
            self.store.save_summary(conn, old["session_id"], metrics(120), "clearline-v1")
        self.completed(120)
        self.completed(5, origin="synthetic")
        self.completed(5, profile="different-person")
        self.completed(5, task="different_task")
        self.completed(5, method="other-method")
        current, _ = self.completed(999)
        baseline = self.store.baseline(current["session_id"])
        self.assertEqual(baseline["session_count"], 2)
        self.assertEqual(baseline["metrics"]["recording_wpm"]["mean"], 120)
        self.assertEqual(len([s for s in self.store.history("demo-person") if s["session_id"] == old["session_id"]]), 1)

    def test_incomplete_summary_is_not_eligible_and_history_is_capped_to_five(self):
        incomplete = self.session()
        with self.store.transaction() as conn:
            self.store.save_summary(conn, incomplete["session_id"], metrics(999), "clearline-v1")
        for _ in range(6):
            self.completed(120)
        current = self.session()
        baseline = self.store.baseline(current["session_id"])
        self.assertEqual(baseline["session_count"], 5)
        self.assertEqual(baseline["metrics"]["recording_wpm"]["mean"], 120)
        self.assertNotIn(incomplete["session_id"], [s["session_id"] for s in self.store.history("demo-person")])

    def test_outbox_retries_preserve_event_id_and_payload(self):
        self.session(cloud=True)
        row = self.store.claim_outbox()
        with self.store.transaction() as conn:
            self.store.retry_outbox(conn, row["event_id"], "timeout")
        retried = self.store.claim_outbox()
        self.assertEqual(retried["event_id"], row["event_id"])
        self.assertEqual(retried["payload"], row["payload"])
        with self.store.transaction() as conn:
            self.store.ack_outbox(conn, row["event_id"])
        self.assertNotIn(row["event_id"], [r["event_id"] for r in self.store.pending_outbox()])

    def test_export_gate_and_allowlist_block_private_fields(self):
        local = self.session(profile="Private Person Name")
        self.assertEqual(self.store.pending_outbox(), [])
        with self.store.transaction() as conn:
            self.assertFalse(self.store.enqueue_export(conn, local, "event", {"event_type": "state_changed"}))
        approved = self.session(profile="Private Person Name", cloud=True)
        for payload in ({"event_type": "state_changed", "transcript": "private"},
                        {"event_type": "Private Person Name"},
                        {"action_id": "Private Person Name"}):
            with self.assertRaises(ValueError):
                with self.store.transaction() as conn:
                    self.store.enqueue_export(conn, approved, "event", payload)
        with self.assertRaises(ValueError):
            with self.store.transaction() as conn:
                self.store.enqueue_export(conn, approved, "session_summary", {"metrics": {"transcript": "private"}})
        encoded = json.dumps(self.store.pending_outbox())
        self.assertNotIn("Private Person Name", encoded)
        self.assertNotIn("private-token-hash", encoded)
        self.assertNotIn("transcript", encoded)

    def test_duplicate_export_id_does_not_add_second_row(self):
        session = self.session(cloud=True)
        event_id = str(uuid.uuid4())
        with self.store.transaction() as conn:
            for _ in range(2):
                self.store.enqueue_export(conn, session, "event", {"event_type": "state_changed"}, event_id)
        with self.store.connection() as conn:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM outbox WHERE event_id=?", (event_id,)).fetchone()[0], 1)

    def test_synthetic_export_is_labeled(self):
        self.session(origin="synthetic")
        self.assertTrue(self.store.pending_outbox())
        self.assertTrue(all(r["payload"]["data_origin"] == "synthetic" for r in self.store.pending_outbox()))

    def resource(self):
        evidence = "a" * 64
        return {"source_ref": "b" * 64, "evidence_ref": evidence, "version": 1,
                "url": "https://example.org/contact", "retrieved_at": utcnow(),
                "content_hash": "c" * 64, "verification_status": "source_backed",
                "facts": {"phone": {"value": "555-0100", "passage_ref": f"{evidence}#chars=0-10"}, "hours": None},
                "request_id": None, "task_id": "task-123"}

    def test_resource_export_is_allowlisted_and_consent_gated(self):
        local, approved = self.session(), self.session(cloud=True)
        with self.store.transaction() as conn:
            self.assertFalse(self.store.enqueue_export(conn, local, "resource_version", self.resource()))
            self.assertTrue(self.store.enqueue_export(conn, approved, "resource_version", self.resource()))
        resources = [r for r in self.store.pending_outbox() if r["kind"] == "resource_version"]
        self.assertEqual(len(resources), 1)
        self.assertEqual(resources[0]["table_name"], "clearline_resource_versions")
        self.assertIsNone(resources[0]["payload"]["facts"]["hours"])

    def test_resource_export_rejects_private_fields_and_unbacked_facts(self):
        session = self.session(cloud=True)
        invalid = [
            {**self.resource(), "transcript": "secret"},
            {**self.resource(), "facts": {"person_name": "Secret Name"}},
            {**self.resource(), "facts": {"phone": {"value": "555", "passage_ref": "not_evidence"}}},
            {**self.resource(), "url": "http://127.0.0.1/"},
            {**self.resource(), "url": "https://user:password@example.org/"},
            {**self.resource(), "url": "http://localhost/"},
            {**self.resource(), "request_id": "Person Name"},
        ]
        for projection in invalid:
            with self.subTest(projection=projection):
                with self.assertRaises(ValueError):
                    with self.store.transaction() as conn:
                        self.store.enqueue_export(conn, session, "resource_version", projection)


if __name__ == "__main__":
    unittest.main()
