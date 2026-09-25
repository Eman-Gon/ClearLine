# ClearLine native Android

The native phone implementation for build specification 3.1 is in this directory.
The sibling `frontend/` and `backend/` are desktop prototypes; the APK has no dependency on them.

Session 1 supplies the Compose application, microphone/Whisper module, shared contracts,
and transactional Room store. Session 2 supplies the embedded Liquid runtime and workflow
coordinator. Session 3 supplies the optional encrypted sponsor connections.

**Current verification:** Android debug version **0.1.1** (version code 2) built successfully
on September 25, 2026, with all **174** module unit tests passing. APK signing, arm64
packaging, and 16 KiB native alignment checks passed. Live host probes verified Nimble
Search/Extract and isolated synthetic RawTree event/memory readback. Phone installation
and S24 acceptance are still untested. See [APK build evidence](docs/APK_BUILD.md),
[live sponsor checks](docs/SPONSOR_LIVE_CHECKS.md), and [the phone setup guide](docs/PHONE_TESTING.md).

## Build and run

Use JDK 17 and the checked-in Gradle wrapper. Provision the pinned SDK/NDK only after
reviewing and accepting Google's SDK agreement; [toolchain setup](docs/TOOLCHAIN.md)
documents the isolated installer. No SDK agreement is accepted automatically.

```sh
cd android
./audio/scripts/fetch-whisper.sh
./gradlew :core:test :agent:test :storage:testDebugUnitTest :audio:testDebugUnitTest :inference:testDebugUnitTest :sponsors:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
adb devices -l
./gradlew :app:installDebug
```

The target is arm64-v8a, API 35, minimum API 26. Native source identities and model
digests are pinned. Models are not APK assets: Setup offers explicit download or
private file import, then verified loading. Sponsor services start disabled with no keys.
No Liquid API key or laptop server is used.

On this Mac, the SDK, Gradle cache and build outputs live on the T7 SSD. Follow the
[T7 mount and source-sync instructions](docs/TOOLCHAIN.md) before rebuilding there.
The shareable debug APK is `/Volumes/T7/ClearLine-build/share/ClearLine-0.1.1-native-debug.apk`.
A phone tester only needs that APK and the setup instructions; they do not build the project.
Install it over version 0.1 as an update: the package and signing certificate are unchanged,
so installed models and saved history are retained. Do not uninstall or clear app storage.

## App flow

1. In Setup, install the Whisper and Liquid artifacts. Installation and loaded readiness
   are separate states. Only one model uses the shared native execution arbiter at a time.
2. Start a check-in, consent, grant microphone permission and record a non-sensitive
   English demonstration. Stop manually or allow the 30-second limit to finish it.
3. The hardware completion callback admits a finalized private WAV. Room stores its
   identity/checksum before the coordinator processes it. Opening a screen never starts work.
4. Summary shows actual duration, word count, recording WPM and RMS, plus a separately
   labeled local baseline. Pitch, pauses, emotion and drift are unmeasured.
5. Optional follow-up shows the complete editable transcript-derived query and local
   rationale. Approval authorizes that exact query/city. Public source cards retain evidence.
6. Export choices are off by default. Measurement approval and exact snippet/keyword
   approval are separate. Saving selected measurements can export the displayed current
   summary; it does not backfill other sessions. RawTree counts/history are query-confirmed,
   separately labeled, and never substituted for local recovery or the local baseline.
7. Backgrounding cancels capture/local work and saves unfinished tasks. Reopen and press
   Resume. A partial capture requires replacement; completed admitted clips survive restart.

## Ownership and contracts

| Module | Responsibility |
| --- | --- |
| `core/` | Serializable bounded DTOs, shared ports, measurements, query rationale and memory math |
| `storage/` | Room v2, explicit migration, atomic claims/commit/outbox, admission and deletion |
| `audio/` | Native PCM capture, verified Whisper installation, embedded ASR and measured PCM |
| `inference/` | Verified Liquid installation, llama.cpp JNI, prompt/parser and model proposal |
| `agent/` | Foreground workflow policy, resume/recovery, local tools and consented projections |
| `sponsors/` | Keystore vault, allowlisted Nimble/RawTree HTTPS, bounded returned projections |
| `app/` | Concrete composition, current-state dispatch authorization, lifecycle and native UI |

Start with [audio](audio/README.md), [storage](storage/README.md), [agent](agent/README.md),
[sponsors](sponsors/README.md), and [call context](docs/CALL_CONTEXT.md) for boundary details.

## Host checks without the Android SDK

[The host source check](tools/host-typecheck/README.md) uses real AndroidX bytecode,
the real Compose compiler and Robolectric's Android API jar. It catches Kotlin/API
errors without inventing Android stubs. It does not package resources, build NDK
libraries, merge manifests, produce DEX/APKs, or establish device behavior.

```sh
cd android/tools/host-typecheck
../../gradlew -p . test
```

The first run resolves public Maven dependencies. The full Android checks above remain
required. Instrumented UI tests are in `app/src/androidTest/`; consent-gated device
audio tests are documented in `audio/README.md`.

The [Room host harness](tools/host-storage/README.md) separately generates the actual
Room implementation and runs its database/migration tests. From `android/`, use
`./gradlew -p tools/host-storage test --console=plain`.
