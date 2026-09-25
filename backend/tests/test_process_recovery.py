"""Real process-death coverage with explicitly synthetic sponsor/audio doubles."""

import json
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import textwrap
from types import SimpleNamespace
from uuid import uuid4

import pytest

from backend.storage import Store, utcnow
from backend.worker.runner import Worker


REPOSITORY = Path(__file__).resolve().parents[2]

# Each subprocess imports the real storage and worker. The only simulated
# boundaries are model-selected actions, sponsor results, and already accepted
# audio. There is no network request, Whisper inference, or sponsor smoke test.
CHILD = textwrap.dedent(r'''
    import asyncio
    import json
    import os
    from pathlib import Path
    import sys
    from types import SimpleNamespace
    from uuid import uuid4
    from backend.storage import Store, utcnow
    from backend.worker.runner import Worker

    mode, database, invocation_log = sys.argv[1:4]
    store = Store(database)
    source_ref, evidence_ref = '1' * 64, '2' * 64
    metrics = {
        'duration_s': 10.0, 'word_count': 20, 'recording_wpm': 120.0,
        'pause_count': None, 'energy_rms': 0.1, 'pitch_mean_hz': None,
        'quality': 'accepted', 'quality_reasons': [], 'data_origin': 'synthetic',
    }

    def record_call(action):
        with open(invocation_log, 'a', encoding='utf-8') as stream:
            stream.write(json.dumps({'name': action['name'], 'action_id': action['action_id'], 'process': mode}) + '\n')
            stream.flush()
            os.fsync(stream.fileno())

    class SyntheticAudio:
        def process(self, *args, **kwargs):
            raise AssertionError('Committed audio must never be processed again')

    class SyntheticAgent:
        async def advance(self, checkpoint):
            if not checkpoint['sources']:
                return {'name': 'search_public_resources', 'arguments': {'category': 'caregiver support groups', 'city': 'Test City'}}
            if not checkpoint['evidence_refs']:
                return {'name': 'extract_public_page', 'arguments': {'source_ref': source_ref}}
            return {'name': 'finish_task', 'arguments': {'summary': 'Synthetic test only', 'evidence_refs': checkpoint['evidence_refs']}}

        async def execute(self, action, checkpoint):
            record_call(action)
            if action['name'] == 'search_public_resources':
                return {'sources': [{'source_ref': source_ref, 'url': 'https://example.org/synthetic', 'title': 'Synthetic test result'}]}
            assert action['name'] == 'extract_public_page'
            if mode == 'before_crash':
                session = store.get_session(session_id)
                checkpoint = store.latest_checkpoint(session_id)
                with store.connection() as conn:
                    committed = dict(conn.execute("SELECT * FROM actions WHERE name='search_public_resources'").fetchone())
                assert committed['status'] == 'succeeded'
                assert checkpoint['completed_action_watermark'] == 1
                # The search has committed and the next read-only action is
                # durably started. The parent kills us before extract returns.
                print(json.dumps({'marker': 'extract_started_after_committed_search', 'session_id': session_id, 'clip_id': clip_id, 'extract_action_id': action['action_id'], 'search_action_id': committed['action_id'], 'summary_ref': session['current_measurements_ref']}), flush=True)
                await asyncio.Future()
            return {
                'source_ref': source_ref, 'url': 'https://example.org/synthetic',
                'retrieved_at': '2026-09-25T00:00:00Z', 'content_hash': '3' * 64,
                'evidence_ref': evidence_ref, 'version': 1, 'verification_status': 'source_backed',
                'facts': {}, 'test_fixture': True,
            }

    if mode == 'before_crash':
        session = store.create_session(str(uuid4()), 'synthetic-test-token-hash', {'recording': True, 'cloud_export': False}, data_origin='synthetic')
        session_id, clip_id = session['session_id'], str(uuid4())
        with store.transaction() as conn:
            now = utcnow()
            conn.execute("INSERT INTO clips(session_id,clip_id,status,metrics_json,transcript,method_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", (session_id, clip_id, 'accepted', json.dumps(metrics), 'Synthetic transcript fixture', 'clearline-v1', now, now))
            summary = store.save_summary(conn, session_id, metrics, 'clearline-v1')
            store.update_session(conn, session_id, {
                'phase': 'researching', 'metrics': metrics, 'capture_finished': True,
                'accepted_clip_refs': [clip_id], 'current_measurements_ref': summary['summary_ref'],
                'resource_revision': 1,
                'resource_request': {'category': 'caregiver support groups', 'city': 'Test City', 'approved': True},
            })
            store.enqueue_job(conn, session_id, 'agent', 'synthetic-recovery-start', {'scope': 'resources', 'revision': 1, 'step': 0})
    else:
        session_id = sys.argv[4]

    worker = Worker(store, SyntheticAudio(), SyntheticAgent(), None, SimpleNamespace(max_action_count=12, upload_dir=str(Path(database).parent / 'uploads')))

    async def main():
        if mode == 'before_crash':
            assert await worker.run_once()  # Commit synthetic search and checkpoint.
            await worker.run_once()  # Block in extract until parent SIGKILL.
            raise AssertionError('The first subprocess must be killed at its marker')
        recovered = store.recover()
        for _ in range(5):
            if not await worker.run_once():
                break
        session = store.get_session(session_id)
        with store.connection() as conn:
            actions = [dict(row) for row in conn.execute('SELECT action_id,name,status,attempts FROM actions ORDER BY created_at,rowid')]
            clips = [dict(row) for row in conn.execute('SELECT clip_id,status,media_path FROM clips')]
            jobs = [dict(row) for row in conn.execute('SELECT status FROM jobs')]
            summaries = conn.execute('SELECT COUNT(*) FROM session_summaries').fetchone()[0]
        print(json.dumps({'recovered': recovered, 'session': session, 'actions': actions, 'clips': clips, 'jobs': jobs, 'summary_count': summaries}), flush=True)

    asyncio.run(main())
''')


