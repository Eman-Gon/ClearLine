# ClearLine — Corrected Build Specification

**Revision:** 2.1 · September 25, 2026  
**Status:** Documentation-checked implementation specification, not a completed application.  
**Target:** Phone-operated, laptop-local hackathon prototype using Liquid AI + RawTree + Nimble.  
**Supersedes:** Both supplied ClearLine drafts. Do not append this to their contradictory instructions.

## 1. Overview and explicit scope changes

ClearLine maintains consent-based voice check-ins and unfinished, user-chosen caregiver follow-ups across interruptions. A person records a short clip on a Samsung Galaxy S24. A paired laptop transcribes and measures the recording locally. Liquid AI selects validated tools to retrieve historical summaries, compare descriptive measurements, request missing input, and research public resources the user explicitly requested. RawTree holds approved demo events, session summaries, source versions, and checkpoint history. Nimble searches and extracts current public pages. A durable worker can recover after a backend process restart without treating completed work as new work.

This preserves the original phone input, Python/FastAPI backend, longitudinal comparisons, three sponsors, and parallel-build structure. The following changes are deliberate, not claims that the original draft already did them:

- Remove claims of cognitive-decline detection, dementia screening, emotional flattening, a clinical alert threshold, and a “90-day early warning system.” The selected speech-emotion model's card describes emotion classification, not validation for those uses. [S12]
- Remove the emotion classifier and emotion-confidence score from the MVP. Remove readability/vocabulary scores from the MVP. Keep descriptive recording measurements only.
- Public-resource research begins with an explicit user request. A statistical difference must not automatically imply a clinical referral.
- Remove all Black Forest Labs references. This build uses exactly the three selected sponsors.
- Replace fragile independently decoded timeslice blobs with short, complete recordings. Streaming acoustic analysis is a later enhancement.
- Describe the deployment as **phone-operated, with inference on a paired laptop**. This is not an Android-native model deployment.
- Replace in-memory-only workflow state with durable checkpoints and a recoverable worker.

**Proposed pitch:** “A family check-in should not disappear when an app closes. ClearLine keeps a concise history of recorded check-ins and unfinished follow-ups. Its local agent checks the evidence, asks for missing information, and picks up its work after an interruption.”

**Boundary:** This prototype does not diagnose, prescribe, identify emotional or cognitive states, recommend treatment, or provide emergency monitoring. It does not secretly record calls or prove clinical efficacy.

## 2. The one deployment architecture to implement

```text
Samsung Galaxy S24 / Chrome
  consent + complete voice clips + explicit resource request
                 |
       USB-forwarded local origin
       or trusted HTTPS on a private LAN
                 |
FastAPI on paired laptop (one application worker)
  |-- local audio decoding, transcription, descriptive measurements
  |-- SQLite: durable jobs, action ledger, checkpoints, outbox
  |-- local Liquid llama-server: model-selected tools
  |-- RawTree API: approved demo history + evidence + checkpoint mirror
  |-- Nimble API: public search + page extraction
  `-- read-only status endpoints for the phone
```

Use the existing Python backend rather than introducing a Node service merely because one supplied link documents a Node SDK. The official Nimble Python SDK exists; direct HTTP is also documented. [S5–S7]

For the live prototype, use `llama-server` on the laptop. Liquid's docs document that server and a separate in-process Android integration. The LEAP SDK is listed as deprecated, with direct llama.cpp use recommended. [S1–S4]

Do not introduce Apollo as an undocumented server for ClearLine. Do not equate “a model runs in Apollo” with “our web application is connected to that model.”

### Local model selection

Primary candidate: `LiquidAI/LFM2.5-2.6B-GGUF:Q4_K_M`. The 2.6B model is documented for agentic workloads and tool calling. Its publisher provides a GGUF llama-server example. [S1, S2]

If the actual laptop cannot run it acceptably, deliberately evaluate `LiquidAI/LFM2.5-1.2B-Instruct-GGUF:Q4_K_M`. Record the chosen model, quantization, runtime build, and measured tool-call behavior. Do not silently swap models or invent latency/accuracy measurements.

Example startup, after installing a current compatible llama.cpp build:

```bash
llama-server \
  -hf LiquidAI/LFM2.5-2.6B-GGUF:Q4_K_M \
  --alias clearline-liquid \
  --host 127.0.0.1 --port 8080 \
  -c 4096 --jinja
