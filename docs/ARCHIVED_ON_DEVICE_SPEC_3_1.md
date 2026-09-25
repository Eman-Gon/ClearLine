# ClearLine — True On-Device S24 Build Specification

**Revision:** 3.1 · September 25, 2026

**Status:** Replacement architecture and implementation plan. Native app and S24 acceptance gates are not yet complete.

**Target:** Native Android app with Liquid inference, audio processing, agent execution, and durable state on the Samsung Galaxy S24.

**Supersedes:** Revision 3.0's category-only research and blanket transcript-export restriction. Retains its phone-local architecture, consent boundaries, and recovery requirements; also replaces Revision 2.1's laptop-local assignments.

## 1. Product and mandatory deployment requirements

ClearLine maintains consent-based voice check-ins and unfinished, user-chosen caregiver follow-ups across interruptions. The S24 records a short clip, transcribes it locally, computes descriptive measurements, and runs Liquid locally to choose validated tools. Its database saves completed steps and the next unfinished action. The local transcript shapes a proposed Nimble query that the user reviews before sending. Separately approved RawTree exports can include selected measurements, a short transcript snippet, and a keyword; the app makes that exported memory visible through counts, history, and a sourced baseline comparison.

All of these belong on the S24:

- Native UI and microphone capture.
- Audio, transcription, measurements, and historical comparisons.
- Liquid weights, inference, prompt assembly, tool-call parsing, and validation.
- Local profiles, sessions, source evidence, action ledger, checkpoints, and export outbox.
- Pause, process-death recovery, and explicit resume.

A laptop may build/install the APK, transfer models during setup, inspect redacted diagnostics, or mirror the screen. It must not process recordings, run the app's model, coordinate its agent, or hold the authoritative runtime database. Disconnecting it must not stop the installed app.

**No Liquid API key. No Liquid cloud endpoint. No Ollama or localhost:11434 dependency. No phone or laptop llama-server dependency. No FastAPI backend in the Android execution path.**

Use exactly the three selected sponsors: Liquid AI, RawTree, and Nimble. Speech recognition is a local implementation dependency, not an additional cloud service.

**Product boundary:** descriptive check-ins and administrative follow-up only. No cognitive-decline prediction, dementia screening, emotion classification, diagnosis, clinical risk score, treatment advice, or emergency monitoring. A person's stated concern may shape public-resource research; measured changes do not establish a condition or automatically trigger searches or referrals. No emotion/confidence, pause, vocabulary, or drift value may be invented to support the query or pitch.

**Pitch after device acceptance:** “The agent brain runs locally on your phone. Your sensitive voice history never needs to be sent to a cloud model. ClearLine saves the next unfinished step so you can pick up where you left off.”

Until then, use “We are building…” and distinguish implemented components from verified on-device behavior.

## 2. Architecture and migration

