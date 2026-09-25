# Native audio module

Implemented native microphone capture and embedded recorded-file English transcription. Actual S24 capture/transcription, model memory, thermal behavior, cancellation latency, and coexistence with Liquid are **NOT RUN** until device evidence is recorded. A native build or synthetic PCM unit test does not establish speech quality.

## Build

The root Android project pins the Kotlin/AGP/SDK versions. This module uses NDK `27.2.12479018`, CMake `3.22.1`, arm64-v8a, and whisper.cpp v1.7.6 commit `a8d002cfd879315632a579e73f0148d06959de36`.

Run `./audio/scripts/fetch-whisper.sh` from `android/`, then `./gradlew :audio:testDebugUnitTest :audio:assembleDebug`. The fetch script requires network only during developer build setup, checks immutable commit and clean source, and writes ignored `.native/`. CMake performs no downloads. The upstream MIT runtime license is in the fetched checkout; model weights use OpenAI Whisper's MIT license. No weights are packaged in the APK.

`libclearline_whisper.so` statically links its own ggml with hidden visibility, `--exclude-libs,ALL`, and a linker version script that exports only this JNI bridge. No `libggml.so` is packaged. This must be verified alongside `libclearline_liquid.so` in the final APK. CPU backend only, four threads, GPU/BLAS/OpenMP disabled; 16 KiB ELF alignment requested.

## App integration

Use one `SharedModelArbiter` instance for Whisper and Liquid:

```kotlin
val runtime = WhisperAsrRuntime(context, sharedArbiter)
val processor = WhisperAudioProcessor(context, runtime)
val recorder = PcmAudioRecorder(context, foregroundScope) { clip ->
    // Schedule durable admission from the hardware completion event.
}
```

Setup may call `refreshInstalledStatus()` (local verification, off main thread). User-selected download passes `WhisperModelManifest.approvedDownload()` to `install`. A system document picker may instead pass an `ApprovedModelArtifact` using the exact same identity and a `content://` URI. Only the pinned HTTPS repository URL is accepted for network setup; redirects are restricted to Hugging Face HTTPS domains. Installation streams into `.part`, checks free space, exact 77,704,715-byte size and SHA-256, fsyncs, and atomically renames before readiness. Installing does not load; explicitly call `load(identity.modelId)`. Missing/unverified files never report ready.

The Activity obtains `RECORD_AUDIO` permission before `start(sessionId, clipId)`. Collect `recorder.state`: `Recording(elapsedMs, levelRms)`; `Finalized(clip)` on Stop or automatic 30-second completion; `Interrupted`; or safe `Failed` metadata. The constructor completion callback fires once per successful capture; the app uses that hardware event to schedule durable admission. Its exception cannot turn a completed recording into a capture failure. `StateFlow` observation is UI-only and must not admit clips or create jobs. The app retains pending completion until durable admission and relies on durable admission deduplication. Backgrounding must synchronously call `requestInterruption()` at the lifecycle edge, then await `interrupt()` and cancel local model work. The atomic interruption flag is rechecked before microphone activation, including when capture was queued on an IO dispatcher. Do not automatically resume on foregrounding.

Only true 16 kHz mono PCM16 capture is requested; unsupported devices fail visibly. There is no relabeling/resampling, cloud recognizer, SpeechRecognizer, laptop service, or HTTP transcription path. AudioRecord is released on every path. Nonblocking reads are bounded by a 45-second wall timeout. Explicit interruption discards the incomplete clip, whereas an already completed clip remains available for admission/recovery.

## Files and retention

Files live in `context.noBackupFilesDir/audio/<session UUID>/<clip UUID>.wav`; model files in `noBackupFilesDir/models/whisper/`. Recorder emits a receipt only after canonical WAV header/length validation, file fsync, atomic `.part` promotion and directory fsync. IDs cannot overwrite earlier bytes. Run `cleanPartialFiles()` once before capture on app startup; this removes partial recordings only. Storage owns orphan complete-file cleanup and deletes processed audio only after its result is durably committed. Accepted unprocessed complete files remain for retry; app/profile/session deletion needs storage's restart-safe cleanup. Backup/data-transfer exclusion is additionally owned by app manifest/rules.