```

The model download needs network access initially. Subsequent local inference can run without a Liquid-hosted service. Nimble and RawTree still require network access. `--alias` is a llama-server option; the app's `LIQUID_MODEL` must match the served alias. [S2, S3, S14]

**No sponsor-issued Liquid API key is required for this local server.** Use direct HTTP to its local chat-completions endpoint. If an SDK insists on an API key, a dummy client value is not a sponsor credential. Do not send that value to a cloud service or interpret it as authentication. Keep this unauthenticated model server bound to loopback. [S3]

## 3. Product privacy and data provenance

This is a demo-only prototype, not a deployed healthcare-data service.

Before recording, disclose the actual path: phone audio goes to the paired laptop; local audio processing and Liquid inference happen there; approved derived measurements and workflow metadata may be exported to RawTree; a public search category and user-entered city go to Nimble.

Use synthetic historical data and a consenting teammate's non-sensitive demonstration recording. Tag each record separately:

- `synthetic`: fabricated fixture, never presented as a real observation.
- `consented_demo`: an actual consenting demonstration input.

Do not label an entire dataset synthetic if it includes a live recording. Synthetic historical averages are an illustrative demo reference, not the live speaker's established personal baseline.

Require explicit consent for recording and a separate choice for cloud measurement export. If export is not approved, do not upload that clip's measurements or personal checkpoint payload. Show the resulting restricted mode and do not pretend RawTree received them. Synthetic-history and public-resource demonstrations remain possible.

Never export raw audio, transcripts, secrets, full free-form model messages, or a person's name to RawTree. Build outbound objects from an allowlisted schema, not from the entire internal state. Minimize identifiers and free text; pseudonymous identifiers are not proof of anonymity. A public search can itself reveal interests, so show the proposed search category and city before use.

Use this deployment wording: **“Voice processing and model inference run on the paired laptop; approved demo measurements and workflow data use RawTree.”** Do not claim that everything stays on the phone, that the app is fully offline, or that health-data compliance has been established.

## 4. Component 1 — Mobile frontend

**Stack:** Plain HTML, CSS, and JavaScript. Keep a single same-origin FastAPI deployment. The interface may be split into static files for maintainability.

### Screens

1. **Home:** ClearLine, explicit demo banner, profile history from the API, model/runtime connection status, and current unfinished task. No invented dates or red/green medical-risk badges.
2. **Check-in:** Consent, record/stop control, elapsed recording duration, local waveform, upload/processing status, and the actual accepted measurements when available. Distinguish recording quality from a health assessment.
3. **Summary:** Current descriptive measurements, baseline source and session count, unavailable fields, and optional differences. Include “No health interpretation is provided.” Stopping a recording opens a neutral summary, not a mandatory alert.
4. **Follow-up:** User chooses a public caregiver-support resource category and city; view sources, unknown fields, the pending step, pause/resume, and a compact tool/event timeline.

### Recording implementation

Use `navigator.mediaDevices.getUserMedia` after a user gesture and consent. Feature-detect `MediaRecorder` and supported MIME types. Record a complete approximately 20–30 second clip; combine all its `dataavailable` blobs and upload only after the recorder's final stop event. The server must receive a complete media container, not an arbitrary fragment assumed to decode on its own. The recording specification only guarantees playability of the combined complete recording, not each individual blob. [S13]

Each clip receives one stable `clip_id`. Reuse it on retry. A logical session may contain multiple clips, including a replacement for unusable input. Store only session/clip identifiers and a minimal client resume token in browser storage; do not persist audio by default.

Handle permission denial, unsupported format, zero-length audio, timeout, device disconnect, and duplicate upload. Track pending uploads so Stop does not declare a session complete before its final clip has been accepted. Page backgrounding must show an interruption rather than promise uninterrupted capture. Chrome on Android can delay recording events when the screen locks. [S9]

**Microphone transport:** Ordinary `http://192.168...` is not a reliable microphone setup because microphone access needs a secure context. Prefer Chrome USB port forwarding and open `http://localhost:3000` on the S24, or use trusted HTTPS. Verify on the actual phone. USB tethering alone is not the same as setting up port forwarding. [S8, S10]

## 5. Component 2 — Backend and audio pipeline

**Stack:** Python, FastAPI, uvicorn, python-multipart, httpx, Pydantic, python-dotenv, SQLite, numpy, librosa, a locally installed Whisper implementation, and ffmpeg. Freeze dependency versions after the smoke test on the build machine. Do not invent a tested version set.

Serve the HTML at `/`, assets at `/static`, and application endpoints under `/api`. Register API routes before any catch-all mount. Keep browser calls same-origin; do not enable wildcard CORS as a substitute for a working origin configuration. Require a paired demo session, validate Origin for mutations, and keep secrets server-side.

Use one application process and one serial workflow worker for this prototype. CPU-heavy audio processing must not block the request/event loop: execute it in a controlled thread/process worker. The persistent job table, not an in-memory task object, is the source of pending work. Cache loaded models once.

### Clip processing

1. Validate paired session, consent, identifiers, upload size, and supported media.
2. Save the upload under a server-generated temporary path unique to `(session_id, clip_id)`. Never use an unsanitized client filename or a shared `chunk.wav`.
3. Persist receipt and enqueue processing before acknowledging it as accepted.
4. Decode with ffmpeg into a known mono PCM sample rate, using a timeout and an argument list rather than shell interpolation. Inspect duration after decoding.
5. Check audio quality. Implementation thresholds are engineering filters, not health thresholds. Start with a minimum duration and detectable speech requirement; expose why an input is unusable.
6. Transcribe locally and permit an optional user correction. Do not interpret an empty/no-speech transcription as reliable text.
7. Compute descriptive metrics. Use `recording_wpm` for words divided by full clip duration; do not label that speech-only rate. Use pauses only when timestamp granularity supports them. RMS is recording amplitude, not a cross-device health measure. Optional pitch requires a voiced-frame mask; return null when unavailable.
8. In one local transaction, persist the result, advance the job, and queue allowlisted cloud events. Remove temporary media in success/failure cleanup once no accepted job needs it. If a crash occurs before features commit and the file remains, recover it; if it is missing, ask for a new recording.

