"""SQLite owns coordination. Remote event delivery is deliberately at least once.

Every mutator accepting ``conn`` participates in its caller's transaction. No
network, model, or audio work runs in this module. JSON state is local only;
exports have separate, small schemas and cannot serialize arbitrary state.
"""

from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import math
from pathlib import Path
import re
import sqlite3
import statistics
from typing import Any, Iterator
from urllib.parse import urlsplit
import uuid


PHASES = frozenset({"recording", "processing", "awaiting_input", "comparing",
                    "awaiting_user_choice", "researching", "waiting_retry",
                    "ready", "paused", "agent_unavailable"})
METRICS = ("duration_s", "word_count", "recording_wpm", "pause_count",
           "energy_rms", "pitch_mean_hz")
ORIGINS = frozenset({"synthetic", "consented_demo"})
TOOLS = frozenset({"get_baseline_summary", "compare_recording_metrics",
                   "search_public_resources", "extract_public_page",
                   "request_user_input", "finish_task"})
QUALITY_REASONS = frozenset({"too_short", "too_long", "silent", "no_speech",
                             "empty_transcript", "invalid_audio", "decode_failed",
                             "missing_audio", "unsupported_media", "empty_audio"})


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _json(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"), sort_keys=True, allow_nan=False)


class ConflictError(ValueError):
    """An input revision or an immutable identity did not match."""


SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    profile_id TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    state_version INTEGER NOT NULL,
    input_revision INTEGER NOT NULL,
    phase TEXT NOT NULL,
    data_origin TEXT NOT NULL,
    recording_task TEXT NOT NULL,
    method_version TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    completed_at TEXT
);
CREATE INDEX IF NOT EXISTS sessions_profile ON sessions(profile_id,created_at);
CREATE TABLE IF NOT EXISTS clips (
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    clip_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued',
    media_path TEXT,
    mime_type TEXT,
    sha256 TEXT,
    byte_size INTEGER,
    input_revision INTEGER NOT NULL DEFAULT 0,
    metrics_json TEXT,
    transcript TEXT,
    method_version TEXT,
    superseded_by TEXT,
    supersedes_clip_id TEXT,
    error_json TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY(session_id,clip_id)
);
CREATE TABLE IF NOT EXISTS jobs (
    job_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    kind TEXT NOT NULL,
    logical_key TEXT NOT NULL UNIQUE,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued',
    attempts INTEGER NOT NULL DEFAULT 0,
    retry_at TEXT,
    error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS jobs_pending ON jobs(status,retry_at,created_at);
CREATE TABLE IF NOT EXISTS actions (
    action_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    logical_key TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    arguments_json TEXT NOT NULL,
    input_revision INTEGER NOT NULL,
    state_version INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'planned',
    result_json TEXT,
    result_ref TEXT,
    error TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS actions_session ON actions(session_id,status);
CREATE TABLE IF NOT EXISTS checkpoints (
    checkpoint_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    state_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(session_id,state_version)
);
CREATE TABLE IF NOT EXISTS events (
    event_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    state_version INTEGER NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS events_session ON events(session_id,state_version);
CREATE TABLE IF NOT EXISTS session_summaries (
    summary_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    profile_id TEXT NOT NULL,
    summary_version INTEGER NOT NULL,
    input_revision INTEGER NOT NULL,
    data_origin TEXT NOT NULL,
    recording_task TEXT NOT NULL,
    method_version TEXT NOT NULL,
    metrics_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(session_id,summary_version)
);
CREATE INDEX IF NOT EXISTS summaries_baseline ON session_summaries(profile_id,data_origin,recording_task,method_version,created_at);
CREATE TABLE IF NOT EXISTS outbox (
    event_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id),
    kind TEXT NOT NULL,
    table_name TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    attempts INTEGER NOT NULL DEFAULT 0,
    retry_at TEXT,
    error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS outbox_pending ON outbox(status,retry_at,created_at);
PRAGMA user_version=1;
"""


class Store:
    def __init__(self, db_path: str | Path):
        self.db_path = str(db_path)
        self._keeper: sqlite3.Connection | None = None
        self._uri = self.db_path == ":memory:"
        if self._uri:
            self.db_path = f"file:clearline-{uuid.uuid4()}?mode=memory&cache=shared"
            self._keeper = self._connect()
        else:
            Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(SCHEMA)

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, timeout=10, isolation_level=None,
                               uri=self._uri)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=10000")
        return conn

    def connect(self) -> sqlite3.Connection:
        """Open a connection for caller-owned read-only inspection; close after use."""
        return self._connect()

    @contextmanager
    def connection(self) -> Iterator[sqlite3.Connection]:
        conn = self._connect()
        try:
            yield conn
        finally:
            conn.close()

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        with self.connection() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                yield conn
                conn.commit()
            except BaseException:
                conn.rollback()
                raise

    def close(self) -> None:
        if self._keeper:
            self._keeper.close()
            self._keeper = None

    @staticmethod
    def _session(conn: sqlite3.Connection, session_id: str) -> dict:
        row = conn.execute("SELECT * FROM sessions WHERE session_id=?", (session_id,)).fetchone()
        if row is None:
            raise KeyError(session_id)
        state = json.loads(row["state_json"])
        for key in ("session_id", "profile_id", "token_hash", "state_version",
                    "input_revision", "phase", "data_origin", "recording_task",
                    "method_version", "created_at", "updated_at", "completed_at"):
            state[key] = row[key]
        return state

    def get_session(self, session_id: str, conn: sqlite3.Connection | None = None) -> dict:
        if conn is not None:
            return self._session(conn, session_id)
        with self.connection() as own:
            return self._session(own, session_id)

    def create_session(self, profile_id: str, token_hash: str, consent: dict,
                       data_origin: str = "consented_demo",
                       recording_task: str = "check_in") -> dict:
        if data_origin not in ORIGINS:
            raise ValueError("Unknown data origin")
        if not isinstance(consent, dict) or consent.get("recording") is not True:
            raise ValueError("Explicit recording consent is required")
        now = utcnow()
        state = {
            "session_id": str(uuid.uuid4()), "profile_id": profile_id,
            "token_hash": token_hash, "state_version": 1, "input_revision": 0,
            "phase": "recording", "consent": consent, "data_origin": data_origin,
            "recording_task": recording_task, "method_version": "clearline-v1",
            "execution_mode": "local_pipeline", "metrics": None, "comparison": None,
            "resources": [], "pending_action": None, "errors": [],
            "accepted_clip_refs": [], "unresolved_requirements": [],
            "recent_completed_action_refs": [], "evidence_refs": [],
            "baseline_ref": None, "current_measurements_ref": None,
            "finish_requested": False, "capture_finished": False, "pause_requested": False,
            "comparison_revision": 0, "resource_revision": 0,
            "cloud_sync": {"status": "pending" if self.export_permitted({"consent": consent, "data_origin": data_origin}) else "restricted"},
            "provenance": {"data_origin": data_origin, "baseline_source": None},
            "created_at": now, "updated_at": now, "completed_at": None,
        }
        with self.transaction() as conn:
            conn.execute("""INSERT INTO sessions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                         (state["session_id"], profile_id, token_hash, 1, 0,
                          "recording", data_origin, recording_task, state["method_version"],
                          _json(state), now, now, None))
            self._record_transition(conn, state, "session_created")
        return state

    def update_session(self, conn: sqlite3.Connection, session_id: str,
                       patch: dict, expected_revision: int | None = None) -> dict:
        state = self._session(conn, session_id)
        if expected_revision is not None and state["input_revision"] != expected_revision:
            raise ConflictError("Input revision is stale")
        for key in ("session_id", "profile_id", "token_hash", "created_at", "data_origin", "recording_task"):
            if key in patch and patch[key] != state[key]:
                raise ConflictError(f"{key} is immutable")
        if "state_version" in patch:
            raise ConflictError("state_version is server-owned")
        state.update(patch)
        if state["phase"] not in PHASES:
            raise ValueError("Unknown workflow phase")
        if not isinstance(state["input_revision"], int) or state["input_revision"] < 0:
            raise ValueError("Invalid input revision")
        state["state_version"] += 1
        state["updated_at"] = utcnow()
        conn.execute("""UPDATE sessions SET state_version=?,input_revision=?,phase=?,
                        method_version=?,state_json=?,updated_at=?,completed_at=? WHERE session_id=?""",
                     (state["state_version"], state["input_revision"], state["phase"],
                      state["method_version"], _json(state), state["updated_at"],
                      state.get("completed_at"), session_id))
        self._record_transition(conn, state, "state_changed")
        return state

    def _record_transition(self, conn: sqlite3.Connection, state: dict, kind: str) -> None:
        checkpoint = self.checkpoint_projection(state)
        checkpoint_id, event_id = str(uuid.uuid4()), str(uuid.uuid4())
        conn.execute("INSERT INTO checkpoints VALUES (?,?,?,?,?)",
                     (checkpoint_id, state["session_id"], state["state_version"],
                      _json(checkpoint), state["updated_at"]))
        event = {"event_type": kind, "phase": state["phase"],
                 "input_revision": state["input_revision"]}
        conn.execute("INSERT INTO events VALUES (?,?,?,?,?,?,?,?)",
                     (event_id, state["session_id"], state["state_version"], kind, kind, state["phase"],
                      _json(event), state["updated_at"]))
        self.enqueue_export(conn, state, "event", event, event_id)
        # Pending arguments, goals, user questions and consent stay local.
        public_checkpoint = {key: checkpoint[key] for key in (
            "schema_version", "input_revision", "phase", "baseline_ref",
            "accepted_clip_refs", "current_measurements_ref",
            "recent_completed_action_refs", "completed_action_watermark", "evidence_refs")}
        self.enqueue_export(conn, state, "checkpoint", public_checkpoint, checkpoint_id)

    @staticmethod
    def checkpoint_projection(state: dict) -> dict:
        return {
            "schema_version": 1, "session_id": state["session_id"],
            "state_version": state["state_version"], "input_revision": state["input_revision"],
            "phase": state["phase"], "goal": state.get("goal"),
            "baseline_ref": state.get("baseline_ref"),
            "accepted_clip_refs": state.get("accepted_clip_refs", []),
            "current_measurements_ref": state.get("current_measurements_ref"),
            "unresolved_requirements": state.get("unresolved_requirements", []),
            "pending_action": state.get("pending_action"),
            "recent_completed_action_refs": state.get("recent_completed_action_refs", [])[-8:],
            "completed_action_watermark": state.get("completed_action_watermark", 0),
            "evidence_refs": state.get("evidence_refs", []),
            "execution_mode": state.get("execution_mode", "local_pipeline"),
            "method_version": state.get("method_version"),
            "last_model_call": state.get("last_model_call"),
            "last_tool_result": state.get("last_tool_result"),
        }

    def latest_checkpoint(self, session_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute("SELECT payload_json FROM checkpoints WHERE session_id=? ORDER BY state_version DESC LIMIT 1", (session_id,)).fetchone()
            return json.loads(row["payload_json"]) if row else None

    @staticmethod
    def _job(row: sqlite3.Row) -> dict:
        result = dict(row)
        result["payload"] = json.loads(result.pop("payload_json"))
        return result

    def enqueue_job(self, conn: sqlite3.Connection, session_id: str, kind: str,
                    key: str, payload: dict | None = None) -> dict:
        logical_key = f"{session_id}:{kind}:{key}"
        now = utcnow()
        conn.execute("""INSERT OR IGNORE INTO jobs
            (job_id,session_id,kind,logical_key,payload_json,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?)""",
                     (str(uuid.uuid4()), session_id, kind, logical_key,
                      _json(payload or {}), now, now))
        row = conn.execute("SELECT * FROM jobs WHERE logical_key=?", (logical_key,)).fetchone()
        return self._job(row)

    def claim_job(self) -> dict | None:
        with self.transaction() as conn:
            row = conn.execute("""SELECT jobs.* FROM jobs JOIN sessions USING(session_id)
                WHERE jobs.status IN ('queued','retry') AND sessions.phase!='paused'
                AND COALESCE(json_extract(sessions.state_json,'$.pause_requested'),0)=0
                AND (jobs.retry_at IS NULL OR jobs.retry_at<=?)
                AND (jobs.kind='audio' OR NOT EXISTS (
                    SELECT 1 FROM jobs audio_jobs WHERE audio_jobs.session_id=jobs.session_id
                    AND audio_jobs.kind='audio' AND audio_jobs.status IN ('queued','retry','running')))
                AND (jobs.kind='audio' OR NOT EXISTS (
                    SELECT 1 FROM clips WHERE clips.session_id=jobs.session_id
                    AND clips.status IN ('queued','processing') AND clips.superseded_by IS NULL))
                ORDER BY jobs.created_at,jobs.rowid LIMIT 1""", (utcnow(),)).fetchone()
            if row is None:
                return None
            conn.execute("UPDATE jobs SET status='running',attempts=attempts+1,updated_at=? WHERE job_id=?", (utcnow(), row["job_id"]))
            return self._job(conn.execute("SELECT * FROM jobs WHERE job_id=?", (row["job_id"],)).fetchone())

    def complete_job(self, conn: sqlite3.Connection, job_id: str) -> None:
        conn.execute("UPDATE jobs SET status='completed',retry_at=NULL,error=NULL,updated_at=? WHERE job_id=?", (utcnow(), job_id))

    def retry_job(self, conn: sqlite3.Connection, job_id: str, error: str,
                  retry_at: str | None = None) -> None:
        conn.execute("UPDATE jobs SET status='retry',retry_at=?,error=?,updated_at=? WHERE job_id=? AND status!='completed'", (retry_at, error, utcnow(), job_id))

    def fail_job(self, conn: sqlite3.Connection, job_id: str, error: str) -> None:
        conn.execute("UPDATE jobs SET status='failed',error=?,updated_at=? WHERE job_id=? AND status!='completed'", (error, utcnow(), job_id))

    def recover(self) -> dict:
        with self.transaction() as conn:
            jobs = conn.execute("UPDATE jobs SET status='queued',retry_at=NULL,updated_at=? WHERE status='running'", (utcnow(),)).rowcount
            actions = conn.execute("UPDATE actions SET status='unknown',updated_at=? WHERE status='started'", (utcnow(),)).rowcount
            outbox = conn.execute("UPDATE outbox SET status='pending',updated_at=? WHERE status='sending'", (utcnow(),)).rowcount
            pending_pauses = conn.execute("SELECT session_id FROM sessions WHERE phase!='paused' AND json_extract(state_json,'$.pause_requested')=1").fetchall()
            for row in pending_pauses:
                state = self._session(conn, row["session_id"])
                self.update_session(conn, row["session_id"], {
                    "phase": "paused", "resume_phase": state["phase"], "pause_requested": False,
                })
        return {"jobs_requeued": jobs, "actions_unknown": actions,
                "outbox_requeued": outbox, "pauses_completed": len(pending_pauses)}

    def plan_action(self, conn: sqlite3.Connection, session_id: str, name: str,
                    arguments: dict, key: str, input_revision: int | None = None) -> dict:
        state = self._session(conn, session_id)
        revision = state["input_revision"] if input_revision is None else input_revision
        logical_key = f"{session_id}:{revision}:{key}"
        now = utcnow()
        conn.execute("""INSERT OR IGNORE INTO actions
            (action_id,session_id,logical_key,name,arguments_json,input_revision,state_version,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?)""", (str(uuid.uuid4()), session_id, logical_key,
                  name, _json(arguments), revision, state["state_version"], now, now))
        action = self._action(conn.execute("SELECT * FROM actions WHERE logical_key=?", (logical_key,)).fetchone())
        if action["name"] != name or action["arguments"] != arguments:
            raise ConflictError("Action key already belongs to different arguments")
        return action

    @staticmethod
    def _action(row: sqlite3.Row) -> dict:
        result = dict(row)
        result["arguments"] = json.loads(result.pop("arguments_json"))
        raw = result.pop("result_json")
        result["result"] = json.loads(raw) if raw else None
        return result

    def get_action(self, action_id: str, conn: sqlite3.Connection | None = None) -> dict:
        if conn is None:
            with self.connection() as own:
                return self.get_action(action_id, own)
        row = conn.execute("SELECT * FROM actions WHERE action_id=?", (action_id,)).fetchone()
        if row is None:
            raise KeyError(action_id)
        return self._action(row)

    def start_action(self, conn: sqlite3.Connection, action_id: str) -> dict:
        conn.execute("""UPDATE actions SET status='started',attempts=attempts+1,updated_at=?
                        WHERE action_id=? AND status IN ('planned','failed','unknown')""", (utcnow(), action_id))
        return self.get_action(action_id, conn)

    def finish_action(self, conn: sqlite3.Connection, action_id: str, result: Any,
                      result_ref: str | None = None) -> dict:
        action = self.get_action(action_id, conn)
        if action["status"] == "succeeded":
            return action
        if action["status"] != "started":
            raise ConflictError("Only a started action can commit a result")
        conn.execute("UPDATE actions SET status='succeeded',result_json=?,result_ref=?,error=NULL,updated_at=? WHERE action_id=?", (_json(result), result_ref or action_id, utcnow(), action_id))
        return self.get_action(action_id, conn)

    def fail_action(self, conn: sqlite3.Connection, action_id: str, error: str,
                    unknown: bool = False) -> dict:
        conn.execute("UPDATE actions SET status=?,error=?,updated_at=? WHERE action_id=? AND status!='succeeded'", ("unknown" if unknown else "failed", error, utcnow(), action_id))
        return self.get_action(action_id, conn)

    def save_summary(self, conn: sqlite3.Connection, session_id: str, metrics: dict,
                     method_version: str) -> dict:
        """Commit a version of a completed capture; caller excludes unusable clips."""
        state = self._session(conn, session_id)
        metrics = dict(metrics)
        metrics["data_origin"] = state["data_origin"]
        self._validate_metrics(metrics)
        row = conn.execute("SELECT MAX(summary_version) AS version FROM session_summaries WHERE session_id=?", (session_id,)).fetchone()
        version = (row["version"] or 0) + 1
        summary_id = str(uuid.uuid4())
        now = utcnow()
        conn.execute("INSERT INTO session_summaries VALUES (?,?,?,?,?,?,?,?,?,?)",
                     (summary_id, session_id, state["profile_id"], version,
                      state["input_revision"], state["data_origin"], state["recording_task"],
                      method_version, _json(metrics), now))
        summary = {"summary_id": summary_id, "summary_ref": summary_id,
                   "summary_version": version, "session_id": session_id,
                   "profile_id": state["profile_id"], "input_revision": state["input_revision"],
                   "data_origin": state["data_origin"], "recording_task": state["recording_task"],
                   "method_version": method_version, "metrics": metrics, "created_at": now}
        self.enqueue_export(conn, state, "session_summary", {
            "summary_id": summary_id, "summary_version": version,
            "input_revision": state["input_revision"], "method_version": method_version,
            "recording_task": state["recording_task"], "metrics": metrics,
        }, summary_id)
        return summary

    @staticmethod
    def _summary(row: sqlite3.Row) -> dict:
        result = dict(row)
        result["metrics"] = json.loads(result.pop("metrics_json"))
        result["summary_ref"] = result["summary_id"]
        return result

    def history(self, profile_id: str) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute("""SELECT s.* FROM session_summaries s JOIN sessions session ON session.session_id=s.session_id
                WHERE s.profile_id=? AND session.completed_at IS NOT NULL
                AND summary_version=(SELECT MAX(n.summary_version) FROM session_summaries n WHERE n.session_id=s.session_id)
                ORDER BY session.completed_at DESC,s.rowid DESC LIMIT 100""", (profile_id,)).fetchall()
            return [self._summary(row) for row in rows]

    def baseline(self, session_id: str, min_sessions: int = 2) -> dict:
        state = self.get_session(session_id)
        with self.connection() as conn:
            rows = conn.execute("""SELECT s.* FROM session_summaries s JOIN sessions session ON session.session_id=s.session_id
                WHERE s.profile_id=? AND session.completed_at IS NOT NULL
                AND s.session_id!=? AND s.data_origin=? AND s.recording_task=? AND s.method_version=?
                AND summary_version=(SELECT MAX(n.summary_version) FROM session_summaries n WHERE n.session_id=s.session_id)
                ORDER BY session.completed_at DESC,s.rowid DESC LIMIT 5""", (
                    state["profile_id"], session_id, state["data_origin"],
                    state["recording_task"], state["method_version"])).fetchall()
        summaries = [self._summary(row) for row in rows]
        current = state.get("metrics") or {}
        comparisons = {}
        for name in METRICS:
            values = [s["metrics"][name] for s in summaries if isinstance(s["metrics"].get(name), (int, float)) and not isinstance(s["metrics"].get(name), bool)]
            value = current.get(name)
            mean = statistics.mean(values) if values else None
            delta = value - mean if value is not None and mean is not None else None
            deviation = statistics.stdev(values) if len(values) >= 2 else None
            comparisons[name] = {
                "current": value, "mean": mean, "delta": delta, "std": deviation,
                "standardized_delta": delta / deviation if delta is not None and deviation and len(values) >= min_sessions else None,
                "session_count": len(values),
            }
        refs = [s["summary_id"] for s in summaries]
        return {
            "status": "available" if len(summaries) >= min_sessions else "insufficient_history",
            "session_count": len(summaries), "minimum_sessions": min_sessions,
            "source": "synthetic_demo_reference" if state["data_origin"] == "synthetic" else "consented_demo_history",
            "baseline_source": "synthetic_demo_reference" if state["data_origin"] == "synthetic" else "consented_demo_history",
            "data_origin": state["data_origin"], "recording_task": state["recording_task"],
            "method_version": state["method_version"], "summary_refs": refs, "sessions": refs,
            "baseline_ref": str(uuid.uuid5(uuid.NAMESPACE_URL, _json(refs))) if refs else None,
            "metrics": comparisons, "missing_metrics": [key for key, item in comparisons.items() if item["mean"] is None],
            "interpretation": "No health interpretation is provided.",
        }

    @staticmethod
    def export_permitted(session: dict) -> bool:
        consent = session.get("consent") or {}
        return session.get("data_origin") == "synthetic" or consent.get("cloud_export") is True or consent.get("cloud_measurement_export") is True

    @staticmethod
    def _validate_metrics(metrics: dict) -> None:
        allowed = set(METRICS) | {"quality", "quality_reasons", "data_origin"}
        if set(metrics) - allowed:
            raise ValueError("Metric export contains fields outside the allowlist")
        for key in METRICS:
            value = metrics.get(key)
            if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)):
                raise ValueError(f"Invalid numeric metric: {key}")
        if metrics.get("quality") not in (None, "accepted", "rejected", "unusable"):
            raise ValueError("Invalid quality value")
        reasons = metrics.get("quality_reasons", [])
        if not isinstance(reasons, list) or any(reason not in QUALITY_REASONS for reason in reasons):
            raise ValueError("Quality reasons must be allowlisted codes")
        if metrics.get("data_origin") not in (None, *ORIGINS):
            raise ValueError("Invalid metric provenance")

    def enqueue_export(self, conn: sqlite3.Connection, session: dict | str, kind: str,
                       payload: dict, event_id: str | None = None) -> bool:
        if isinstance(session, str):
            session = self._session(conn, session)
        table_aliases = {"clearline_events": "event", "clearline_session_summaries": "session_summary", "clearline_checkpoints": "checkpoint", "clearline_resource_versions": "resource_version"}
        kind = table_aliases.get(kind, kind)
        fields = {
            "event": {"event_type", "phase", "input_revision", "action_id", "action_name", "action_status", "summary_ref"},
            "session_summary": {"summary_id", "summary_version", "input_revision", "method_version", "recording_task", "metrics"},
            "checkpoint": {"schema_version", "input_revision", "phase", "baseline_ref", "accepted_clip_refs", "current_measurements_ref", "recent_completed_action_refs", "completed_action_watermark", "evidence_refs"},
            "resource_version": {"source_ref", "evidence_ref", "version", "url", "retrieved_at", "content_hash", "verification_status", "facts", "request_id", "task_id"},
        }
        if kind not in fields or set(payload) - fields[kind]:
            raise ValueError("Export contains fields outside the allowlist")
        if "metrics" in payload:
            if not isinstance(payload["metrics"], dict):
                raise ValueError("Invalid metrics")
            self._validate_metrics(payload["metrics"])
        if kind == "resource_version":
            self._validate_resource(payload)
        # All text is an enum or opaque machine reference. Arbitrary questions,
        # tool messages, city strings and person names have no export field.
        enum_fields = {"phase": PHASES, "action_name": TOOLS,
                       "event_type": {"session_created", "state_changed", "action_planned", "action_started", "action_succeeded", "action_failed", "action_unknown", "summary_saved"},
                       "action_status": {"planned", "started", "succeeded", "failed", "unknown"}}
        for name, choices in enum_fields.items():
            if name in payload and payload[name] not in choices:
                raise ValueError(f"Invalid export enum: {name}")
        for name in ("schema_version", "input_revision", "summary_version", "completed_action_watermark"):
            if name in payload and (isinstance(payload[name], bool) or not isinstance(payload[name], int) or payload[name] < 0):
                raise ValueError(f"Invalid export integer: {name}")
        for name in ("method_version", "recording_task"):
            if name in payload and (not isinstance(payload[name], str) or len(payload[name]) > 100 or not all(c.isalnum() or c in "_.:-" for c in payload[name])):
                raise ValueError(f"Invalid export identifier: {name}")
        for name in ("action_id", "summary_id", "summary_ref", "baseline_ref", "current_measurements_ref"):
            if payload.get(name) is not None:
                self._validate_reference(payload[name])
        for name in ("accepted_clip_refs", "recent_completed_action_refs", "evidence_refs"):
            if name in payload:
                if not isinstance(payload[name], list):
                    raise ValueError(f"Invalid export reference list: {name}")
                for value in payload[name]:
                    self._validate_reference(value)
        if not self.export_permitted(session):
            return False
        event_id = event_id or str(uuid.uuid4())
        self._validate_reference(event_id)
        now = utcnow()
        outbound = {
            "event_id": event_id, "session_id": session["session_id"],
            # A local profile label can contain a person's name; export only a
            # stable pseudonymous digest, never the user-supplied label itself.
            "profile_ref": hashlib.sha256(session["profile_id"].encode()).hexdigest(),
            "state_version": session["state_version"], "data_origin": session["data_origin"],
            "created_at": now, "export_approved": True, **payload,
        }
        table_name = {"event": "clearline_events", "session_summary": "clearline_session_summaries", "checkpoint": "clearline_checkpoints", "resource_version": "clearline_resource_versions"}[kind]
        conn.execute("""INSERT OR IGNORE INTO outbox
            (event_id,session_id,kind,table_name,payload_json,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?)""", (event_id, session["session_id"], kind,
                  table_name, _json(outbound), now, now))
        return True

    @classmethod
    def _validate_resource(cls, payload: dict) -> None:
        required = {"source_ref", "evidence_ref", "version", "url", "retrieved_at", "content_hash", "verification_status", "facts"}
        if not required.issubset(payload):
            raise ValueError("Resource export requires source-backed versioned facts")
        for field in ("source_ref", "evidence_ref", "content_hash"):
            cls._validate_reference(payload[field])
        if not re.fullmatch(r"[0-9a-f]{64}", payload["content_hash"]):
            raise ValueError("Resource content hash must be SHA256")
        if type(payload["version"]) is not int or not 1 <= payload["version"] <= 2**53 - 1:
            raise ValueError("Invalid resource version")
        if payload["verification_status"] != "source_backed":
            raise ValueError("Only source-backed public facts may be exported")
        url = payload["url"]
        if not isinstance(url, str) or len(url) > 2048:
            raise ValueError("Invalid public URL")
        try:
            parsed = urlsplit(url)
            host = (parsed.hostname or "").lower().rstrip(".")
            port = parsed.port
        except ValueError as exc:
            raise ValueError("Invalid public URL") from exc
        if (parsed.scheme not in {"http", "https"} or not host or parsed.username is not None
                or parsed.password is not None or parsed.fragment or port not in (None, 80, 443)
                or host == "localhost" or host.endswith((".localhost", ".local", ".internal"))):
            raise ValueError("Resource URL must be a public HTTP(S) source")
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            if "." not in host or not re.fullmatch(r"[a-z0-9.-]+", host):
                raise ValueError("Invalid public hostname") from None
        else:
            if not address.is_global:
                raise ValueError("Private resource URLs may not be exported")
        retrieved = payload["retrieved_at"]
        if not isinstance(retrieved, str) or len(retrieved) > 40:
            raise ValueError("Invalid resource retrieval timestamp")
        try:
            timestamp = datetime.fromisoformat(retrieved.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValueError("Invalid resource retrieval timestamp") from exc
        if timestamp.tzinfo is None:
            raise ValueError("Resource retrieval timestamp needs a timezone")
        facts = payload["facts"]
        if not isinstance(facts, dict) or set(facts) - {"phone", "email", "address", "hours", "availability"}:
            raise ValueError("Public facts contain fields outside the allowlist")
        for fact in facts.values():
            if fact is None:
                continue
            if not isinstance(fact, dict) or set(fact) != {"value", "passage_ref"}:
                raise ValueError("Public facts require a value and supporting passage reference")
            if not isinstance(fact["value"], str) or not 1 <= len(fact["value"]) <= 1000:
                raise ValueError("Public fact value is invalid")
            match = re.fullmatch(re.escape(payload["evidence_ref"]) + r"#chars=(\d+)-(\d+)", str(fact["passage_ref"]))
            if not match or not 0 <= int(match[1]) < int(match[2]) <= 2**31:
                raise ValueError("Public fact passage does not match its evidence version")
        for field in ("request_id", "task_id"):
            if payload.get(field) is not None and (not isinstance(payload[field], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:/-]{0,199}", payload[field])):
                raise ValueError("Invalid public request identifier")

    @staticmethod
    def _validate_reference(value: Any) -> None:
        if not isinstance(value, str):
            raise ValueError("Export references must be UUIDs or content hashes")
        if re.fullmatch(r"source_[0-9a-f]{24}(?::[0-9a-f]{64})?", value):
            return
        try:
            uuid.UUID(value)
            return
        except ValueError:
            pass
        if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
            raise ValueError("Export references must be UUIDs or content hashes")

    @staticmethod
    def _outbox(row: sqlite3.Row) -> dict:
        result = dict(row)
        result["payload"] = json.loads(result.pop("payload_json"))
        return result

    def pending_outbox(self, limit: int = 50) -> list[dict]:
        with self.connection() as conn:
            return [self._outbox(row) for row in conn.execute("""SELECT * FROM outbox
                WHERE status IN ('pending','retry') AND (retry_at IS NULL OR retry_at<=?)
                ORDER BY created_at,rowid LIMIT ?""", (utcnow(), min(max(limit, 1), 100))).fetchall()]

    def claim_outbox(self) -> dict | None:
        with self.transaction() as conn:
            row = conn.execute("""SELECT * FROM outbox WHERE status IN ('pending','retry')
                AND (retry_at IS NULL OR retry_at<=?) ORDER BY created_at,rowid LIMIT 1""", (utcnow(),)).fetchone()
            if row is None:
                return None
            conn.execute("UPDATE outbox SET status='sending',attempts=attempts+1,updated_at=? WHERE event_id=?", (utcnow(), row["event_id"]))
            return self._outbox(conn.execute("SELECT * FROM outbox WHERE event_id=?", (row["event_id"],)).fetchone())

    def ack_outbox(self, conn: sqlite3.Connection, event_id: str) -> None:
        conn.execute("UPDATE outbox SET status='delivered',retry_at=NULL,error=NULL,updated_at=? WHERE event_id=?", (utcnow(), event_id))

    def retry_outbox(self, conn: sqlite3.Connection, event_id: str, error: str,
                     retry_at: str | None = None) -> None:
        conn.execute("UPDATE outbox SET status='retry',error=?,retry_at=?,updated_at=? WHERE event_id=? AND status!='delivered'", (error, retry_at, utcnow(), event_id))
