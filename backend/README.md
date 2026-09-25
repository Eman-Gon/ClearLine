# ClearLine backend — Session 2

A single-process FastAPI application serves the mobile frontend and uses SQLite
for durable state, jobs, actions, checkpoints, session summaries, and an export
outbox. Voice processing runs on the paired laptop. Session 3 supplies Liquid,
RawTree, and Nimble adapters through `backend/worker/interfaces.py`.

## Start locally

Run from the repository root with Python 3.12:

```sh
python3 -m venv backend/.venv
backend/.venv/bin/pip install -r backend/requirements-dev.txt
export CLEARLINE_PAIRING_CODE='choose-a-private-demo-code'
backend/.venv/bin/python -m uvicorn backend.main:app --host 127.0.0.1 --port 3000 --workers 1
```

Open `http://localhost:3000/`. The frontend is served at `/`; its assets are
served at `/static`. Enter the same pairing code to create a live demo session.
Keep one worker: an OS advisory lock prevents two processes from recovering
one database concurrently. Uvicorn reload is unnecessary for the demo.

Use Chrome USB port forwarding to map the phone's localhost port 3000 to this
laptop's port 3000, then open `http://localhost:3000` on the S24. Alternatively
configure trusted HTTPS and add its exact origin to `CLEARLINE_ALLOWED_ORIGINS`.
Phone microphone capture has not been tested by Session 2.

## Real transcription setup

`ffmpeg` must be installed and on PATH (or set `FFMPEG_BINARY`). This machine's
real decoder tests used ffmpeg 7.1.1 on macOS arm64 with Python 3.12.2.

```sh
backend/.venv/bin/pip install -r backend/requirements-audio.txt
export WHISPER_MODEL_PATH='/absolute/path/to/local/faster-whisper-model'
```

The local CTranslate2 Whisper directory must include `model.bin`, `config.json`,
and `tokenizer.json`. The application does not download model weights. It loads
one cached CPU/int8 model; downloading a model is a separate setup operation.
Faster Whisper is not installed in the tested environment, so real transcription
has not passed. `requirements.txt` pins core versions actually exercised;
`requirements-audio.txt` deliberately leaves the untested transcription package
unfrozen until a real audio smoke test succeeds. NumPy computes PCM amplitude;
pauses and pitch remain null, so librosa is not needed by this implementation.

A missing audio dependency preserves the upload and reports `awaiting_input`
with a dependency error. Configure it, then Resume. Invalid, silent, empty, or
missing media asks for a replacement and produces no invented measurements.
Raw uploads and decoded temporary media are removed after terminal processing;
queued files survive a restart. Local transcripts, metrics, summaries, and
checkpoints remain in SQLite until the local database is removed. No claim of
secure forensic erasure is made.

## Configuration

- `CLEARLINE_DB`: defaults to `backend/data/clearline.sqlite3`.
- `CLEARLINE_UPLOAD_DIR`: defaults to `backend/data/uploads`.
- `CLEARLINE_PAIRING_CODE`: required for session creation; no default access code.
- `CLEARLINE_ALLOWED_ORIGINS`: comma-separated exact origins; defaults to
  `http://localhost:3000,http://127.0.0.1:3000`.
- `WHISPER_MODEL_PATH`, `FFMPEG_BINARY`: local audio setup.
- Sponsor settings and real smoke probes are documented in `agent/README.md`.

The default database/media directory is ignored by Git. Filled credentials must
stay local. `/api/health` reports configuration and known readiness without
network calls; `configured_unverified` does not mean a live sponsor test passed.

## API contract

Mutations require an allowed `Origin`. Create a session with:

```json
{
  "pairing_code": "your local pairing code",
  "consent": {"recording": true, "cloud_export": false},
  "data_origin": "consented_demo",
  "recording_task": "check_in"
}
```

An optional UUID `profile_id` reconnects a demo profile. The response contains
server-owned `session_id`, `profile_id`, and a `resume_token`. Send the token as
`Authorization: Bearer <resume_token>` on subsequent routes. Tokens are hashed
in SQLite. Live API recordings always use `consented_demo`; synthetic history
is created separately as an explicitly labeled fixture.

