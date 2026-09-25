"""End-to-end coverage for the autonomous scheduled-call pipeline: scheduler ->
Vapi dispatch -> webhook -> RawTree history -> Liquid analysis -> Nimble search
-> RawTree write -> family notification. Sponsor HTTP clients are replaced with
fakes; only the worker/router orchestration in backend/autonomous is exercised.
"""
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient

from backend.api.config import Settings
from backend.autonomous.routes import make_family_router
from backend.autonomous.worker import PhoneWorker
from backend.calls.routes import make_call_router
from backend.calls.vapi import VapiSettings
from backend.integrations.common import IntegrationError
from backend.storage import Store


class Provider:
    def __init__(self):
        self.count = 0

    async def preflight(self):
        pass

    async def start(self, number, request_id):
        self.count += 1
        return str(uuid4())


class FakeMemory:
    def __init__(self, sessions=None, session_count=0, fail=None):
        self.sessions = sessions or []
        self.session_count = session_count
        self.fail = fail
        self.saved = []

    async def aclose(self):
        pass

    async def history(self, profile_id, session_id, data_origin):
        if self.fail == "history":
            raise IntegrationError("rawtree_history_unavailable", "boom", True)
        return {
            "source": "RawTree", "table": "clearline_phone_sessions",
            "session_count": self.session_count, "returned_sessions": len(self.sessions),
            "history_limit": 10, "sessions": self.sessions, "data_origin": data_origin,
        }

    async def save(self, call, report):
        if self.fail == "save":
            raise IntegrationError("rawtree_write_failed", "boom", True)
        self.saved.append(call["request_id"])
        return {"source": "RawTree", "event_id": call["request_id"], "readback_verified": True}


class FakeAnalyst:
    def __init__(self, resources_helpful=True, fail=None):
        self.resources_helpful = resources_helpful
        self.fail = fail

    async def aclose(self):
        pass

    async def analyse(self, call, history):
        if self.fail == "analysis":
            raise IntegrationError("liquid_unsupported_evidence", "boom")
        return {
            "summary": "Participant described feeling isolated this week.", "changes": [],
            "limitations": "Limited to one prior session in this transcript-only flow.",
            "resources_helpful": self.resources_helpful, "resource_reason": "Stated loneliness.",
            "concern_quote": "I feel so alone", "search_terms": "loneliness support",
            "source": "Liquid", "model_identity": "test-model", "context": None,
            "input_scope": {"prior_sessions_considered": len(history["sessions"])},
            "acoustic_measurements_available": False,
        }

    async def search(self, analysis, city, approved):
        if self.fail == "search":
            raise IntegrationError("nimble_invalid_response", "boom")
        if not analysis["resources_helpful"]:
            return {"status": "not_needed", "reason": analysis["resource_reason"], "query": None, "results": []}
        if not approved:
            return {"status": "not_authorized", "reason": "Public resource search was not enabled.", "query": None, "results": []}
        return {
            "status": "completed", "source": "Nimble",
            "query": "older adult loneliness social connection support" + (" near " + city if city else ""),
            "reason": analysis["resource_reason"], "concern_quote": analysis["concern_quote"],
            "historical_evidence": [], "request_id": "nimble-req-1",
            "results": [{"url": "https://example.org/support", "title": "Community support group", "description": "A local group."}],
        }


class FakePush:
    def __init__(self, fail=False):
        self.sent = []
        self.fail = fail

    async def send(self, token, session_id):
        if self.fail:
            raise IntegrationError("push_send_failed", "boom")
        self.sent.append((token, session_id))
        return {"provider": "FCM", "status": "accepted_by_provider"}


HEADERS = {"Authorization": "Bearer test-pair", "Origin": "http://testserver"}
WEBHOOK_HEADERS = {"X-ClearLine-Vapi-Secret": "s" * 40}


def make_env(tmp_path, *, memory=None, analyst=None, push=None, clock=None):
    store = Store(str(tmp_path / "auto.db"))
    settings = Settings(pairing_code="test-pair", allowed_origins=("http://testserver",))
    app = FastAPI()
    provider = Provider()
    call_router = make_call_router(store, settings, VapiSettings(webhook_secret="s" * 40, consent_mode="preconsented_demo"), provider)
    app.include_router(call_router)
    app.include_router(make_family_router(store, settings))
    worker = PhoneWorker(store, call_router.dispatch_call, memory=memory or FakeMemory(),
                          analyst=analyst or FakeAnalyst(), push=push or FakePush(), clock=clock or (lambda: datetime.now(timezone.utc)))
    return TestClient(app), store, provider, worker


