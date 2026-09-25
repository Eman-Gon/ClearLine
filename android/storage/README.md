# Local Room authority

`RoomWorkflowStore.open(context)` places the database under `noBackupFilesDir/database/`.
The audio module writes finalized files under `noBackupFilesDir/audio/`.
The application must share one store and cancel execution before deletion.

All persisted DTOs carry versioned serialization, identity, provenance and revisions.
Room schema v2 has an explicit v1→v2 migration for deletion tombstones and the file cleanup queue. No destructive fallback is enabled.

The coordinator decides workflow transitions. `CommandMutation` writes a snapshot,
checkpoint, jobs, optional summary and consent-checked export projections in one
transaction. `commitSuccess` includes the immutable action result and exact tool
exchange, completes the claimed job and creates the coordinator's next job atomically.
No inference or HTTP call runs inside a database transaction.

Admission independently verifies canonical private path, complete PCM WAV header,
duration and SHA-256. Clip IDs cannot name different content. Content duplication
within a session returns the original receipt. The durable receipt is retryable after
audio deletion. Acceptance creates a deterministic processing job and clears an audio
replacement blocker. Replacing summarized input advances dependent revisions unless
the coordinator has already invalidated that summary.

Recovery pauses pending/claimed foreground jobs, marks uncommitted plans unknown,
and preserves successful action IDs. It requires explicit Resume. Resume can requeue
eligible jobs without creating new action identities. Export delivery may replay if
the process stops after delivery but before acknowledgement; the export ID stays the same.

Export checks apply equally to synthetic data. Both enqueue and dispatch validate
identity, current consent/revision and selected fields. An optional reviewed transcript
snippet/keyword additionally requires `TRANSCRIPT_SNIPPET`, an exact match to the
reviewed approval, and measurements approval. Revocation cancels unsent work; an
already transmitted request cannot be recalled. Enabling approval does not scan old
sessions to create exports.

Completed processing schedules audio removal; retryable incomplete input remains.
Call `recoverInterrupted()` on process startup before capture, then
`cleanPendingAudioFiles()`; also clean after terminal processing/deletion. Startup
queues orphan and partial audio files, preserving admitted unprocessed inputs.
Session/profile deletion atomically removes rows and writes tombstones plus cleanup
paths. Late commits cannot recreate deleted data. Filesystem cleanup is idempotent
and survives another process death. This is logical deletion, not forensic erasure.

Local baseline selection takes the latest summary versions of the five most recent
eligible prior check-ins, matching profile/task/measurement/lexical/provenance, and
requires two prior sessions. Invalidation excludes obsolete summaries until a new
summary commits. WPM pools words over full recording duration; RMS pools squared
energy by duration. Zero variance produces no standardized difference.

Run `gradle :storage:testDebugUnitTest :core:test --configure-on-demand` with the pinned
Android SDK/build tools installed. Tests use Robolectric and synthetic local fixtures;
they do not establish S24 audio, native inference or actual process-death behavior.

Without SDK setup, `gradle -p tools/host-storage test --console=plain` from `android/`
runs the checked-in host harness against the real Room runtime and KSP compiler.
On September 25, 2026, this passed 16 storage tests and 24 core tests. The storage
tests cover success-bundle rollback, logical-job uniqueness rollback, admission
deduplication, file retention/cleanup, rejected-clip replacement, revision rejection,
same-plan recovery, cross-scope plan selection, consent/revocation, separate reviewed
text approval, latest-five baseline/correction behavior and the v1→v2 migration.