~~~text
Samsung Galaxy S24 — native Android application
  Kotlin / Jetpack Compose UI
       |
  Local commands + read-only observed state
       |
  One serialized foreground coordinator
       |-- AudioRecord -> finalized local PCM/WAV
       |-- Embedded whisper.cpp -> transcript + measurements
       |-- Embedded llama.cpp / JNI -> Liquid GGUF -> validated tool proposal
       |-- Room / SQLite -> local history, jobs, actions, checkpoints, outbox
       |
       |-- approved transcript-shaped query/city -> Nimble HTTPS Search / Extract
       `-- approved measurements/snippet/keyword -> RawTree HTTPS memory views

Explicit setup: download or import model artifacts before offline use.
No runtime connection to a developer laptop.
~~~

Kotlin/Compose, Room, coroutines, JNI/NDK, and native HTTP are this project's implementation choices. Pin compatible Gradle, Android plugin, Kotlin, SDK/NDK, CMake, library versions, and native runtime commits after the bootstrap build succeeds. Do not invent a tested version set.

Use one application process and one serialized execution coordinator. Audio processing, model loading, and inference run off the main thread. Recording and inference are foreground-only for the MVP; interruption saves progress for explicit Resume. Continuous background inference is outside this build.

### Existing code is reference material

The repository has a web UI under frontend/ and Python/FastAPI under backend/. Changing this file does not convert that implementation into Android.

| Existing artifact | Native migration |
| --- | --- |
| frontend/ | Reuse screen concepts, wording, consent behavior, and test scenarios; build native UI. |
| backend/audio/ | Preserve metric semantics/errors; replace Python/ffmpeg/faster-whisper with phone-native capture and ASR. |
| backend/storage/ and backend/worker/ | Port transactions, identities, corrections, and recovery to Room/Kotlin. |
| backend/agent/ | Port context and validation; replace localhost HTTP inference with in-process JNI. |
| backend/integrations/ | Port bounded sponsor request/response behavior to Android HTTPS. |
| Python/Node tests | Behavioral references only; do not establish Android inference or S24 performance. |
| .env and existing READMEs | Legacy desktop setup; never package them into the APK or follow their laptop startup path for native acceptance. |

Preserve existing directories during migration. Create the native application under android/. Label any old preview as a desktop prototype. Do not automatically migrate private recordings or cloud history.

Native commands replace pairing codes, browser resume tokens, Origin/CORS checks, HTTP audio uploads, polling routes, and LIQUID_BASE_URL.

## 3. Liquid runtime and model feasibility

Liquid currently documents embedding llama.cpp through its native C API and an Android NDK integration. Its LEAP SDK is deprecated. Use the embedded path. [Liquid mobile integration](https://docs.liquid.ai/deployment/on-device/llama-cpp/mobile), [deprecations](https://docs.liquid.ai/lfm/help/deprecations).

Start device evaluation with **LiquidAI/LFM2.5-1.2B-Instruct-GGUF, Q4_0**. The mobile guide uses this small checkpoint as an example. Evaluate Q4_K_M or LFM2.5-2.6B as an explicit alternative if tool reliability/performance warrants it. The 2.6B checkpoint targets agentic use; documentation is not proof that it meets this app's device requirements. [1.2B model](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF), [2.6B model](https://docs.liquid.ai/lfm/models/lfm25-2.6b).

Record the actual S24 variant, SoC, RAM, Android version, immutable model revision, filename, quantization, SHA-256, native commit/build flags, and context size. Do not assume all S24 variants have the same hardware.

Session 2 must implement:

1. An arm64-v8a in-process runtime behind Kotlin interfaces. Adapt the upstream example or own the JNI bridge; inspect the pinned APIs.
2. Private model-file import/download with free-space checks, pinned digest verification, and atomic completion. Partial files never count as installed.
3. Visible missing/installing/verifying/loading/ready/unloading/failed states and the actual loaded model identity.
4. Serialized native context access, supported cancellation boundaries, and safe unload. Never free a context being used by another thread.
5. Token counts from the loaded tokenizer over the fully rendered prompt, including tool schemas/results. Start with a measured 4096-token context budget.
6. Actual cold-load, prefill/decode, tool-round-trip, memory, and repeated-run thermal measurements. Leave unmeasured values blank. CPU is the initial path; acceleration requires actual-device verification.
7. Coexistence of whisper.cpp and llama.cpp in one APK without native symbol/packaging conflicts. Serialize ASR and inference; unload a model if memory requires it.

Reference: [upstream Android example](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android).

### Tool calls must work without a model server

The previous adapter received parsed tool calls from an HTTP server. The native bridge must now provide app-owned parsing/validation.

LFM tool use includes tool definitions, a model-generated call, execution, a tool-role result, and a second model turn. JSON calls can be requested explicitly; template/control-token handling must match the checkpoint. [Liquid tool use](https://docs.liquid.ai/lfm/key-concepts/tool-use).

- Preserve required chat-template and tool-call tokens. A UI text-stream API that strips control tokens is insufficient.
- Request exactly one JSON call at a time. Parse bounded complete output; reject unknown tools/fields, invalid arguments, multiple calls, truncation, and malformed output.
- Use a pinned template/parser or a documented strict implementation for the chosen model. Do not assume llama-server parsing helpers exist in the basic C API.
- Never execute generated Python, JavaScript, SQL, shell, or other code. Grammar constraints do not replace consent/state validation.
- Save the matching assistant/tool exchange and call identity in checkpoints; reconstruct it after process death without a surviving KV cache.
- Invalid output produces a bounded retry/error and then agent_unavailable. No hidden cloud fallback or scripted planner labeled Liquid.

**First hardware gate:** with models installed and network disabled on the S24, ask Liquid to call echo_nonce. Generate the nonce only after validating the call, execute locally, return a tool result, and verify a second inference uses it. Then exercise actual permitted-tool selection cases. A chat response alone does not pass.

## 4. Local data, consent, and credentials

Recording, Nimble research, and RawTree export require separate choices. Local-only storage is the default.

| Destination | Permitted data | Gate |
| --- | --- | --- |
| Phone-private storage | Audio during processing; transcript, metrics, local state and evidence | Recording consent and retention explanation |
| Liquid inference | Compact checkpoint, permitted tools, relevant local data/public excerpts | In-process; no inference network request |
| Nimble | Exact approved concern/topic query, city, and selected public result URLs | User approval tied to final query and input revision |
| RawTree | Selected measurements, optional bounded snippet/keyword, and/or minimal workflow/source projections | Separate field-level export approval, off by default |
| Model download host | Model artifact request | Explicit setup, without voice/history |

Synthetic inputs stay synthetic; actual consenting non-sensitive demonstration recordings are consented_demo. Do not mix these into personal baselines. Synthetic status does not bypass export consent.

Never send audio, full transcripts, personal names, private notes, full prompts/model output, credentials, or full local checkpoints to sponsors. The only transcript-derived outbound text is the exact approved Nimble concern query and the separately approved RawTree snippet/keyword. Construct outbound objects from typed allowlists. A RawTree checkpoint projection is a limited approved record, not a backup of private agent state.

Build the query and export preview locally after ASR. Show the complete outgoing query; use a generalized concern phrase instead of copying arbitrary sentences, contact details, or names. Show the exact optional RawTree snippet (at most 200 characters) and keyword (at most 40 characters) before approval, and allow editing or omission. Text export is not implied by measurement export or by Nimble approval. Store approved values with their session/input revision; changes require fresh approval of the changed outbound content. Do not rely on automatic redaction as proof that text contains no sensitive information.

Show which fields leave the phone. Queries, snippets, keywords, cities, and pseudonymous identifiers may reveal personal concerns. Enabling export must not silently backfill prior sessions. Revocation cancels unsent projections and prevents new dispatches; it cannot recall already delivered or in-flight requests. Previously exported history may therefore be incomplete or retain older approved versions; present its coverage honestly.

Use app-private storage and exclude sensitive files, DB/WAL files, model files, and credential ciphertext from cloud backup and device transfer. Set manifest policy plus applicable explicit backup/data-extraction rules; verify on the S24. allowBackup=false alone is not proof that every transfer path is excluded. [Android backup rules](https://developer.android.com/identity/data/autobackup).

Delete audio after terminal processing no longer needs it; retain complete unprocessed input for recovery. Clean orphan/partial files on startup. Transcripts/checkpoints remain until local deletion. Provide session/profile deletion and cancel queued work/exports. Do not promise forensic erasure or recovery after app-data deletion.

### Private prototype credentials

Use direct phone-to-sponsor HTTPS with owner-provided credentials for the private demo. Enter Nimble/RawTree keys in native settings; there is no Liquid credential.

No shared secrets in source, assets, BuildConfig, QR fixtures, commits, or APKs. Session 3 implements a vault using an Android Keystore-generated encryption key and encrypted token values in private, backup-excluded storage. Tokens never enter model context, logs, checkpoints, screenshots, or analytics. Provide clear-key controls. Keystore does not make API tokens impossible to obtain on a compromised phone. [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [security guidance](https://developer.android.com/privacy-and-security/security-tips).

Do not publicly distribute organization-wide keys. Broader distribution would require scoped authorization or a separately designed credential broker; it must not receive voice history or become the inference/checkpoint host. That service is outside this prototype.

After model setup, the app's external traffic is limited to explicitly approved sponsor operations. No private-content telemetry/crash reports. Opening a source in an external browser requires user action and leaves the app's privacy boundary.

## 5. Session 1 component — Native app, audio, storage

### Screens

1. **Setup:** actual model installation/loading, local transcription readiness, optional sponsor settings, and data explanation.
2. **Home/history:** local profile, check-ins, open follow-ups, model readiness, and next unfinished step. Earlier sessions/results remain reopenable after starting a new check-in.
3. **Check-in:** consent, microphone permission, record/stop, elapsed duration, local level meter, interruption and processing status. Show the last confirmed RawTree count and pending export status when available, with retrieval time and count scope.
4. **Summary:** descriptive metrics, local baseline provenance/count, null/unavailable fields, optional transcript correction, and “No health interpretation is provided.” Offer the separately labeled RawTree baseline/current table and session memory timeline when approved history has been read.
5. **Follow-up:** editable transcript-shaped query/city preview and exact-query approval, “Nimble searched” text, a factual “Based on” explanation, up to three source cards/descriptions, retrieval time, unknown fields, pause/resume, and completed/pending action timeline.
6. **Developer diagnostics:** read-only last sponsor operation metadata, sanitized RawTree query/template and bounds, rows returned, baseline eligibility/count, measured latency, retrieval time, cache/live status, and errors. This is a native screen, not a new backend route.

UI rendering, Flow collection, recomposition, and history reads must not create jobs or call tools. Debug fixtures are visibly labeled and isolated from real execution.

### Capture and on-device speech recognition

Use native AudioRecord with RECORD_AUDIO permission and private PCM/WAV files. Target 20–30 seconds, UI auto-stop at 30 seconds, and processing bounds of 2–60 seconds. These are engineering limits, not clinical thresholds.

Choose supported capture settings and produce validated mono 16 kHz PCM for ASR. If another capture rate is needed, explicitly resample and version the method; never just relabel it. Finalize a .part file and atomically promote it before issuing a durable complete-clip receipt. Partial recordings are not accepted input.

Keep stable session_id/clip_id, checksum, format, duration, provenance, and method version. Repeated admission of identical content returns the same receipt; reused IDs with different bytes are rejected. Replacement supersedes an earlier clip only after acceptance.

**Chunk limitation:** the MVP capture path finalizes a complete clip before transcription and export. It does not currently establish a durable ten-second chunk stream. Update memory after a committed summary/export acknowledgement and a successful history refresh; do not animate a growing RawTree count during recording or claim a `/session/chunk` response. An opt-in ten-second local-segment extension requires separately finalized segment IDs/checksums, exactly-once local admission, per-segment processing/export state, and interruption/deduplication tests before those live ticks can be demonstrated. Segments must not inflate the number of completed sessions.

Handle denied/revoked permission, missing microphone, capture failure, silence, empty/truncated input, timeout, storage exhaustion, backgrounding, and process death. Release the microphone on stop/interruption. Killed in-progress recording may need replacement. [Android recording guidance](https://developer.android.com/media/platform/mediarecorder).

**ASR:** embed whisper.cpp through a separate Android native module. Evaluate tiny.en or base.en for an explicitly English-language demo; pin the selected model/runtime and verify actual S24 speech. Other languages require an appropriate measured model. [whisper.cpp Android sample](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android).

Session 1 owns the ASR model manifest, private import/download, digest verification, atomic installation, and readiness implementation in audio/. Session 2's ModelRuntime owns Liquid artifacts. Define separate ASR setup/readiness ports in core/ during bootstrap so the shared setup UI can display and install both models without assuming either is already present.

Do not silently substitute ordinary SpeechRecognizer: its default service can send audio to servers, and EXTRA_PREFER_OFFLINE is not a guarantee. This build uses embedded recorded-file ASR. A labeled manual-text/debug path does not satisfy voice acceptance. [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer), [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent).

Run ASR off the UI thread and coordinate memory with Session 2. Missing models/failed transcription remain visible. Never generate fixture metrics from a real recording.

### Measurements and local baseline

Preserve these meanings; example values below are not expected demo results:

~~~json
{
  "duration_s": 24.1,
  "word_count": 45,
  "recording_wpm": 112.03,
  "pause_count": null,
  "energy_rms": 0.08,
  "pitch_mean_hz": null,
  "quality": "accepted",
  "quality_reasons": [],
  "data_origin": "consented_demo",
  "measurement_version": "android-pcm-v1"
}
~~~

Compute duration/normalized RMS from actual PCM, word count from local transcription, and recording_wpm from words divided by full recording duration. Define/version lexical counting. Pitch/pauses remain null in this MVP. Reject unusable/silent/no-speech input; ASR text alone is not proof of speech.

Emotion labels/confidence, vocabulary complexity, and a drift score are not implemented measurements in this revision. Display these as unavailable or omit them. Do not convert missing fields to zero, label RMS as a normalized emotion/energy score, or present recording WPM as articulation rate. Differences describe this recording; they do not diagnose cognitive decline.

Produce one versioned summary per completed session from accepted non-superseded clips. Pool words/duration for WPM; calculate pooled RMS from duration-weighted squared RMS. Transcript corrections update word-derived quantities and dependent summaries/comparisons while retaining acoustic measurements.

**Room is the local baseline and recovery authority.** Select the latest versions of the last five eligible completed prior sessions, excluding current, matching profile/task/method/provenance. Require two prior sessions for this prototype's comparison; otherwise return insufficient_history. Show values, mean, and delta. Zero variance means no standardized difference, never a risk score.

Export off, missing keys, and airplane mode must not prevent local history, comparison, or resume. Synthetic history is never a real speaker's established baseline.

### Visible RawTree memory

RawTree supplies a separate view of the history actually exported to it. Its comparison must be computed from returned eligible rows and labeled `From RawTree (N prior sessions)`; never relabel a Room comparison as a RawTree result. Query the preceding 56 days with bounded pagination/row limits, retain actual coverage and truncation information, deduplicate logical records, select the latest eligible revision, and exclude the current session from the baseline. Use matching profile/task/method/provenance and at least two prior eligible sessions. Report the number/date range actually used, rather than assuming eight weeks or eight sessions exist.

Show baseline and current values side by side for supported fields such as recording WPM and RMS, with units and descriptive deltas. Percent change is unavailable when the baseline is zero; omit unsupported rows or show “Not measured.” Identify the current session as its local accepted summary until its cloud export is confirmed. A cached RawTree snapshot keeps its original retrieval time and cache label. Missing network/keys, incomplete history, and insufficient baseline history remain visible.

The memory counter reports confirmed distinct records/data points and distinct sessions within its stated query scope. Define a data point as a persisted logical summary (or, only after segment support exists, a separately identified segment); multiple fields in one row are not multiple points. Pending/acknowledged exports and query-confirmed totals are separate states. A replayed event, corrected revision, or repeated read must not increase the logical count. When a bounded query is incomplete, label counts as loaded rows/known coverage rather than a total.

The timeline shows each eligible session's actual date, measured recording WPM, and approved snippet/keyword when available. It identifies the current session and the RawTree source. Do not substitute invented emotion labels or text for absent exported fields. A local-only timeline remains separately labeled.

### Persistence

Use versioned Room migrations for profiles, sessions, clips, jobs, actions, checkpoints, events, session_summaries, source_versions, consent_records, and outbox. Keep stable IDs, input_revision, state_version, timestamps, and provenance/method versions. [Room](https://developer.android.com/training/data-storage/room).

Session 1 implements WorkflowStore; Session 2 decides workflow transitions. Atomically commit a successful action's result, checkpoint/state update, and approved outbox projections. Never hold a DB transaction across inference/HTTPS or split that success bundle into independent commits.

## 6. Session 2 component — Local agent and recovery

Liquid proposes a tool. Deterministic Kotlin validates identity, consent, arguments, limits, permitted state, and completion, then executes it.

| Tool | Execution |
| --- | --- |
| get_baseline_summary | Local Room history |
| compare_recording_metrics | Local deterministic descriptive comparison |
| search_public_resources | Nimble request matching the exact approved transcript-shaped query, city, and revision |
| extract_public_page | Nimble extraction of a result from that approved search |
| request_user_input | Persist a bounded relevant missing-input request |
| finish_task | Complete only with required comparisons/evidence and satisfied constraints |

RawTree export is consent-controlled outbox work, not a model tool that can grant permission. Explicit memory refresh and post-delivery refresh use bounded scheduled reads and return persisted snapshots for the UI. Observation never dispatches those reads. Cloud history does not replace local recovery.

Allow one proposed action at a time and initially 12 actions per workflow revision. Bound model/HTTP retries; exhausted budgets remain visibly unfinished. Never fabricate facts after failure.

### States and revisions

~~~text
recording -> processing -> comparing -> awaiting_user_choice
                                      -> researching -> ready

Interruptions/blockers:
awaiting_input, waiting_network, waiting_retry, paused, agent_unavailable
~~~

Model readiness is separate component state. Stored clips can wait for missing models. ready means the current task is complete, not that a person is medically safe.

City, category, concern, or final query edits create a research revision and invalidate its dependent approval/search/extract work. Transcript corrections version word-derived measurements, comparisons, query proposals, and text export previews; any changed outbound text needs fresh approval. A changed metric used in the rationale also invalidates that proposal's revision. Reject stale revisions. Obsolete in-flight results cannot update current state or enqueue exports. Keep accepted clip identity intact; immutable committed results remain associated with their original revision.

Store full action results locally and compact references in checkpoints.

### Durable execution

1. Transactionally claim one eligible job and record its revision and planning attempt.
2. If no valid persisted plan exists, run bounded inference outside the transaction to propose an action, then validate it. A killed planning attempt may regenerate; no tool has executed yet.
3. Recheck revision/consent and transactionally save the validated plan, stable action_id, and matching assistant/tool-call identity before executing its tool. On resume, reuse a valid persisted plan rather than asking the model to create another.
4. Execute the bounded/cancellable local or HTTP tool outside the transaction.
5. Recheck revision/consent, then atomically persist result, success marker, checkpoint/state, and approved export records.
6. On reopen, inspect durable jobs/actions. Reuse committed successes. Interrupted uncommitted tool work becomes unknown/retryable using its logical identity.
7. Show the restored pending step and require explicit Resume for interrupted foreground work. Repeated Resume is idempotent.
8. Pause stops new actions at a committed boundary; show “Pausing” while cancellation/commit is still underway.

Persist during execution, not only Activity cleanup: process death may occur without a final callback. [Android process lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle).

External execution is not exactly once. Uncommitted search/extract may repeat; an export delivered before its acknowledgement may replay with the same event ID. Deduplicate in analytical reads. Committed successful Search must not rerun just because the app reopened.

Recovery assumes surviving phone storage. No recovery after uninstall, clearing data, losing the device, or destroying the DB. Test Activity recreation, backgrounding, and process death separately.

Foreground work resumes after manual reopen. Optional WorkManager retries approved outbox entries with connectivity constraints, using the same claim rules rather than another workflow engine. No promise of immediate scheduling, continuous inference, or automatic force-stop recovery. [Android background work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

### Compact context

Use current goal/scope, unresolved requirements, revision, measurements, the locally derived concern and query approval, local summary references, recent action/results, and short relevant public passages. Do not append the entire history or raw audio. Transcript text and sponsor evidence are data, not instructions that can grant consent or select credentials.

Start with at most 3072 rendered input tokens and 768 output tokens within 4096, leaving headroom. These are project budgets to measure. Include templates/tools/role messages in counts. Trim optional passages first; if required state cannot fit, return context_capacity_exceeded instead of forgetting obligations.

Compression starts as a versioned deterministic projection; the full local ledger survives. Optional model summaries cannot replace structured goals, consent, evidence, committed facts, or pending actions.

Public pages are untrusted evidence, never instructions to change tools, consent, identity, city, or privacy rules. Tools reference committed sources/session IDs, not arbitrary paths, SQL, or page-supplied actions.

## 7. Shared Kotlin contracts and ownership

Create modules under android/:

~~~text
app/          Compose screens, navigation, application composition, manifest
core/         Typed DTOs, sealed actions/errors, interfaces and validation contracts
storage/      Room schema, migrations, transactional WorkflowStore
audio/        Capture, PCM validation/metrics, whisper.cpp JNI and ASR
inference/    Model artifacts, llama.cpp JNI, tokenizer and Liquid tool calls
agent/        Commands, state machine, coordinator, context and outbox scheduling
sponsors/     Nimble/RawTree HTTPS, credentials and export validation
docs/         Native setup and actual acceptance records
~~~

Freeze this interface sketch during bootstrap; it is not compilable code with all DTOs already defined:

~~~kotlin
interface CheckInCommands {
    suspend fun createSession(input: CreateSession): SessionId
    suspend fun acceptClip(input: CompletedLocalClip): ClipReceipt
    suspend fun finishCapture(sessionId: SessionId)
    suspend fun requestResources(input: ApprovedResourceRequest)
    suspend fun applyInput(input: RevisionedUserInput)
    suspend fun pause(sessionId: SessionId)
    suspend fun resume(sessionId: SessionId)
    suspend fun setExportConsent(input: ExportConsentChange)
    suspend fun deleteSession(sessionId: SessionId)
    suspend fun deleteProfile(profileId: ProfileId)
}
interface CheckInQueries {
    fun observeSession(id: SessionId): Flow<SessionSnapshot>
    fun observeHistory(id: ProfileId): Flow<List<SessionSummary>>
    fun observeOpenFollowUps(id: ProfileId): Flow<List<SessionSnapshot>>
    fun observeReadiness(): Flow<ComponentReadiness>
}
interface LocalAgent {
    suspend fun propose(checkpoint: AgentCheckpoint): ProposedAction
}
interface ModelRuntime {
    val status: StateFlow<ModelStatus>
    suspend fun install(artifact: ApprovedModelArtifact)
    suspend fun load(modelId: ModelId)
    suspend fun unload()
}
interface LocalAudioProcessor {
    suspend fun process(clip: CompletedLocalClip): AudioResult
}
interface PublicResourceClient {
    suspend fun search(request: ApprovedResourceRequest): SearchResult
    suspend fun extract(source: ApprovedSource): SourceEvidence
}
interface ApprovedHistoryClient {
    suspend fun append(event: ApprovedExport): DeliveryReceipt
    suspend fun query(request: BoundedHistoryQuery): HistoryResult
}
interface SponsorCredentialSettings {
    fun observeStatus(): Flow<List<CredentialStatus>>
    suspend fun setCredential(service: Sponsor, value: SecretValue)
    suspend fun clearCredential(service: Sponsor)
}
~~~

Define SponsorCredentialSettings in core/; Session 3 implements it. Expose only configured/missing/error metadata to the UI, with no token-reading method. SecretValue must never be logged or serialized into app state; only the sponsor adapter's internal credential provider can retrieve a token for an authorized request.

WorkflowStore must expose atomic command application, job claim, plan persistence, success/result/checkpoint/outbox commit, failure recording, startup recovery, baseline reads, outbox claim/acknowledgement, consent revocation, and deletion. Test rollback/uniqueness. Queries must not run external work.

Profile deletion first cancels its execution and transactionally invalidates all associated jobs/actions/outbox entries while deleting local records; clean associated files with restart-safe cleanup. Late results must not recreate deleted state. This does not imply deletion of previously delivered cloud records.

SessionSnapshot includes IDs, phase, state/input versions, execution_mode, metrics, comparison, sources/resources, pending action/input, errors, provenance, consent, and cloud-sync counts. Add a versioned proposed/approved query with concern, rationale, city, and source transcript revision; optional approved export text; and a RawTree memory snapshot with count scope, coverage, distinct counts, eligible history/baseline, retrieval time, cache status, and typed errors. Distinguish real_on_device from synthetic_fixture; configuration is not verified model readiness.

ApprovedResourceRequest must carry the final query rather than reconstructing it from a category at dispatch. ApprovedExport must distinguish measurement consent from optional snippet/keyword consent and retain the exact approved values. BoundedHistoryQuery/HistoryResult must carry profile/provenance/method filters, window/limits, truncation, logical record identities, and version information. Freeze concrete DTO names and bounds through Session 1's shared contracts; adapters must not silently discard these fields.

SourceEvidence includes title, nullable description, source URL, retrieval time, content hash, evidence/version identity, supporting passages, nullable facts, and verification status. Preserve description into the UI. “Source backed” does not establish service availability.

Use immutable typed DTOs, sealed actions/errors, UUIDs, enums, bounded strings/lists, explicit nulls, and versioned serialization. Controller code assigns identity; model output cannot create action IDs or switch profiles.

### Bootstrap order

1. Session 1 creates root Android configuration, module shells, and core contracts.
2. Sessions 2/3 review the contracts; Session 1 records a shared bootstrap checkpoint before parallel implementation. Research can proceed while waiting.
3. Session 2 proves minimal on-device model loading/tool round trip before substantial UI polish. Session 1 independently proves local capture/ASR.
4. Session 3 implements frozen ports; Session 1 wires concrete implementations in app/. Missing components show unavailable, not fake success.
5. Session 1 owns root Gradle/version catalog, manifest, and core/ edits. Other sessions coordinate changes instead of silently editing shared definitions.

Concrete modules depend on core/; app/ composes them; agent/ receives ports. Sponsor adapters neither mutate Room nor advance workflows. Each session owns its module build/native files after bootstrap.

## 8. Session 3 component — Optional sponsor tools

Use native HTTPS clients with bounded request/response sizes, timeouts, cancellation, typed errors, and redacted logs. Keep TLS verification enabled. Never retry authorization failures indefinitely or invent successful fallback results.

### Nimble

Build a targeted query locally after Whisper completes, then call Search from the phone only for the approved exact request. The existing Python sketch is behavior guidance; implementation is phone-local Kotlin, not a FastAPI service.

1. Inspect the actual transcript for a bounded, relevant stated concern such as forgetting belongings, difficulty finding words, loneliness, or caregiver support. Preserve the distinction between a stated concern and an inferred diagnosis; negated statements must not become affirmative concern evidence.
2. Convert that concern into a public-resource phrase. For example, “I keep forgetting where I put things” can propose `support for forgetting everyday items near San Francisco CA` when that city has been selected. Different stated concerns must produce different queries even with the same city. Do not claim transcript personalization when the builder used only a category fallback.
3. Attach only available descriptive evidence to the local rationale: the selected concern and a measured comparison, if one exists. Changes in recording WPM/RMS may provide context but cannot imply dementia, anxiety, or another condition. Emotion/pause/vocabulary branches remain unavailable until separately implemented and evaluated; they must not replace transcript use.
4. When no supported concern is found, produce an explicitly labeled category-based fallback for user review. Empty/invalid transcripts must not manufacture evidence. Let the user edit the concern/query and city, then approve the exact final string and revision before dispatch.
5. Persist the query, builder version, concern/rationale, input revision, and approval locally. Send only the approved query and allowed search parameters. Do not send the rationale, source transcript, baseline rows, or emotion fields as additional request context.

~~~http
POST https://sdk.nimbleway.com/v2/search
Authorization: Bearer <Nimble credential>
Content-Type: application/json

{"query":"support for forgetting everyday items near San Francisco CA","country":"US","max_results":3,"full_content":false}
~~~

The sample query is illustrative, not a hardcoded city or required search term. Preserve the dispatched query, result title, URL, description, and request_id. Categories caregiver_support, respite_care, and caregiver_education remain useful fallback phrases, but do not override a reviewed transcript-derived concern. Use the current documented Search v2 endpoint above; do not copy the older `api.webit.live` example or its different field names without a verified account/API requirement. [Nimble Search](https://docs.nimbleway.com/api-reference/search/search).

The follow-up screen shows `Nimble searched: “<actual dispatched query>”`, a local `Based on` explanation, and up to three returned results beneath it. Quote the actual selected concern or show a supported metric delta with its source/count; never display the sample “pauses increased 133% · sad (83%)” unless those measurements exist. Before dispatch label the string as proposed/approved, not searched; after a failed request show the failure instead of implying results arrived.

~~~http
POST https://sdk.nimbleway.com/v2/extract
Authorization: Bearer <Nimble credential>
Content-Type: application/json

{"url":"<selected public search-result URL>","render":true}
~~~

Check completion/target status where provided. Parse markdown or clean returned HTML; a task_id alone is not completed evidence. Keep retrieval time and content identity. [Nimble Extract](https://docs.nimbleway.com/api-reference/extract/extract).

Only extract relevant public HTTP(S) organization pages returned by the approved search. Reject userinfo, localhost, private/link-local addresses, unsupported schemes, and disallowed redirects. Phone checks cannot guarantee Nimble's remote DNS/redirect behavior; validate returned URLs and document this limit.

Every displayed fact needs a supporting passage reference. Missing phone/address/hours stay null; do not infer availability. Separate search candidates from extracted evidence. Label cached evidence/timestamps. Refreshes create immutable content versions, preserving old fact-to-source associations. Deterministic changed-page fixtures must be labeled simulated updates.

### RawTree

RawTree stores only selected exported data and supplies the visible cloud memory view. Room remains authoritative for the local baseline, queue claims, actions, checkpoints, and recovery. RawTree comparisons use RawTree-returned rows and carry their own source, coverage, and retrieval metadata.

Port the existing adapter's REST contract:

~~~http
POST https://api.rawtree.com/v1/tables/<allowlisted_table>
Authorization: Bearer <RawTree credential>
x-rawtree-database: <configured database>
Content-Type: application/json

<one approved projection or a bounded array>
~~~

~~~http
POST https://api.rawtree.com/v1/query
Authorization: Bearer <RawTree credential>
x-rawtree-database: <configured database>
Content-Type: application/json

{"sql":"<fixed SELECT template with bounded validated identifiers>"}
~~~

RawTree's official introduction confirms ingestion/query endpoint forms. Detailed API/auth pages could not be retrieved while preparing this revision. Recheck database-header behavior, credential scope, response shape, and account access during live Android integration. The current adapter's expected data row envelope is a contract to validate, not a newly verified API guarantee. [RawTree introduction](https://rawtree.com/blog/introducing-rawtree), [API reference](https://rawtree.com/docs/reference/api), [authentication](https://rawtree.com/docs/reference/authentication), [query guide](https://rawtree.com/docs/guides/query-data).

Allowlisted tables and maximum permitted projections:

- clearline_events: stable export/event ID, pseudonymous session reference, enum event/status, revision, timestamp, provenance.
- clearline_session_summaries: stable logical summary/export identity, pseudonymous profile/session references, input/summary revision, timestamp, provenance, separately selected descriptive metrics and method/version fields; optional separately approved transcript_snippet (at most 200 characters) and top_keyword (at most 40 characters).
- clearline_checkpoints: selected phase/completed/pending counts and references; no goals, prompts, transcripts, private input, or full checkpoint.
- clearline_resource_versions: approved public facts, evidence identity, URL, retrieval time, and supporting public passages.

Validate immutable ApprovedExport values at enqueue and dispatch, including current consent/revision. Never expose arbitrary SQL to Liquid. A database selector does not establish per-user authorization or narrow credential scope.

Extract a candidate snippet and keyword from the actual accepted transcript locally and show them in the export preview. A snippet can begin with the first 200 characters, but users must be able to omit or edit it before approval. Keep absent fields absent/null; do not populate emotion_label or drift_score from fixtures or use `0.0` to mean an uncomputed score. Later corrections create explicit approved revisions with stable logical identity, not silent changes to already sent content.

The local outbox owns IDs/delivery state. Cloud ingestion is not a queue lease, uniqueness guarantee, compare-and-swap, or exactly-once primitive. Deduplicate repeated event IDs and select latest eligible versions in bounded queries. Cloud records must not overwrite local checkpoints or be counted twice in local history.

Implement fixed, bounded history/count/baseline query templates. Scope by pseudonymous profile and eligible provenance/method, select the latest summary revisions, exclude current from the prior-session baseline, and preserve coverage/truncation. After a successful delivery acknowledgement, schedule a history refresh through the coordinator; only returned rows justify a query-confirmed count or timeline. Eventual visibility must show pending confirmation rather than a fabricated increment. Corrections and replayed IDs must not add a new logical session or point.

Expose read-only native diagnostics for the last RawTree request: template name or sanitized SQL, redacted/bounded filters, returned row count, deduplicated eligible session count, measured latency, selected baseline values, retrieval time, and typed failure/cache state. A local diagnostics screen may be named `RawTree debug`; there is no `/debug/rawtree` HTTP server in the Android path. Use the SQL contract shown above after validating it against the account; do not label a fabricated Tinybird pipe query as an executed RawTree request. Keep keys and unapproved transcript text out of diagnostics/logs.

**Live gates:** from the S24, explicitly approve a synthetic event write, query that exact ID from the intended database, and complete Nimble Search plus Extract using an approved transcript-shaped query. Verify optional text-field export with a synthetic canary and approved exact values; then verify history/count/baseline output against known distinct revisions and show the real query diagnostics. Missing keys/network are unavailability. Synthetic smoke-test exports still require approval.

## 9. Three Codex implementation sessions

All sessions must read this revision fully. These commands replace the previous web/backend split.

### Session 1 — Android/mobile app

**User command:** Run Session 1

> Build the native S24 app shell, microphone/audio flow, Room persistence, and Compose UI.
>
> Own android/app/, android/core/, android/storage/, android/audio/, root Android Gradle/version configuration, manifest, and associated tests. Create the shared module/contracts bootstrap first and coordinate it with Sessions 2 and 3.
>
> Implement real AudioRecord and embedded whisper.cpp on the phone, complete-file admission, measurements, provenance, local profiles/history, reopenable prior follow-ups, consent/deletion, backup exclusions, and transactional WorkflowStore.
>
> Add exact-query review, a factual query rationale, three returned result cards, separate snippet/keyword export previews, and read-only RawTree memory views: confirmed/pending counts, sourced baseline/current values, session timeline, and native diagnostics. Persist revisioned query approvals and memory snapshots through shared contracts. Show unavailable fields and actual coverage; do not invent emotion, drift, pitch, or pause values. The current complete-clip path must not claim live ten-second recording increments.
>
> Build model install/load controls against Session 2's ModelRuntime and sponsor settings against Session 3's credential interface. Do not implement laptop hosting, a FastAPI-dependent browser wrapper, Liquid HTTP inference, or sponsor calls in UI code.
>
> Wire concrete implementations in app/ when available. Keep fixtures labeled and debug-only. Missing runtime dependencies remain visible. Verify native audio and Liquid libraries coexist in the APK.
>
> Run unit/Room/UI checks and real S24 capture/transcription when hardware is available. Report files, reproducible build/install steps, retention, real versus simulated tests, and blockers. Web-preview success does not prove Android execution.

**Deliverable:** installable native UI with local capture/transcription/storage, model-loading controls, and working component boundaries.

### Session 2 — Local Liquid agent

**User command:** Run Session 2

> Own android/inference/, android/agent/, their tests, and model/runtime documentation. Use frozen core contracts and Session 1's transactional storage.
>
> Embed pinned llama.cpp via NDK/JNI. Load Liquid GGUF files from private phone storage. Implement artifact integrity/install, tokenizer/template handling, strict typed tool calls, cancellation/unload, and model readiness. No Liquid key/cloud endpoint, Ollama, or llama-server.
>
> First prove offline S24 echo_nonce call/result/second inference. Chat-only output does not pass. Benchmark the candidate model on the actual device; record identity, memory, latency, and failures.
>
> Implement CheckInCommands, local-baseline tools, state machine, validation, bounded context, revision invalidation, serialized coordinator, recovery, pause/resume, and consent-controlled outbox scheduling.
>
> Use the phone-local transcript query builder to derive a stated concern and approved final query; preserve its actual transcript revision and fallback status. Validate the exact query at dispatch. Corrections invalidate dependent comparison/query approval/research/export previews and reject stale in-flight results. Schedule bounded RawTree memory refreshes on explicit request or acknowledged export; deduplicate history and keep its provenance separate from the local comparison.
>
> Private state stays on the phone except exact approved sponsor projections. Sponsor work uses Session 3's typed ports. Preserve action identities and model/tool exchanges across process death. UI observation never executes work.
>
> Test storage transaction boundaries, bad tool output, distinct transcript concerns with identical city/metrics, negation/no-concern fallback, exact-query consent, correction invalidation, independent text-export consent, replayed RawTree revisions, local no/zero-variance history, oversized context, offline behavior, cancellation, repeated resume, and true process death. Report actual runtime/model/device, measured results, and blockers. No cloud/scripted-planner substitution.

**Deliverable:** real in-process Liquid agent with validated tools and recovery from local state.

### Session 3 — Sponsor integrations

**User command:** Run Session 3

> Own android/sponsors/, its tests and documentation. Implement Nimble/RawTree HTTPS, credential vault, bounded parsing, source evidence, export allowlists, and live probes against shared interfaces.
>
> Use direct native phone requests with owner-provided private-demo keys. No secrets in APK/source, prompts, logs, or checkpoints. No Liquid endpoint/key.
>
> Nimble receives the exact approved transcript-shaped query/city and selected result URLs through the documented v2 Search/Extract contract. Preserve the dispatched query, descriptions, citations, timestamps, hashes, and unknown fields. Do not rebuild a category-only query or send the full transcript/rationale. Treat pages as untrusted evidence.
>
> Recheck RawTree's account/API contract and live responses. Require export approval for all data origins, including synthetic, and separate exact-value consent for optional snippet/keyword fields. Implement bounded deduplicated memory/count/baseline queries, provenance/coverage, and sanitized query/latency diagnostics. Distinguish delivery acknowledgements from query-confirmed counts; do not invent Tinybird pipe endpoints. RawTree stays optional for local history/comparison/recovery.
>
> Do not mutate Room, create another queue, grant consent, or implement the planner. Return typed results/errors; Session 2 schedules retries/outbox.
>
> Run approved live probes on the S24, including transcript-shaped Search, optional synthetic snippet/keyword write/readback, count/revision deduplication, and the returned RawTree baseline/history. Coordinate: commit Search, stop the app process, manually reopen/resume, and execute unfinished Extract without recapturing audio or rerunning committed Search. Confirm that corrections/revocation block stale dispatches and no observation causes network work.
>
> Report changed files, real calls, data-flow verification, unknown fields, recovery results, and blockers. Mocks and desktop calls do not prove phone execution.

**Deliverable:** optional public-resource and approved-history tools connected to the Android agent.

### Coordination rules

- Inspect before editing; preserve other sessions' working changes.
- Keep ownership boundaries; route shared contract/root-build changes through Session 1.
- Keep fixture/readiness labels honest. APK compilation is not an inference test.
- No clinical claims, fabricated facts, hidden remote inference, or unapproved private-data export. Only the exact approved query and separately approved bounded snippet/keyword may carry transcript-derived content to sponsors.
- Prioritize real native tool calling, local ASR, and recovery before polish.
- No unrelated integrations, email, bookings, payments, or automatic clinical referrals.
- Record actual native acceptance results under android/docs/, including failures; never replace a failed hardware gate with a mocked pass.
- Handoff: files, checks, exact hardware/runtime, limits, and dependencies.

## 10. Acceptance gates — definition of done

Every Android gate starts as **NOT RUN** for this revision. Legacy desktop tests cannot satisfy it.

| Gate | Evidence required |
| --- | --- |
| Native build | Reproducible arm64 APK with pinned dependencies, installed/launched on actual S24. |
| No laptop execution | Operates with laptop services stopped and USB disconnected after installation; no runtime pairing/localhost endpoint. |
| Offline private core | Models installed, network off: real recording, local ASR, Liquid local tools, eligible local comparison, reopen/resume. Typed fixtures do not satisfy this. |
| Liquid tools | Actual nonce round trip plus valid/invalid tool cases with the pinned in-process runtime/template. |
| Audio | Real S24 speech, finalized PCM, local transcription, silence/truncation/permission/missing-model handling. |
| Device/model limits | Measured load/turn time, tokens, memory, repeated runs, ASR/LFM coexistence, low storage, cancellation/unload. |
| Local history | Eligible history works with export off; no/zero-variance cases; provenance separation; older follow-ups reopen. |
| Observation/deduplication | Recomposition/Flow reads do not run tools; duplicate admission and repeated Resume preserve identities. |
| Corrections | City/concern/query edits invalidate research approval; transcript edits invalidate dependent word metrics/comparison/query/export text; stale in-flight results are rejected; changed outgoing values require fresh consent. |
| Transcript-shaped query | Same city/metrics plus different supported transcript concerns produce different queries; negation, empty text, irrelevant text, and explicit fallback preserve honest rationale; exact approved query reaches the request. |
| Nimble | Approved real phone Search/Extract on v2 endpoints; actual dispatched query, rationale, and up to three descriptions/citations reach UI; unknowns stay unknown; unapproved/stale query cannot dispatch. |
| RawTree | Approved phone write/readback to intended DB; optional snippet/keyword matches separately approved bounded values; denied/revoked export stays blocked. |
| RawTree memory | Known repeated IDs and corrected revisions yield stable distinct counts; latest eligible prior rows drive the sourced table/timeline; current session excluded; no/zero-baseline and incomplete/cached coverage are honest. |
| Sponsor visibility | Native diagnostics show the actual sanitized query/template, returned rows, eligibility, and measured latency; confirmed count changes require returned cloud data; complete-clip recording does not simulate ten-second increments. |
| Process death | Search commits before Extract; kill app, reopen/resume same session; accepted clip and completed Search IDs unchanged; then Extract. |
| Unknown interrupted work | Kill during uncommitted work; retain logical identity and safely retry a read without exactly-once claims. |
| Android lifecycle | Separate Activity recreation, backgrounding, process kill, force-stop/manual reopen; no onDestroy dependency. |
| Privacy | Synthetic-canary network/log inspection: only exact approved query and optional bounded snippet/keyword leave in their allowed fields; no raw audio/full transcript/private context or inference HTTP; text-consent denial/revocation, backup exclusions, credentials, deletion/outbox cancellation verified. |
| Context/evidence | Long history does not grow prompts unboundedly; page instructions cannot alter consent/tools; facts retain source version/passages. |
| Failures/honesty | Offline external work waits visibly; 401/429/timeouts/bad JSON/missing keys fail honestly; fixtures/cached/live output remain distinct. |

For baseline demos, collect at least two eligible prior phone check-ins or show a separate labeled synthetic profile. Do not use synthetic history as the live speaker's baseline.

For the RawTree view, export and query eligible prior sessions with consent. Show the actual span/count if eight weeks have not been collected. Verify baseline numbers against the returned rows and show retrieval time; no baked-in baseline or fixture row may be labeled live RawTree memory.

The interruption demo must preserve the phone DB. Reinstalling with data deletion is not recovery. A developer cable may send a test kill command, but processing stays on the phone; separately demonstrate cable-disconnected use.

## 11. Three-minute demonstration and pitch

Prepare models and complete gates beforehand. Use measured device timings to determine whether the sequence fits three minutes. Label prerecorded fallbacks.

**0:00–0:25 — Story and architecture.** “A check-in is only the beginning. The follow-up needs to survive the interruption.” Show the S24's loaded model identity.

**0:25–1:00 — Offline core.** With network off, record a consenting clip, transcribe on the phone, and show a real local tool action/saved summary and locally proposed concern query. Measurements are descriptive only. A RawTree view shown offline is labeled cached with its retrieval time.

**1:00–1:30 — Chosen external work.** Enable connectivity, show the transcript-shaped query and rationale, approve its exact text/city, and show the local model choosing Nimble Search. Separately approve selected RawTree measurements and optional snippet/keyword. After acknowledged delivery and refresh, show the returned memory count.

**1:30–2:15 — Interruption.** After Search commits and before Extract, stop the app process. Reopen, tap Resume, and show unchanged accepted clip/completed Search with pending Extract.

**2:15–2:45 — Evidence and memory.** Show the actual Nimble query and up to three returned results. Show RawTree's returned baseline/current table and history snippets with actual session count/span, retrieval time, and missing fields. Raw audio/full transcripts remain local; identify the small approved text projections that were sent. Open native query diagnostics when useful.

**2:45–3:00 — Close.** “The agent brain runs locally on your phone. Your sensitive voice history never needs to be sent to a cloud model. When life interrupts, ClearLine keeps the next step ready.”

Longer pitch: “ClearLine helps families carry a check-in through to a useful next step. A short recording is processed on the phone, where a Liquid agent maintains the relevant history and unfinished work. What was said in this call shapes the resource search you approve. RawTree makes the history you chose to export visible: prior sessions, approved snippets, and the measurements behind its comparison. When you pause or the app stops, ClearLine resumes from a local checkpoint. You choose what, if anything, is exported.”

Do not claim that removing RawTree eliminates all comparisons or recovery: the local core works offline. Do not claim a clinical drift score or eight weeks of real memory until those capabilities/data exist. The defensible demo claim is that the displayed RawTree comparison comes from queried exported history, and the displayed Nimble query is shaped by this call's stated concern.

## 12. Native configuration and status

The Android target has no runtime Python environment, FastAPI server, Liquid base URL, model-server alias, or laptop pairing code.

Record application ID, supported SDK range, tested target SDK/device OS, ABI, Gradle/Kotlin/NDK/CMake versions, pinned llama.cpp/whisper.cpp commits, and licenses/notices. Choose compatible versions using the actual bootstrap build and verify native packaging on-device.

Model manifests contain repository, immutable revision, filename, size/digest, model type, quantization, template/tokenizer identity, language scope, and license reference. Download/import is a setup step; offline readiness requires every artifact to verify.

Settings include local model selection, optional sponsor enablement/credentials, RawTree database, approved research city, and per-session measurement/snippet/keyword export choices. Review exact outgoing query/text values in the session, not only a global settings toggle. Sponsor endpoints are allowlisted; credentials stay in the vault. Never copy .env into Android assets or BuildConfig.

After gates pass, write android/docs/ACCEPTANCE.md with actual evidence and measured limitations. Until then, this is a native implementation in progress. Rewriting this specification neither installs Liquid nor completes the Android migration.
