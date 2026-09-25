# Native build toolchain

The Android project uses AGP 8.9.2, Gradle 8.11.1, Kotlin 2.2.10 / Compose compiler 2.2.10, KSP 2.2.10-2.0.2, JDK 17, compile/target SDK 35, min SDK 26, NDK 27.2.12479018, and CMake 3.22.1. See the version catalog and module build files for library pins. Runtime commits and model identity belong to the audio/inference manifests.

The [AGP 8.9 compatibility table](https://developer.android.com/build/releases/past-releases/agp-8-9-0-release-notes) specifies Gradle 8.11.1 and JDK 17, with support through API 35. [Kotlin's compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html) covers this Gradle/AGP pair with Kotlin 2.2.10. Gradle 8.11.1 [does not run on JDK 25](https://docs.gradle.org/8.11.1/userguide/compatibility.html), the preexisting shell default on this build host.

## Isolated macOS Apple Silicon setup

The helper does not change system Java, shell startup files, or other projects. Downloads and caches default to `/private/tmp/clearline-android-toolchain`; set `CLEARLINE_TOOLCHAIN_ROOT` to a persistent writable directory if desired. Temporary storage can disappear between reboots.

```sh
cd /Users/emanschool/ClearLine/android
./tools/setup-toolchain.sh --bootstrap
```

This downloads checksum-pinned Gradle 8.11.1 and Temurin JDK 17.0.20.1+1. It produces an environment file. For Android SDK installation, review the [Android SDK agreement](https://developer.android.com/studio/terms) first, then run the interactive installer:

```sh
./tools/setup-toolchain.sh --install-sdk
source /private/tmp/clearline-android-toolchain/env.sh
./gradlew --no-daemon :core:test :app:assembleDebug
```

The script lets `sdkmanager` prompt for licenses; it does not pipe automatic acceptance. It installs command-line tools build 15859902 (tool revision 22.0), platform-tools, API 35, build-tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1. Command-line tools are taken from the [official Android download page](https://developer.android.com/studio). On other hosts, install equivalent packages and use a JDK 17 distribution appropriate to that host.

```sh
adb devices -l
./gradlew :app:installDebug
```

Installation requires a connected device with USB debugging enabled and its authorization prompt accepted on the device. A successful APK build does not prove S24 recording, model execution, offline operation, or recovery.

## Bootstrap evidence — September 25, 2026

- Build host: Apple Silicon macOS (Darwin 23.4.0).
- Official Gradle archive SHA-256 verified: `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`.
- Official Temurin archive SHA-256 verified: `196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8`.
- Official command-line-tools archive SHA-256 verified: `835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e`.
- KSP `2.2.10-2.0.2` plugin marker resolved from the Gradle plugin portal to Maven Central (HTTP 200).
- Existing adb 33.0.3 reports no attached devices. S24 hardware gates are **NOT RUN**.
- Standard Gradle 8.11.1 wrapper generation passed, including the distribution checksum pin.
- The initial root `:core:test` resolved build plugins, then stopped during native-module configuration because NDK 27.2.12479018's SDK license was not yet accepted. No Android compilation or native test passed at that point.
- A temporary JVM-only Gradle harness compiled the real core module and passed its initial seven `CallInsightsTest` tests. This is host-only contract verification, not Android execution. The first compiler attempt encountered an incremental-cache daemon error; Gradle's fallback compiler completed successfully. For concurrent development, `-Pkotlin.compiler.execution.strategy=in-process` avoids sharing the Kotlin compiler daemon.
- SDK package installation awaits license approval; compilation results will be recorded separately when the complete project is built.

In a restricted coding sandbox, Gradle's local daemon socket and dependency downloads require the execution tool's normal escalation path. The initial sandboxed wrapper generation failed with `SocketException: Operation not permitted`; this was an environment restriction, not an Android compiler failure.