@pytest.mark.skipif(not hasattr(signal, "SIGKILL"), reason="Process-death test requires SIGKILL")
def test_sigkill_restart_reuses_committed_search_and_recovers_next_action(tmp_path):
    database = tmp_path / "restart.sqlite3"
    invocation_log = tmp_path / "synthetic-invocations.jsonl"
    command = [sys.executable, "-u", "-c", CHILD]
    child = subprocess.Popen(
        [*command, "before_crash", str(database), str(invocation_log)],
        cwd=REPOSITORY, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ)
            assert selector.select(timeout=15), "Child failed to reach the committed-search marker"
        line = child.stdout.readline()
        if not line:
            _, error = child.communicate(timeout=5)
            pytest.fail(f"Child exited before its recovery marker: {error}")
        marker = json.loads(line)
        assert marker["marker"] == "extract_started_after_committed_search"
        assert child.poll() is None
        child.kill()
        child.communicate(timeout=5)
        assert child.returncode == -signal.SIGKILL
    finally:
        if child.poll() is None:
            child.kill()
            child.communicate(timeout=5)

    restarted = subprocess.run(
        [*command, "after_crash", str(database), str(invocation_log), marker["session_id"]],
        cwd=REPOSITORY, capture_output=True, text=True, timeout=15, check=False,
    )
    assert restarted.returncode == 0, restarted.stderr
    result = json.loads(restarted.stdout)
    assert result["recovered"]["jobs_requeued"] == 1
    assert result["recovered"]["actions_unknown"] == 1
    assert result["session"]["phase"] == "ready"
    assert result["session"]["completed_action_watermark"] == 3
    assert result["session"]["accepted_clip_refs"] == [marker["clip_id"]]
    assert result["session"]["current_measurements_ref"] == marker["summary_ref"]
    assert result["clips"] == [{"clip_id": marker["clip_id"], "status": "accepted", "media_path": None}]
    assert result["summary_count"] == 1
    assert all(job["status"] == "completed" for job in result["jobs"])

    actions = result["actions"]
    assert len(actions) == 3
    assert all(action["status"] == "succeeded" for action in actions)
    search = next(action for action in actions if action["name"] == "search_public_resources")
    extraction = next(action for action in actions if action["name"] == "extract_public_page")
    assert search["action_id"] == marker["search_action_id"]
    assert search["attempts"] == 1
    assert extraction["action_id"] == marker["extract_action_id"]
    assert extraction["attempts"] == 2

    calls = [json.loads(line) for line in invocation_log.read_text().splitlines()]
    assert [call["name"] for call in calls] == [
        "search_public_resources", "extract_public_page", "extract_public_page",
    ]
    # An interrupted read-only request can run twice. A committed successful
    # result is reused, and the unfinished request keeps its stable action ID.
    assert calls[0]["action_id"] == marker["search_action_id"]
    assert calls[1]["action_id"] == calls[2]["action_id"] == marker["extract_action_id"]
    assert calls[2]["process"] == "after_crash"


def test_relative_upload_directory_cleanup_preserves_queued_absolute_path(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    uploads = Path("uploads")
    uploads.mkdir()
    queued = uploads / "clip-queued.media"
    orphan = uploads / "clip-orphan.media"
    queued.write_bytes(b"synthetic pending input")
    orphan.write_bytes(b"synthetic orphan input")
    store = Store(tmp_path / "cleanup.sqlite3")
    session = store.create_session(str(uuid4()), "synthetic-token-hash", {"recording": True, "cloud_export": False}, data_origin="synthetic")
    with store.transaction() as conn:
        now = utcnow()
        conn.execute(
            "INSERT INTO clips(session_id,clip_id,status,media_path,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            (session["session_id"], str(uuid4()), "queued", str(queued.resolve()), now, now),
        )
    worker = Worker(store, None, None, None, SimpleNamespace(upload_dir="uploads"))
    worker.cleanup_media()
    assert queued.read_bytes() == b"synthetic pending input"
    assert not orphan.exists()