Do not promise secure forensic erasure of temporary files. State the actual retention behavior.

### Stable metric contract

```json
{
  "duration_s": 24.1,
  "word_count": 45,
  "recording_wpm": 112.03,
  "pause_count": null,
  "energy_rms": 0.08,
  "pitch_mean_hz": null,
  "quality": "accepted",
  "quality_reasons": [],
  "data_origin": "consented_demo"
}
```

Numbers above illustrate the schema, not expected demo results. Null means unavailable, never zero by substitution.

Aggregate completed clips into **one versioned summary per completed session**. Duration-weight relevant quantities, retain measurement-method versions, and exclude rejected/superseded clips. A changed transcript produces a new summary version and invalidates dependent comparisons.

### Baseline behavior

Retrieve the latest version of the last five eligible completed sessions, excluding the current session. Deduplicate repeated event deliveries before computing statistics. Match recording task, measurement version, and demo/person provenance. Do not quietly combine different people or synthetic and real histories as a personal baseline.

Show session count, baseline source, and missing metrics. With insufficient history, return `insufficient_history`, not “normal” or a zero risk score. Show descriptive current value, mean, and delta. An optional standardized difference is not a risk probability and must not trigger clinical action. Do not divide by an arbitrary tiny constant to turn a zero-variance baseline into an alarming score.

## 6. Sponsor API contracts

### 6.1 RawTree — not legacy Tinybird Events/Pipes

Use `RAWTREE_API_KEY`, `RAWTREE_DATABASE`, and `RAWTREE_BASE_URL`. The default documented base is `https://api.rawtree.com`. RawTree documents these operations: [S11]

```http
POST /v1/tables/clearline_events
Authorization: Bearer <RawTree key>
x-rawtree-database: <database>
Content-Type: application/json
```

Body: one event object or an array of event objects.

```http
POST /v1/query
Authorization: Bearer <RawTree key>
x-rawtree-database: <database>
Content-Type: application/json

{"sql":"SELECT * FROM clearline_events LIMIT 1"}
```

For the default JSON query format, rows are under `data`. Handle non-2xx responses and an unexpected response shape explicitly. Never use `api.tinybird.co/v0/events` or `/v0/pipes/...` for a RawTree account. The schema-free table creation does not remove application-schema validation. [S11, S15]

Use a `read_write` key for the application where permitted. RawTree keys apply at organization/cluster scope, not just the selected database; a database header is not a security boundary. Never expose the key in the phone frontend. [S16]

Create named application operations: `append_event`, `read_baseline`, `read_latest_checkpoint`, and `read_evidence_version`. Expose bounded, schema-validated tool arguments rather than unrestricted SQL. Build SELECT statements from fixed templates and validated UUIDs/allowlisted identifiers. RawTree's documented query interface accepts a final SQL string and supports read-only queries; do not invent a server-side parameter API. [S15]

Tables:

- `clearline_events`: append-only approved workflow events.
- `clearline_session_summaries`: versioned, approved complete-session measurements.
- `clearline_checkpoints`: versioned, export-approved compact checkpoint projections.
- `clearline_resource_versions`: public source facts and version references.

Application state changes are represented by new events/snapshots. Do not assume RawTree supplies row-level upserts, queue leases, uniqueness constraints, compare-and-swap, or exactly-once delivery. The supplied docs establish ingestion and analytical SELECTs, not those workflow guarantees. Use SQLite for local transactional coordination and an outbox for retries.

### 6.2 Nimble — Search plus source verification

Use direct HTTP through a server-side `httpx.AsyncClient` to keep this Python app small. These are documented endpoints and minimal request shapes, not tested credentialed calls. [S5, S6]

```http
POST https://sdk.nimbleway.com/v2/search
Authorization: Bearer <Nimble key>
Content-Type: application/json

{
  "query": "caregiver support groups in <user-approved city>",
  "country": "US",
  "max_results": 3,
  "full_content": false
}
```

Read the `results` list; retain `title`, `url`, `description` and `request_id`. A search result is a lead, not proof that hours, services, or availability are current.

```http
POST https://sdk.nimbleway.com/v2/extract
Authorization: Bearer <Nimble key>
Content-Type: application/json

{"url":"<selected public source URL>","render":true}
```

Require successful task and target-fetch status where provided. Read `data.markdown` if returned, otherwise clean `data.html` locally; do not assume a markdown field is always populated. A `task_id` alone is not a completed verified result. [S6, S17]