def webhook_report(provider_id, transcript="I feel so alone lately."):
    return {"message": {
        "type": "end-of-call-report", "call": {"id": provider_id},
        "compliance": {"recordingConsent": {"type": "verbal", "grantedAt": "2026-09-25T10:00:00Z"}},
        "artifact": {"messages": [{"role": "assistant", "message": "Hi, how are you?"}, {"role": "user", "message": transcript}]},
    }}


def schedule_body(schedule_id, profile_id, due, **overrides):
    body = dict(schedule_id=str(schedule_id), profile_id=str(profile_id), number="+15555550123",
                timezone="America/Los_Angeles", first_call_at=due.isoformat(), recurrence="once", enabled=True,
                permission_to_call=True, cloud_processing_approved=True, rawtree_storage_approved=True,
                resource_search_approved=True, participant_preconsented_demo=True, data_origin="consented_demo",
                city="San Francisco CA")
    body.update(overrides)
    return body


async def run_reports_to_completion(worker, ticks=6):
    for _ in range(ticks):
        await worker.report_tick()


def test_full_pipeline_labels_each_sponsor_and_notifies(tmp_path):
    now = datetime.now(timezone.utc)
    due = now + timedelta(minutes=2)
    schedule_id, profile_id = uuid4(), uuid4()
    clock_box = {"now": due + timedelta(seconds=1)}
    prior = [{"session_id": str(uuid4()), "created_at": "2026-09-01T10:00:00Z", "transcript": "I have been sleeping fine.", "data_origin": "consented_demo"}]
    memory = FakeMemory(sessions=prior, session_count=len(prior))
    push = FakePush()

    import asyncio

    async def scenario():
        client, store, provider, worker = make_env(tmp_path, memory=memory, push=push, clock=lambda: clock_box["now"])
        resp = client.put(f"/api/family/schedules/{schedule_id}", json=schedule_body(schedule_id, profile_id, due), headers=HEADERS)
        assert resp.status_code == 200

        device_id = uuid4()
        assert client.post("/api/family/devices", json={"device_id": str(device_id), "profile_id": str(profile_id), "token": "x" * 20}, headers=HEADERS).status_code == 200

        await worker.schedule_tick()
        assert provider.count == 1, "one call should have been placed for the due schedule"
        occurrences = client.get(f"/api/family/profiles/{profile_id}/reports", headers=HEADERS).json()["occurrences"]
        assert occurrences and occurrences[0]["state"] in ("queued", "dispatching")

        with store.connection() as conn:
            row = conn.execute("SELECT request_id, provider_id FROM telephone_calls WHERE profile_id=?", (str(profile_id),)).fetchone()
        request_id, provider_id = row["request_id"], row["provider_id"]

        webhook_resp = client.post("/api/vapi/webhook", json=webhook_report(provider_id), headers=WEBHOOK_HEADERS)
        assert webhook_resp.json()["state"] == "completed"

        await run_reports_to_completion(worker)
        await worker.notification_tick()

        reports = client.get(f"/api/family/profiles/{profile_id}/reports", headers=HEADERS).json()["reports"]
        report = next(r for r in reports if r["session_id"] == request_id)
        assert report["stage"] == "complete"

        rawtree = report["report"]["rawtree"]
        assert rawtree["source"] == "RawTree" and rawtree["session_count"] == 1 and rawtree["sessions"] == prior

        liquid = report["report"]["liquid"]
        assert liquid["source"] == "Liquid"
        assert liquid["summary"] == "Participant described feeling isolated this week."
        assert liquid["concern_quote"] == "I feel so alone"

        nimble = report["report"]["nimble"]
        assert nimble["source"] == "Nimble" and nimble["status"] == "completed"
        assert nimble["query"] == "older adult loneliness social connection support near San Francisco CA"
        assert nimble["results"][0]["url"] == "https://example.org/support"

        assert report["report"]["rawtree_write"]["readback_verified"] is True
        assert memory.saved == [request_id]
        assert push.sent and push.sent[0][1] == request_id
        assert report["notifications"][0]["state"] == "accepted_by_provider"

    asyncio.run(scenario())


def test_repeated_schedule_tick_does_not_place_a_second_call(tmp_path):
    import asyncio
    now = datetime.now(timezone.utc)
    due = now + timedelta(minutes=2)
    schedule_id, profile_id = uuid4(), uuid4()
    clock_box = {"now": due + timedelta(seconds=1)}

    async def scenario():
        client, store, provider, worker = make_env(tmp_path, clock=lambda: clock_box["now"])
        client.put(f"/api/family/schedules/{schedule_id}", json=schedule_body(schedule_id, profile_id, due, recurrence="once"), headers=HEADERS)
        await worker.schedule_tick()
        await worker.schedule_tick()
        await worker.schedule_tick()
        assert provider.count == 1, "a 'once' schedule must never dispatch twice, even across repeated ticks"

    asyncio.run(scenario())


