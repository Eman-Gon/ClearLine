# Android APK build — September 25, 2026

The full debug APK build succeeded on the user-selected T7 SSD. All 170 unit tests
passed, with zero failures, errors or skips. Installation, live model execution,
recording and sponsor calls on a physical phone remain **NOT RUN**. The user's friend
can follow [phone setup and testing](PHONE_TESTING.md) without building the project.

## Artifact

- Shareable file: `/Volumes/T7/ClearLine-build/share/ClearLine-0.1-native-debug.apk`.
- Plain-text instructions: `/Volumes/T7/ClearLine-build/share/START-HERE.txt`.
- Original output: `/Volumes/ClearLineBuild/project/android/app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `0e1aeaae9577c5d32e7961796192e58617f509b63e192a576ce25fe53b0c70ec`.
- Application ID `com.clearline.app`, version `0.1-native` / code `1`.
- ARM64 only (`arm64-v8a`), minimum API 26 / Android 8.0, target/compile API 35.
- Signed debug test build; not a production or Play Store release.
- Model weights and sponsor credentials are not supplied with the handoff. The two
  verified model downloads are offered explicitly in Setup (about 775 MB total).

The ordinary share files are directly on T7 and do not require mounting its APFS
build volume to retrieve them. [Toolchain instructions](TOOLCHAIN.md) cover mounting
that volume and synchronizing source for future builds. Keep T7 connected while building.

## Reproduction and results

Run after mounting the build volume and synchronizing the authoritative repository:

```sh
source /Volumes/ClearLineBuild/toolchain/env.sh
cd /Volumes/ClearLineBuild/project/android
set -o pipefail
gradle --no-daemon --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process --console=plain \
  :core:test :agent:test :storage:testDebugUnitTest :audio:testDebugUnitTest \
  :inference:testDebugUnitTest :sponsors:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug 2>&1 | tee /Volumes/ClearLineBuild/logs/android-build.log
```

Observed: **BUILD SUCCESSFUL in 3m 34s**, 193 actionable tasks (154 executed, 39 from
cache). Counts below are from the actual seven modules' XML reports, without adding
overlapping earlier host harness runs.

| Module | Tests | Failures/errors/skips |
| --- | ---: | --- |
| core | 24 | 0 / 0 / 0 |
| agent | 25 | 0 / 0 / 0 |
| storage | 16 | 0 / 0 / 0 |
| audio | 9 | 0 / 0 / 0 |
| inference | 44 | 0 / 0 / 0 |
| sponsors | 44 | 0 / 0 / 0 |
| app | 8 | 0 / 0 / 0 |
| **Total** | **170** | **0 / 0 / 0** |

These are JVM/Robolectric unit tests. They do not exercise the phone microphone,
loaded on-device models, Android Keystore or live sponsor endpoints. Instrumentation
and the S24 acceptance gates in [the handoff](SESSION_1_HANDOFF.md) remain pending.

## Packaged APK checks

- `apksigner verify --verbose --print-certs`: passed, APK signature scheme v2.
- Debug certificate SHA-256:
  `e0decf68c8bb491ec456a3d5cd57fe70a70e2006bb7f059e30a83e6574ed718b`.
- `zipalign -c -P 16 4`: passed.
- AAPT confirmed package/version/API levels, launcher, microphone permission and ARM64 ABI.
- Packaged libraries are exactly `libandroidx.graphics.path.so`,
  `libclearline_liquid.so` and `libclearline_whisper.so`, all under `lib/arm64-v8a/`.
- Every library is ELF64 AArch64; every LOAD segment is aligned to `0x4000` (16 KiB).
- Whisper exposes exactly five JNI functions; Liquid exposes exactly seven. Neither
  exposes public ggml/llama/whisper runtime symbols. Dynamic dependencies are system
  Android libraries; no separate model-runtime library is needed.
- APK contains no model-weight assets. The merged manifest preserves
  `allowBackup=false` and `usesCleartextTraffic=false`; debug status is explicit.

Evidence is retained on T7 under `/Volumes/ClearLineBuild/logs/` and
`/Volumes/ClearLineBuild/verification/`, with unit reports in each module's
`build/test-results/` directory. These package checks do not prove runtime coexistence,
privacy behavior or successful installation on the friend's phone.

## Non-blocking build warnings

AGP reported newer SDK XML metadata than its parser version. Whisper's upstream
feature-display probe omitted the Android compiler target and printed an ARM CPU
warning; actual compiler commands used `--target=aarch64-none-linux-android26`, and
the packaged ELF was checked independently as AArch64. The pinned llama archive
printed unavailable Git metadata because it is an extracted archive outside a Git
repository. None prevented native compilation or packaging. Existing Kotlin
deprecation/parameter-name warnings also remain.