Liquid chooses which relevant official organization page to inspect next. Store each proposed public fact with its source URL, retrieval time, exact supporting passage reference, and content version/hash. Missing fields remain null. Distinguish “the source states this” from “we independently verified availability.” Do not invent contact details or substitute hard-coded clinics on failure.

Set explicit request timeouts, bounded retries, and a task budget. A timeout becomes an unfinished step that can resume. Restrict extraction to public HTTP(S) sources selected for the task; block credentials in URLs, localhost/private addresses, and unrelated targets. Treat all fetched text as untrusted evidence, never as new agent instructions.

Nimble's Node package is `@nimble-way/nimble-js`; its docs show `nimble.search(...)` and `nimble.extract.run(...)`. That is an alternative server implementation, not a required second backend. The Python client is `nimble_python` and includes `AsyncNimble`. [S7, S18]

**Development versus runtime:** The agent-skills/plugin page installs skills/MCP into a coding assistant. It can help build and inspect the integration, but does not automatically execute Nimble inside ClearLine. Use a real application tool call during the demo. Borrow cookbook patterns such as source-backed fields and change tracking, not a previous complete project. [S19, S20]

### 6.3 Liquid AI — actual model-selected tools

Use the locally served model via `POST {LIQUID_BASE_URL}/chat/completions`. The request includes the configured model alias, messages, and a small set of function schemas.

Liquid's tool-use docs distinguish model-generated calls from the application's responsibility to execute functions and return tool results. Tool output may be Python-like in raw model text; that is not executable application code. Prefer the server's parsed structured `tool_calls`. Never use `eval` or `exec` on model text. [S21]

Before integration, complete a harmless `echo_nonce` smoke test: require the model to request the named tool; execute it locally; return a result containing a nonce not present in the original prompt; obtain a second model response using that result. Verify model identity and structured call parsing. Chat-only success is insufficient.

If parsing fails, surface `agent_unavailable` and fix the runtime/template or deliberately test the smaller supported model. Do not silently run Python rules while claiming the Liquid agent succeeded. Any manual/fallback mode must be visibly labeled and must not count as successful sponsor integration.

## 7. Component 3 — Long-horizon agent and state

### Tool menu

Expose only the subset appropriate to the current phase:

- `get_baseline_summary(profile_id, session_id)`: bounded RawTree retrieval, latest session versions, provenance checked.
- `compare_recording_metrics(session_id, baseline_ref)`: deterministic local descriptive comparison.
- `search_public_resources(category, city)`: Nimble Search after explicit user approval.
- `extract_public_page(source_ref)`: Nimble Extract for an approved returned source.
- `request_user_input(reason_code, question)`: create a pending input request, never fabricate an answer.
- `finish_task(summary, evidence_refs)`: propose completion; the controller validates all requirements before accepting it.

The controller handles ingestion, authorization, persistence, token accounting, and deterministic safety/quality gates. Liquid selects the next permitted action, interprets failures, and decides whether another source or clarification is needed. It cannot bypass consent, change profile identity, execute arbitrary SQL, or declare unsupported clinical findings.

### States and transitions

`recording → processing → awaiting_input | comparing → awaiting_user_choice → researching → waiting_retry | ready`

`paused` and `agent_unavailable` are explicit states. `ready` means the current administrative/check-in task is complete, not that the person is medically safe. Future sessions and open follow-ups may continue separately.

An invalid clip requests replacement. Insufficient history displays that fact. A user correction invalidates only the comparison depending on the changed input. Changing the requested city invalidates resource-search work, not previously accepted audio. A source failure retains the unresolved action. A changed public page creates a new evidence version and retires the obsolete current-state fact without erasing audit history.

### Durable state

Use a local SQLite transaction for every accepted state transition. Persist: `session_id`, `profile_id`, `state_version`, `input_revision`, `phase`, `consent`, accepted input references, baseline version reference, unresolved requirements, pending action, action status, retry time, and export status.

The active model checkpoint is a bounded projection, for example:

```json
{
  "schema_version": 1,
  "session_id": "<uuid>",
  "state_version": 7,
  "input_revision": 2,
  "phase": "researching",
  "goal": "Find user-requested caregiver support resources",
  "baseline_ref": "<versioned reference>",
  "accepted_clip_refs": ["<clip reference>"],
  "current_measurements_ref": "<summary reference>",
  "unresolved_requirements": ["source-backed contact information"],
  "pending_action": {"name":"extract_public_page","source_ref":"<source reference>"},
  "recent_completed_action_refs": ["<action reference>"],
  "completed_action_watermark": 5,
  "evidence_refs": ["<versioned source reference>"],
  "execution_mode": "liquid_local"
}
```

Do not send private consent details or full local state to cloud storage accidentally. Construct a separate approved export projection.

### Idempotency and crash recovery

Use unique local `(session_id, clip_id)` and `action_id` records. The controller, not the model, assigns stable action IDs before tool execution. Persist `planned → started → succeeded/failed/unknown`, the result reference, and the state version.

Commit accepted results and outbox rows together. Outbox delivery retries the same `event_id`; RawTree may contain duplicate deliveries, so deduplicate them in analytical queries. Versioned summaries must not count twice in a baseline.