def test_duplicate_webhook_replay_does_not_rerun_the_pipeline(tmp_path):
    import asyncio
    now = datetime.now(timezone.utc)
    due = now + timedelta(minutes=2)
    schedule_id, profile_id = uuid4(), uuid4()
    clock_box = {"now": due + timedelta(seconds=1)}
    memory = FakeMemory()

    async def scenario():
        client, store, provider, worker = make_env(tmp_path, memory=memory, clock=lambda: clock_box["now"])
        client.put(f"/api/family/schedules/{schedule_id}", json=schedule_body(schedule_id, profile_id, due), headers=HEADERS)
        await worker.schedule_tick()
        with store.connection() as conn:
            provider_id = conn.execute("SELECT provider_id FROM telephone_calls WHERE profile_id=?", (str(profile_id),)).fetchone()["provider_id"]
        payload = webhook_report(provider_id)
        assert client.post("/api/vapi/webhook", json=payload, headers=WEBHOOK_HEADERS).json()["state"] == "completed"
        await run_reports_to_completion(worker)
        assert memory.saved  # first delivery produced exactly one RawTree write
        saved_after_first = list(memory.saved)

        replay = client.post("/api/vapi/webhook", json=payload, headers=WEBHOOK_HEADERS)
        assert replay.json() == {"duplicate": True, "state": "completed"}
        await run_reports_to_completion(worker)
        assert memory.saved == saved_after_first, "a replayed webhook must not create a second RawTree write"

    asyncio.run(scenario())


def test_missing_rawtree_consent_fails_before_any_sponsor_call(tmp_path):
    import asyncio

    async def scenario():
        client, store, provider, worker = make_env(tmp_path)
        request_id, profile_id = uuid4(), uuid4()
        body = {"request_id": str(request_id), "profile_id": str(profile_id), "number": "+15555550123",
                "permission_to_call": True, "cloud_processing_approved": True, "participant_preconsented_demo": True,
                "data_origin": "consented_demo"}  # rawtree_storage_approved defaults to False
        assert client.post("/api/calls", json=body, headers=HEADERS).status_code == 202
        with store.connection() as conn:
            provider_id = conn.execute("SELECT provider_id FROM telephone_calls WHERE request_id=?", (str(request_id),)).fetchone()["provider_id"]
        client.post("/api/vapi/webhook", json=webhook_report(provider_id), headers=WEBHOOK_HEADERS)

        await worker.report_tick()

        reports = client.get(f"/api/family/profiles/{profile_id}/reports", headers=HEADERS).json()["reports"]
        report = next(r for r in reports if r["session_id"] == str(request_id))
        assert report["stage"] == "failed"
        assert report["error_code"] == "rawtree_consent_required"
        assert "rawtree" not in report["report"] and "liquid" not in report["report"]

    asyncio.run(scenario())


def test_failed_stage_can_be_retried_without_placing_another_call(tmp_path):
    import asyncio
    now = datetime.now(timezone.utc)
    due = now + timedelta(minutes=2)
    schedule_id, profile_id = uuid4(), uuid4()
    clock_box = {"now": due + timedelta(seconds=1)}
    analyst = FakeAnalyst(fail="search")

    async def scenario():
        client, store, provider, worker = make_env(tmp_path, analyst=analyst, clock=lambda: clock_box["now"])
        client.put(f"/api/family/schedules/{schedule_id}", json=schedule_body(schedule_id, profile_id, due), headers=HEADERS)
        await worker.schedule_tick()
        assert provider.count == 1
        with store.connection() as conn:
            provider_id = conn.execute("SELECT provider_id FROM telephone_calls WHERE profile_id=?", (str(profile_id),)).fetchone()["provider_id"]
        client.post("/api/vapi/webhook", json=webhook_report(provider_id), headers=WEBHOOK_HEADERS)

        for _ in range(4):
            await worker.report_tick()

        reports = client.get(f"/api/family/profiles/{profile_id}/reports", headers=HEADERS).json()["reports"]
        report = next(r for r in reports)
        session_id = report["session_id"]
        assert report["stage"] == "failed" and report["report"]["resume_stage"] == "search"
        assert report["report"]["error_code"] == "nimble_invalid_response"

        retry = client.post(f"/api/family/reports/{session_id}/retry", headers=HEADERS)
        assert retry.status_code == 200 and retry.json()["stage"] == "search"

        analyst.fail = None  # the underlying issue is now resolved; reprocess without redialing
        for _ in range(3):
            await worker.report_tick()
        assert provider.count == 1, "retry must never place a second phone call"

        reports = client.get(f"/api/family/profiles/{profile_id}/reports", headers=HEADERS).json()["reports"]
        report = next(r for r in reports if r["session_id"] == session_id)
        assert report["stage"] == "complete"
        assert report["report"]["nimble"]["status"] == "completed"

    asyncio.run(scenario())
