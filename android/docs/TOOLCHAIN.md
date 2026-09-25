# Native build toolchain

The Android project uses AGP 8.9.2, Gradle 8.11.1, Kotlin 2.2.10 / Compose compiler 2.2.10, KSP 2.2.10-2.0.2, JDK 17, compile/target SDK 35, min SDK 26, NDK 27.2.12479018, and CMake 3.22.1. See the version catalog and module build files for library pins. Runtime commits and model identity belong to the audio/inference manifests.

The [AGP 8.9 compatibility table](https://developer.android.com/build/releases/past-releases/agp-8-9-0-release-notes) specifies Gradle 8.11.1 and JDK 17, with support through API 35. [Kotlin's compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html) covers this Gradle/AGP pair with Kotlin 2.2.10. Gradle 8.11.1 [does not run on JDK 25](https://docs.gradle.org/8.11.1/userguide/compatibility.html), the preexisting shell default on this build host.

## Isolated macOS Apple Silicon setup

The helper does not change system Java, shell startup files, or other projects. Downloads and caches default to `/private/tmp/clearline-android-toolchain`; set `CLEARLINE_TOOLCHAIN_ROOT` to a persistent writable directory if desired. Ordinary temporary storage can disappear between reboots. This machine now uses the user-selected T7 storage layout below; the old temporary path serves only as a compatibility symlink.

```sh
cd /Users/emanschool/ClearLine/android
./tools/setup-toolchain.sh --bootstrap
```

This downloads checksum-pinned Gradle 8.11.1 and Temurin JDK 17.0.20.1+1. It produces an environment file. For Android SDK installation, review the [Android SDK agreement](https://developer.android.com/studio/terms) first, then run the interactive installer:

```sh
./tools/setup-toolchain.sh --install-sdk
source /private/tmp/clearline-android-toolchain/env.sh
gradle --no-daemon :core:test :app:assembleDebug
```

The script lets `sdkmanager` prompt for licenses; it does not pipe automatic acceptance. It installs command-line tools build 15859902 (tool revision 22.0), platform-tools, API 35, build-tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1. Command-line tools are taken from the [official Android download page](https://developer.android.com/studio). On other hosts, install equivalent packages and use a JDK 17 distribution appropriate to that host.

```sh
adb devices -l
gradle :app:installDebug
```

Installation requires a connected device with USB debugging enabled and its authorization prompt accepted on the device. A successful APK build does not prove S24 recording, model execution, offline operation, or recovery.

## Persistent T7 build storage

The user requested that the build use the external T7 SSD. T7 uses exFAT, so an APFS sparse bundle was created as a file on that drive to support the build tools' filesystem requirements. T7 was not formatted. The bundle has a 32 GiB maximum capacity and occupies space as its contents grow.

| Purpose | Path |
| --- | --- |
| Persistent APFS bundle on T7 | `/Volumes/T7/ClearLine-build/ClearLineBuild.sparsebundle` |
| Mounted build volume | `/Volumes/ClearLineBuild` |
| JDK, SDK, Gradle, downloads, and Gradle cache | `/Volumes/ClearLineBuild/toolchain` |
| Android build copy and generated outputs | `/Volumes/ClearLineBuild/project/android` |
| Authoritative working source | `/Users/emanschool/ClearLine/android` |

Keep T7 connected and the APFS volume mounted while using the build tools or running a build. Stop Gradle and other processes using the volume before ejecting it. The SDK installation and accepted license receipt are retained within the persistent toolchain; mounting the volume again does not require accepting the license again.

After reconnecting T7, mount the bundle if `/Volumes/ClearLineBuild` is not already mounted:

```sh
hdiutil attach /Volumes/T7/ClearLine-build/ClearLineBuild.sparsebundle
source /Volumes/ClearLineBuild/toolchain/env.sh
cd /Volumes/ClearLineBuild/project/android
gradle --no-daemon :core:test :app:assembleDebug
```

The generated `env.sh` uses the actual SSD paths and places the already extracted Gradle 8.11.1 on `PATH`. The `gradle` command above reuses that installation without downloading another wrapper distribution. `/private/tmp/clearline-android-toolchain` is retained as a compatibility symlink to `/Volumes/ClearLineBuild/toolchain`, but the symlink itself may disappear when macOS cleans temporary storage. Prefer the persistent path above. If the environment file must be regenerated, use the existing helper:

```sh
CLEARLINE_TOOLCHAIN_ROOT=/Volumes/ClearLineBuild/toolchain \
  /Users/emanschool/ClearLine/android/tools/setup-toolchain.sh --env \
  > /Volumes/ClearLineBuild/toolchain/env.sh
```

Make source fixes in the original working repository, then copy them to the SSD before rebuilding. Do not make independent source edits in the build copy. For example, this updates source without copying generated outputs or replacing the SSD's local SDK configuration:

```sh
rsync -a --exclude '/.gradle/' --exclude '/.kotlin/' \
  --exclude '**/build/' --exclude '**/.cxx/' --exclude '/local.properties' \
  /Users/emanschool/ClearLine/android/ /Volumes/ClearLineBuild/project/android/
```

The T7 migration completed on September 25, 2026. A checksum-based `rsync` dry run found no differences in the copied toolchain, and Java, CMake, Clang, AAPT2 and Gradle executed successfully from the external volume. Only after verification was the old internal toolchain removed and replaced with the compatibility symlink. Internal free space increased from approximately 1.5 GiB to 6.3 GiB at that point.

The Android source build copy is on the external volume. The full Gradle build covering all seven modules' unit tests plus `:app:assembleDebug` completed successfully there: 170 tests passed with no failures or skips. APK signature, native exports, ABI and 16 KiB alignment checks also passed. See [APK build evidence](APK_BUILD.md). Phone installation and device tests remain unverified.

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
- At the end of this initial bootstrap attempt, SDK package installation awaited license approval. That earlier blocker was resolved by the installation below.

In a restricted coding sandbox, Gradle's local daemon socket and dependency downloads require the execution tool's normal escalation path. The initial sandboxed wrapper generation failed with `SocketException: Operation not permitted`; this was an environment restriction, not an Android compiler failure.

## SDK installation evidence — September 25, 2026

The user explicitly accepted the Android SDK license, and SDK installation completed successfully in `/private/tmp/clearline-android-toolchain/sdk`. The installed SDK license receipt exists at `licenses/android-sdk-license`; license acceptance is no longer a build blocker.

The installed package metadata records:

| Package | Installed revision |
| --- | --- |
| Android SDK Command-line Tools | 22.0 (archive build 15859902) |
| Android SDK Platform-Tools | 37.0.1 |
| Android SDK Platform 35 | 2 (API 35) |
| Android SDK Build-Tools | 35.0.0 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |

Revisions above were read from each installed package's `package.xml`, except command-line tools, which provides `source.properties`. The full Android Gradle tests and `:app:assembleDebug` subsequently passed; the [build record](APK_BUILD.md) identifies the APK and verification evidence. No device was attached for this build. The user's friend will perform phone setup using the [phone testing guide](PHONE_TESTING.md); installation and S24 hardware acceptance remain **NOT RUN**.