On restart, recover durable jobs and resume from the last committed boundary. Reuse successful tool results when their inputs/source validity still match. A read-only web request interrupted after sending but before receiving may be retried; do not promise exactly-once external execution. Keep external writes such as sending emails, booking, or submitting forms out of the MVP.

RawTree is the event/evidence history and analytics service, not the transactional job queue. SQLite is the local recovery mechanism. Name both honestly. Demonstrate a backend process restart on the same laptop; do not claim survival of disk/device loss.

### Context compression

Keep the current goal, constraints, bounded pending work, baseline summary, current metrics, recent action references, and relevant source excerpts. Store full permitted evidence/history outside the active model prompt. Retire stale facts by version, and retain references for later inspection.

Target a total 4,096-token runtime context with a conservative input allowance and output reserve. Include system text, tool schemas, and evidence in the budget. Use the runtime's supported tokenization/template facilities to enforce a tested budget; record actual prompt usage when available. Report bytes or estimates as such, not as measured tokens. Limit each activation to a small tool-call budget and persist before yielding.

Do not prune away unfulfilled obligations to meet the context target. Cap the active queue and keep additional work in durable storage. Recent completed-action lists are bounded; the complete action ledger is not re-injected into prompts.

## 8. Shared application contract

All lanes use these routes; changing them requires updating the contract first.

| Method and route | Responsibility |
|---|---|
| `POST /api/sessions` | Create paired, consented demo session; return server-owned session ID. |
| `POST /api/sessions/{id}/clips` | Accept complete recording with stable `clip_id`; persist and queue. |
| `POST /api/sessions/{id}/finish` | Mark capture complete; queue comparison after accepted uploads finish. |
| `POST /api/sessions/{id}/resources` | Save explicit category/city request and queue research. |
| `POST /api/sessions/{id}/input` | Apply requested user correction/answer with revision checking. |
| `POST /api/sessions/{id}/pause` | Request a durable pause at a safe boundary. |
| `POST /api/sessions/{id}/resume` | Resume eligible unfinished work, once. |
| `GET /api/sessions/{id}` | Read stored status only. No model/tool calls, ingestion, or re-enqueue. |
| `GET /api/profiles/{id}/history` | Return authorized, version-deduplicated session summaries. |
| `GET /api/health` | Report known component readiness without returning secrets. |

Status includes `session_id`, `state_version`, `phase`, `execution_mode`, `metrics`, `comparison`, `resources`, `pending_action`, `errors`, `provenance`, and `cloud_sync`. Unknown metrics are null. Resources include source URLs, retrieval times, verification status, and unknown fields. Errors distinguish retryable failures from unavailable credentials or an invalid tool call.

Do not return `drift_score`, `clinical_risk`, `emotion`, `alert_fired`, invented report images, or an internally inconsistent 0–100/1.5 threshold. The UI is a view of stored state, not a second scoring engine.

## 9. Codex three-session build plan

This specification is designed to be placed in the repository root as `ClearLine_Fixed_Build_Spec.md`. Open **three separate Codex chats** against the same repository. In each chat, you only need to say one of the exact commands below after Codex can see this file:

- `Run Session 1`
- `Run Session 2`
- `Run Session 3`

Codex must treat this file as the source of truth. Each session must inspect the repository before changing anything, stay inside its ownership boundaries, preserve working code from the other sessions, and report exactly what it changed and tested. Never silently replace sponsor integrations with fake success.

### Session 1 — Mobile frontend

**User command:** `Run Session 1`

**Codex instruction:**

> You are Session 1 of the ClearLine build. Read `ClearLine_Fixed_Build_Spec.md` completely before editing. Build only the phone-first frontend and the smallest frontend-only helpers needed to exercise the shared API contract. Do not implement Liquid AI, RawTree, Nimble, SQLite workflow logic, audio analysis, or backend business logic.
>
> Your ownership is `frontend/` only. If the repository does not yet have that directory, create it. Do not edit `backend/`, `.env`, sponsor integration files, or dependency files owned by the other sessions.
>
> Implement a Samsung Galaxy S24-friendly mobile UI with these screens: Home, Check-in, Summary, and Follow-up. The UI must include explicit recording consent, a demo-data banner, model/runtime connectivity status, current unfinished task, record/stop controls, elapsed time, waveform or simple live level visualization, upload/processing states, descriptive metrics, baseline provenance/session count, resource cards with source URL/retrieval status, pause/resume controls, and a compact event/tool timeline. Do not show cognitive-risk, diagnosis, emotion labels, clinical alerts, or fabricated medical conclusions.
>
> Use the exact shared routes from Section 8. Poll `GET /api/sessions/{id}` as a read-only status endpoint. Polling must never trigger work. Use `POST /api/sessions`, `/clips`, `/finish`, `/resources`, `/input`, `/pause`, and `/resume` only for the actions defined in the spec.
>
> Recording requirements: use `getUserMedia` only after a user gesture and consent; feature-detect `MediaRecorder`; create one complete approximately 20–30 second recording; combine `dataavailable` chunks and upload only after the final stop event; generate one stable `clip_id` and reuse it on retry. Handle permission denial, unsupported MIME type, zero-length recording, upload timeout, duplicate retry, disconnect, and interrupted page state. Do not assume arbitrary timeslice blobs are independently decodable.
>
> Keep the implementation simple: plain HTML/CSS/JS unless the repository already has an established frontend framework. Prefer same-origin API calls. Do not embed secrets or call RawTree/Nimble directly from the browser.
>
> Because Session 2 may not be finished yet, add an obvious `DEMO_FIXTURE_MODE` or mock adapter that can render the interface from clearly labeled fixture responses without changing the production API shape. It must be trivial to disable once the real backend exists. Fixture data must be visibly labeled synthetic/demo data.
>
> Before finishing, test the UI locally in a narrow mobile viewport and verify that the frontend issues only the documented API calls. Report: files changed, how to launch the frontend with the backend, what is mocked, what is real, and any blockers. Do not claim phone microphone testing unless it was actually tested on the S24.