| Route | Behavior and body |
| --- | --- |
| `POST /api/sessions` | Paired, consented creation as above. |
| `POST /api/sessions/{id}/clips` | Multipart `audio`, stable UUID `clip_id`, optional `supersedes_clip_id`; 202 after durable receipt. |
| `POST /api/sessions/{id}/finish` | Complete capture; comparison waits for accepted uploads. |
| `POST /api/sessions/{id}/resources` | `{category, city, approved:true}`; explicit public search request. |
| `POST /api/sessions/{id}/input` | `{input_revision, clip_id, transcript}`, `{input_revision, city}`, or `{input_revision, answer}`; stale revision gives 409. |
| `POST /api/sessions/{id}/pause` | Pause at the next committed safe boundary. |
| `POST /api/sessions/{id}/resume` | Resume pending eligible work idempotently. |
| `GET /api/sessions/{id}` | Read stored status only; never schedules or executes work. |
| `GET /api/profiles/{id}/history` | Authorized latest complete summaries, one per session. |
| `GET /api/health` | Local readiness; no credentials returned. |

Categories are `caregiver_support`, `respite_care`, `caregiver_education`, with
matching natural-language aliases. Complete uploads are limited to 16 MiB;
decoded duration must be 2–60 seconds. These are engineering quality limits,
not health thresholds. Use 20–30 second phone recordings.

Status includes the shared metrics/comparison/resources/provenance/cloud fields,
plus `input_revision`, `consent`, `clips`, `events`, `pending_input`, and
`pause_requested`. Authorized clip status includes its local transcript for
correction. Cloud payloads never include transcripts. Baselines use the latest
version of up to five completed sessions matched by profile, provenance,
recording task, and measurement method. Fewer than two sessions is insufficient;
zero variance yields a null standardized difference.

## Persistence and Session 3 seam

SQLite schema version 1 has `sessions`, `clips`, `jobs`, `actions`, `checkpoints`,
`events`, `session_summaries`, and `outbox`. Every accepted transition, checkpoint,
and approved export is committed transactionally. Clip `(session_id, clip_id)`
and logical job/action keys are unique. A duplicate clip with different bytes is
rejected. Successful replacement supersedes its original only after acceptance.

On startup, claimed jobs are requeued, started actions become `unknown`, pending
pauses reach `paused`, and interrupted outbox delivery retries the same event ID.
The worker reuses the persisted action ID and committed successes. A public HTTP
request interrupted before commit can run again; external execution is not
exactly once. Recovery assumes the same laptop disk and database survive.

`create_app` accepts injected `audio_processor`, `agent_runner`, and `exporter`.
Production loads `backend.agent.create_agent_runner()` and `create_exporter()`
when present. The runner's async `advance(checkpoint)` proposes one
`{name, arguments, model_call?, context_usage?}`. The worker assigns a stable
`action_id`, persists it, and calls async `execute(action, checkpoint)` for
baseline/search/extract. Comparison, input requests, and completion checks remain
controller-owned. Missing adapters or local model produce `agent_unavailable`.

Scope-specific compact checkpoints preserve the last model call and result,
including errors, while full action results remain durable. Failed read requests
return to the model for its next choice within a bounded task budget. City
changes invalidate resource work; transcript changes version measurements and
invalidate comparison work. Existing accepted audio is retained.

The exporter implements async `append_event(table, payload)`. Outbox projections
have strict schemas and consent gates for events, checkpoints, summaries, and
source-backed resource versions. Their stable IDs permit downstream deduplication.
No raw audio, transcripts, names, secrets, or model messages enter exports.

## Verification and current limits

```sh
python3 -m pytest backend/tests -q --disable-warnings
python3 -m pytest backend/tests backend/smoke_tests -q --disable-warnings
```

The combined backend and sponsor-adapter suite passed 114 tests. A real Uvicorn
loopback HTTP smoke check also passed frontend serving, paired session creation,
and authorized status retrieval, then shut down its temporary server.

Session 2 tests use real SQLite and real ffmpeg decoding. Sponsor/audio test
doubles are explicitly labeled; they do not establish live transcription or
sponsor availability. The process-recovery test starts a real subprocess,
commits a synthetic search, SIGKILLs it during extraction, restarts, and verifies
that search executes once, extraction reuses its action ID, and the accepted
clip and summary remain intact. Polling, duplicate content conflicts, origin and
auth checks, pause/resume, corrections, no/zero-variance history, invalid audio,
privacy projections, and outbox replay also have automated coverage.

Session 3's real probes currently report no reachable Liquid server and missing
RawTree/Nimble credentials. Complete real Whisper, Liquid, sponsor, and S24 checks
before describing the full application as demo-ready.
