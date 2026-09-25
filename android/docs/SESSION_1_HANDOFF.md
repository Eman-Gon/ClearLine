# Session 1 native fix — September 25, 2026

This records implementation and observed evidence for specification 3.1. It does
not certify an installed Android build or a successful phone demonstration.

## Implemented

- Native Compose Setup, Home/history, Check-in, Summary, Follow-up and read-only diagnostics.
  History/profile selection survives restart; opening history, recomposing, and collecting
  state do not issue coordinator commands. Explicit controls approve recording, search,
  export, resume, model setup and deletion.
- Real AudioRecord PCM16 mono 16 kHz capture, private finalized WAV files, checksum-backed
  admission, background interruption and 30-second stop. Embedded pinned whisper.cpp
  transcribes accepted files; no cloud recognizer or fixture metric fallback exists.
- Separate verified Whisper/Liquid setup and loaded readiness. App composition shares
  one model arbiter and unloads the other model before execution.
- Room v2 with an explicit v1→v2 migration, normalized identity/revision indexes,
  atomic action/result/checkpoint/outbox commits, saved plan identities, recovery pause,
  idempotent admission, versioned summaries, deletion tombstones and persistent file cleanup.
- A local baseline from the latest five eligible prior summaries, matching profile,
  task, methods and provenance. At least two prior summaries are required. Cloud export
  is optional for local history, baseline and unfinished-work recovery.
- Editable exact Nimble query, rationale bound to the approved transcript, and committed
  query/source display. Optional RawTree snippet/keyword have separate editable previews
  and input-revision/consent checks. Revocation removes unsent work and cached text.
- RawTree returned counts, prior/current comparison, bounded timeline and sanitized
  diagnostics. UI distinguishes acknowledgements, query-confirmed totals, cached data,
  insufficient history and missing values. Complete clips do not pretend to be ten-second
  streamed segments.
- Manifest backup/device-transfer exclusions, private no-backup storage, Keystore-backed
  sponsor credentials, cleartext-disabled networking and protected credential-entry UI.

The shared bootstrap exists in repository checkpoint `6f355cb` (native project,
module shells and initial contracts). Later contracts were coordinated across the three
sessions, including independent comparison/research revisions, per-step jobs, exact text
approval and source-derived memory. The current files, rather than that initial checkpoint,
define the final integration API.

## Evidence actually obtained

| Check | Observed result | Limit |
| --- | --- | --- |
| Core contracts/query/memory math | 24 host tests passed | Synthetic inputs, JVM |
| Audio PCM/install logic | 9 host tests passed | No microphone or loaded ASR |
| Whisper native bridge | Real pinned library built on Mac arm64; 10 missing-model/cancel/unload cycles passed; 5 JNI exports, no public ggml symbols | No Android ELF or speech inference |
| Full production Kotlin source compatibility | All seven modules compiled using Kotlin/Compose 2.2.10, actual AndroidX AARs and Android 15 API jar | No resource/manifest/DEX/NDK/APK build |
| App dispatch authorization | 8 host tests passed | Read-only proxy store; no sponsor network traffic |
| Room generation and transactions | 16 tests passed with actual Room 2.7.1 KSP generation and Robolectric | Host SQLite, not a killed phone process |
| Agent/inference and sponsors | Separate session reports in their module READMEs | Doubles/host probes are explicitly identified there |
| UI instrumentation | Written for observation, exact search approval and snippet approval | Not run; Android build/device required |
| Full Android APK | Debug APK built; all 170 module unit tests passed; signing, native exports, ABI and 16 KiB alignment passed | Host build/package verification; no phone runtime test. See [build record](APK_BUILD.md) |
| S24 acceptance | Not run | No S24 attached to adb |

Focused app authorization tests and Room checks are reproducible through the checked-in
`tools/host-typecheck` and `tools/host-storage` projects. Do not add overlapping suites together as a unique
test total: several temporary harnesses include the same 24 core tests.

Host compilation found and fixed public Android API incompatibilities in directory fsync.
A static build review also aligned the storage minimum SDK with the API 26 application.
These earlier host checks reduced integration risk. The subsequent [full Android build](APK_BUILD.md) established APK assembly and packaging; phone behavior remains unverified.

## Required Android and S24 acceptance

SDK license approval, toolchain provisioning and the full build are complete. The
build log, APK SHA-256 and packaging checks are recorded in [APK build evidence](APK_BUILD.md).
Inspect future APKs for only the intended ABI,
both JNI libraries, isolated ggml symbols, no bundled weights, no secrets and no desktop
server addresses. Run unit tests and instrumentation, including the native UI tests.

Record each phone check below as PASS/FAIL with APK/model identity, device/OS, actual
steps and evidence. All currently remain **NOT RUN**:

| Area | Required evidence |
| --- | --- |
| Capture | Permission deny/revoke, 30-second automatic completion, foreground/background interruption, real level meter, release of microphone, low storage, silent/no-speech rejection |
| ASR | Actual consenting English speaker transcribed offline; duration/RMS/WPM from accepted PCM; corrected transcript versions only word-derived measurements |
| Liquid | Offline unpredictable nonce tool-call/result/second inference; load/turn timings, token counts, memory, repeated-run thermal behavior and cancellation |
| Coexistence | Whisper and Liquid libraries coexist in one APK; load/unload transitions complete without duplicate symbols, crashes or overlapping use |
| Recovery | Search committed → force-stop → manually reopen → explicit Resume → unfinished Extract; no recapture or committed Search replay |
| Local persistence | Restart retains profiles/history/summary versions; duplicate admission, replacement, rollback and deletion behave on device |
| Optional sponsors | Approved phone Search/Extract and RawTree selected write/readback; missing keys/network remain visible; revisions/replays do not inflate counts |
| Privacy | Synthetic canary traffic/log inspection; exact query and optional approved snippet only; correction/revocation blocks stale dispatch; backup/transfer exclusions and key handling verified |

Audio lives under `noBackupFilesDir/audio/`, models under `noBackupFilesDir/models/`,
and Room under `noBackupFilesDir/database/`. Successful terminal audio processing queues
audio deletion; admitted unfinished input remains for retry. Orphan/partial files are
cleaned on startup. Other local records remain until session/profile deletion. Delivered
cloud data cannot be recalled by local deletion or consent revocation.

## Current build status

The user explicitly accepted Google's Android SDK agreement. JDK 17, Gradle, the Android
platform, build tools, NDK and CMake are installed. The toolchain and Android build copy
have been moved to the user-selected T7 SSD and verified; the original repository remains
the source authority. Full Gradle unit tests and `:app:assembleDebug` passed on the
external volume, along with APK signing and native packaging checks. No physical S24
was connected, so installation and device acceptance remain **NOT RUN**. The user's
friend can install the provided APK using the [phone guide](PHONE_TESTING.md), without
building it. The [toolchain guide](TOOLCHAIN.md) gives the storage layout and rebuild commands.