**Session 1 deliverable:** A usable mobile interface in `frontend/` that can switch from labeled fixtures to the real shared API without a redesign.

---

### Session 2 — FastAPI, audio pipeline, SQLite, and recovery worker

**User command:** `Run Session 2`

**Codex instruction:**

> You are Session 2 of the ClearLine build. Read `ClearLine_Fixed_Build_Spec.md` completely before editing. Build the backend API, local persistence, audio-processing pipeline, durable job/checkpoint system, and recoverable worker. Do not implement the Liquid tool loop or the RawTree/Nimble adapters; expose clean interfaces that Session 3 can implement. Do not edit `frontend/`.
>
> Your ownership is `backend/main.py`, `backend/api/`, `backend/audio/`, `backend/storage/`, `backend/worker/`, local database/migration code, and backend dependency configuration. If these paths do not exist, create a clean structure. Preserve any working code already created by another session.
>
> Implement every route in Section 8 exactly. `GET /api/sessions/{id}` must be read-only and must never enqueue work, call a model, call a sponsor API, or advance state. Use SQLite for sessions, clips, jobs, action ledger, checkpoints, outbox, and idempotency. Persist every accepted transition transactionally. Use stable `(session_id, clip_id)` uniqueness and stable `action_id`s.
>
> Implement the state machine from Section 7, including `paused` and `agent_unavailable`. Implement durable pause/resume at safe boundaries. On backend restart, recover unfinished jobs from the last committed checkpoint. Never promise exactly-once network execution; reuse committed successful results and retry unfinished read-only work safely.
>
> Audio requirements: accept complete uploads only; write to unique temp paths; decode with ffmpeg using an argument list and timeout; inspect duration; reject unusable/silent input with explicit quality reasons; transcribe locally; compute the stable descriptive metric contract; return null for unavailable values; aggregate accepted clips into one versioned session summary; exclude rejected/superseded clips. Remove the emotion classifier, cognitive-risk logic, clinical thresholds, and readability/vocabulary scoring from the MVP.
>
> Baseline requirements: last five eligible completed session versions, excluding the current session, deduplicated by latest version and matching provenance/measurement method. Return `insufficient_history` when appropriate. Never output a risk probability or clinical interpretation.
>
> Create a small integration boundary for Session 3, for example an `AgentRunner`/`SponsorTools` protocol or service interface. The worker should be able to ask that interface for the next permitted action and execute it, but Session 2 must not fake sponsor success if Session 3 is absent. Surface `agent_unavailable` instead.
>
> Implement the outbox pattern for approved RawTree exports without embedding RawTree HTTP code here. Construct allowlisted export projections so raw audio, transcript text, names, secrets, and unrestricted model messages cannot accidentally leave the local machine.
>
> Add tests for: duplicate clip upload, repeated status polling, crash/restart recovery, pause/resume, invalid audio, no history, zero-variance history, and outbox retry idempotency. If ffmpeg/Whisper are unavailable, fail visibly and document setup rather than substituting fake metrics.
>
> Before finishing, run the backend tests you can actually execute. Report: files changed, database schema, routes implemented, recovery test result, audio dependencies required, integration interfaces Session 3 must implement, and any blockers.

**Session 2 deliverable:** A real FastAPI/SQLite application that can accept a phone recording, persist state, recover after restart, and wait cleanly for sponsor-agent integration.

---

### Session 3 — Liquid AI + RawTree + Nimble agent integrations

**User command:** `Run Session 3`

**Codex instruction:**