## Measurements and filtering

`android-pcm-v1` computes full sample duration and normalized RMS from actual signed PCM16; WPM is Unicode lexical count / full recording minutes. `english-lexical-v1` uses shared `MeasurementMath`: letters/digits with internal straight or curly apostrophes, punctuation/underscores split words. Pitch and pause count stay null. Tests use synthetic PCM explicitly; no synthetic metric is substituted for real input.

Processing accepts 2–60 seconds (UI capture stops at 30). Engineering filters reject RMS <0.0005, peak <0.003, or fewer than ten audible 20 ms frames (frame RMS >=0.003). These are usability checks, not clinical thresholds or calibrated VAD. Whisper runs English greedy decode without previous-text conditioning, discards segments with no-speech probability >=0.6, and rejects empty/zero-word output. Noise can still cause transcription errors: actual-device speech evaluation and user correction remain necessary.

JNI uses bounded 90-second inference with native abort/encoder callbacks. Kotlin cancellation triggers an atomic cancellation flag from a separate coroutine while computation runs; cancellation does not free a live context. Runtime mutex and shared arbiter serialize load/inference/unload. Native logging is suppressed to prevent transcript/model-output leakage. Hardware cancellation boundaries may take time; no instantaneous abort claim.

## Verification

Host JVM tests exercise real PCM duration/RMS/WPM, silence/click rejection, truncated/relabeled WAV, `.part` rejection, identical promotion, reused-ID conflict, and verified/cancelled/oversize model installation. They do not use a real microphone/model.

Actual September 25, 2026 checks: **9/9 host JVM tests passed** using Kotlin 2.2.10 / JDK 17 and the real core source in an isolated Gradle harness (`/tmp/clearline-audio-host-check`), because the Android SDK license/install gate prevented running Android Gradle tasks. The actual pinned whisper.cpp and JNI bridge also compiled and linked on the Mac arm64 host with AppleClang 15.0.0. Run `JAVA_HOME=<JDK17> ./audio/scripts/verify-host-jni.sh` to repeat: **10/10 missing-model/context and cancellation/unload JNI smoke cycles passed**. `nm -gU` showed exactly five exported JNI methods and no ggml/whisper runtime exports. These host checks use no weights or real recordings and do not establish Android native linking, microphone behavior, transcription, or APK coexistence. A separate temporary JVM source harness (`/tmp/clearline-app-typecheck`) also compiled all 46 current production Kotlin files across the seven modules with the real Kotlin/Compose 2.2.10 compiler, pinned AndroidX AAR bytecode, and Robolectric Android 15 API jar. It caught and fixed use of the unavailable public `OsConstants.O_DIRECTORY` constant; private-directory fsync now opens the verified directory with `O_RDONLY`. This is a Kotlin/API compatibility check, not Android Gradle, resource/manifest, DEX, NDK or APK validation. Android instrumentation and S24 gates remain **NOT RUN**.

Android instrumentation contains native-library/missing-model checks plus explicit live tests guarded by runner argument `-e consentedAudio true`. Run only with a consenting speaker, permission already granted through the app, and the verified model installed. Read a non-sensitive English sentence during the ten-second recording. Live tests are skipped without the flag; skipped tests do not count as acceptance. Device lifecycle, revoked permission, low storage, force-stop recovery, airplane-mode speech, and 30-second auto-stop require separate S24 acceptance entries.

Primary references: [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord), [pinned whisper header](https://github.com/ggml-org/whisper.cpp/blob/a8d002cfd879315632a579e73f0148d06959de36/include/whisper.h), [model metadata](https://huggingface.co/api/models/ggerganov/whisper.cpp/revision/5359861c739e955e79d9a303bcbc70fb988958b1?blobs=true).