> You are Session 3 of the ClearLine build. Read `ClearLine_Fixed_Build_Spec.md` completely before editing. Implement the sponsor integrations and long-horizon Liquid agent loop. Do not redesign the frontend or backend routes. Integrate against Session 2's storage/job interfaces.
>
> Your ownership is `backend/agent/`, `backend/integrations/`, sponsor smoke tests, and `.env.example`. You may make the smallest necessary interface hookup outside those directories only if Session 2 explicitly left an integration seam; document every such change. Do not rewrite Session 2's API/state machine.
>
> Liquid AI: use the local llama.cpp `llama-server` path from Section 2, defaulting to `http://127.0.0.1:8080/v1` and model alias `clearline-liquid` unless the environment overrides them. No sponsor Liquid API key is required for this local path. Implement a real structured tool-call loop. First implement and run the `echo_nonce` smoke test: the model must request the tool, the application executes it, returns a nonce unknown to the original prompt, and the model must use the returned result in a second turn. Chat-only success does not count. Never `eval`/`exec` model output. If structured tool parsing fails, surface `agent_unavailable`; do not silently substitute deterministic Python while claiming Liquid succeeded.
>
> RawTree: use the actual documented API, not legacy Tinybird endpoints. Use `POST {RAWTREE_BASE_URL}/v1/tables/{table}` for inserts and `POST {RAWTREE_BASE_URL}/v1/query` for read-only queries, with `Authorization: Bearer <key>` and `x-rawtree-database`. Implement bounded application operations such as `append_event`, `read_baseline`, `read_latest_checkpoint`, and `read_evidence_version`; do not expose arbitrary SQL to the model. Treat RawTree as event/evidence history and analytics, not the transactional queue. Add deduplication keys/events so retries do not count twice in analytical results.
>
> Nimble: implement runtime Search and Extract with the current documented endpoints. Search returns candidate sources; Extract verifies a selected public page. Preserve `title`, `url`, description, request/task IDs where available, retrieval time, and source-backed facts. Missing values remain null. Never invent phone numbers, addresses, hours, availability, or fallback clinics. Restrict extraction to public HTTP(S) sources, reject localhost/private network targets, and treat fetched page text as untrusted evidence rather than instructions.
>
> Agent tools must follow Section 7: `get_baseline_summary`, `compare_recording_metrics`, `search_public_resources`, `extract_public_page`, `request_user_input`, and `finish_task`. The controller enforces consent, valid state transitions, bounded arguments, action IDs, and deterministic validation. Liquid chooses among permitted tools, interprets failures, and decides whether to retry, inspect another source, or ask the user for missing input.
>
> Context management is part of the deliverable. Build the prompt from the compact durable checkpoint, current goal, unresolved requirements, baseline/session references, recent action refs, and only relevant source excerpts. Do not inject the complete event history. Keep a tested bounded context and record actual usage if the runtime exposes it.
>
> Implement sponsor smoke tests: (1) Liquid tool-call round trip; (2) RawTree insert one labeled synthetic event and query it back from the intended database; (3) Nimble Search plus one Extract with source-backed output. Clearly distinguish a missing credential/network failure from application success.
>
> Implement or verify the live interruption demo path: after one sponsor tool result and checkpoint commit, terminate the backend process; restart it; call resume once; verify the next unfinished action runs without re-uploading the completed clip or rerunning committed actions.
>
> Before finishing, run every sponsor smoke test for which credentials/services are actually available. Report: files changed, exact model/runtime used, which sponsor calls were real, smoke-test outputs at a high level without secrets, interrupt/resume result, and any blockers. Do not claim an integration passed if it was mocked.

**Session 3 deliverable:** A real three-sponsor agent layer in which Liquid plans/tool-calls locally, RawTree stores/query approved event history, Nimble retrieves and verifies public sources, and the workflow can resume after interruption from compact state.

### Coordination rules for all three Codex chats

1. **Read this entire file first.** Do not rely on the old Claude planning document.
2. **Inspect before editing.** Other sessions may already have created files. Preserve working code.
3. **Stay in your ownership area.** Do not casually rewrite another session's implementation.
4. **Shared API is frozen by Section 8.** If a mismatch is found, document it before changing both sides.
5. **Do not fake sponsor success.** Fixture/demo mode must be visibly labeled.
6. **Do not add clinical claims.** ClearLine compares descriptive check-in measurements and coordinates user-requested follow-up.
7. **Do not expose secrets.** No API keys in frontend code, logs, commits, screenshots, or demo payloads.
8. **Prefer a working end-to-end path over extra features.** Do not add BFL, email sending, bookings, payments, or unrelated integrations.
9. **Commit/checkpoint your work cleanly** if the repository workflow supports it, so another session can inspect what changed.
10. **Finish with a concise handoff:** changed files, tests run, known failures, and what the next session needs.

## 10. Acceptance gates — run before demo polish

1. **Liquid:** real local structured tool call, execution, and second model turn succeed; model alias matches served model.
2. **RawTree:** insert one labeled synthetic event and retrieve it from the intended database with the actual credentials. Reject legacy endpoint usage.
3. **Nimble:** live Search and one Extract succeed; the resulting card contains only source-backed fields. Report network failures honestly.
4. **Phone:** actual S24 microphone works through the chosen origin; complete recording decodes on the laptop.
5. **Polling:** repeated GET status calls do not increase tool executions, state versions, or event count.
6. **Deduplication:** submitting the same clip twice and retrying an outbox event does not count it twice in session/baseline results.
7. **Crash:** stop the backend after one tool result commits; restart with the same database; resume the next unfinished step without re-uploading completed input.
8. **Correction:** changing the city invalidates only search-dependent work; changing a transcript invalidates dependent measurements/comparison.
9. **Boundary cases:** silence, missing audio, no history, zero-variance baseline, stale source, missing source field, 401/429/timeouts, invalid model calls, and unavailable model are handled without invented output.
10. **Privacy:** inspect outbound test payloads for transcript text, names, audio bytes, and secrets; reject disallowed exports.
11. **Context:** a long synthetic event history does not cause the active prompt to grow with the complete timeline.
12. **Honesty:** fixture data, cached evidence, consented live data, and fallback execution each have distinct visible labels.

These are requirements to execute on the user's machine. They are not claims that credentialed services, model inference, or the S24 were tested while preparing this file.

## 11. Three-minute demonstration

**0:00–0:25 — Story.** “I check in with my parent, but the unfinished follow-up gets lost when life interrupts.” Show a demo persona and explicitly labeled synthetic history.

**0:25–0:55 — Phone input.** Record a normal consenting demonstration clip, or show a clearly labeled prerecorded backup. Do not act confused to simulate a disease. Show quality validation and descriptive measurements, with the synthetic baseline labeled.

**0:55–1:20 — User-directed action.** Request public caregiver-support resources in a chosen city. Show Liquid's actual named tool call, Nimble's live source result, and a saved RawTree event.

**1:20–2:00 — Real interruption.** After the search result/checkpoint commits and before the next extract step, terminate the backend process. The phone shows disconnected. Restart on the same laptop and resume the same session. Show the restored pending step and unchanged accepted clip ID.

**2:00–2:35 — Verification and correction.** Extract the selected official page; show cited known fields and any remaining unknown. If using a deterministic changed-page fixture, label it a simulated source update rather than implying an official site changed live.

**2:35–3:00 — Architecture.** Show event-history size, bounded active context with its actual measurement label, completed/pending action counts, and source provenance. “The agent carries forward the verified state and unfinished work, not every prior conversation.”

The full build-cycle mapping is: **specify** the user's check-in/follow-up goal; **execute** capture, comparison, and research; **verify** input and source evidence; **iterate** on missing input, changed constraints, and failed tools. This is a proposed fit to the supplied hackathon brief, not a guarantee of judges' interpretation.

## 12. Configuration

Use the included `.env.example`. Never commit filled credentials. `LIQUID_BASE_URL` is local; its presence does not imply cloud hosting. `LIQUID_MODEL` is the server alias. `RAWTREE_API_KEY` and `NIMBLE_API_KEY` are the two external service secrets required for this architecture. A RawTree database must exist or be selected from the account's available/default database.

Do not start all three implementation lanes until the shared schema and the three sponsor smoke-test contracts are understood. Prioritize the local tool round-trip and actual phone recording before aesthetic work.

## Sources

Source-derived capability/API statements are cited above. The workflow, safety gates, schema, limits, file ownership, and tests are proposed engineering decisions, not sponsor requirements.

- **S1** Liquid model library and 2.6B model: https://docs.liquid.ai/lfm/models/complete-library ; https://docs.liquid.ai/lfm/models/lfm25-2.6b
- **S2** Official GGUF model card: https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF
- **S3** Local serving: https://docs.liquid.ai/deployment/on-device/llama-cpp
- **S4** Mobile/deprecations: https://docs.liquid.ai/deployment/on-device/llama-cpp/mobile ; https://docs.liquid.ai/lfm/help/deprecations
- **S5** Nimble Search contract: https://docs.nimbleway.com/api-reference/search/search
- **S6** Nimble Extract contract: https://docs.nimbleway.com/api-reference/extract/extract
- **S7** Nimble Python SDK: https://docs.nimbleway.com/nimble-sdk/sdks/python
- **S8** Chrome port forwarding: https://developer.chrome.com/docs/devtools/remote-debugging/local-server
- **S9** Recording timing: https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/dataavailable_event
- **S10** Microphone secure context: https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia
- **S11** RawTree API: https://rawtree.com/docs/reference/api ; https://rawtree.com/docs/quickstart/api
- **S12** Original emotion model's task/data: https://huggingface.co/ehcalabres/wav2vec2-lg-xlsr-en-speech-emotion-recognition
- **S13** Complete recording semantics: https://www.w3.org/TR/mediastream-recording/
- **S14** llama-server options: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
- **S15** RawTree SQL: https://rawtree.com/docs/guides/query-data
- **S16** RawTree auth: https://rawtree.com/docs/reference/authentication
- **S17** Nimble Extract guide: https://docs.nimbleway.com/nimble-sdk/web-tools/extract/quickstart
- **S18** Nimble Node SDK: https://docs.nimbleway.com/nimble-sdk/sdks/node
- **S19** Nimble development plugin: https://docs.nimbleway.com/integrations/agent-skills/plugin-installation
- **S20** Nimble cookbooks: https://www.nimbleway.com/cookbooks
- **S21** Liquid tool use: https://docs.liquid.ai/lfm/key-concepts/tool-use
