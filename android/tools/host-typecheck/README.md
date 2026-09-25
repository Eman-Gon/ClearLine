# Host Kotlin / Compose source compatibility check

This independent JVM Gradle project compiles the **actual production Kotlin sources** in `app`, `core`, `storage`, `audio`, `inference`, `agent`, and `sponsors`. It applies the real Kotlin serialization and Compose compiler plugins using the repository version catalog. It uses published AndroidX AAR bytecode plus Robolectric's public Android 15 API jar; it contains no custom Android, application, or sponsor stubs.

This project **does not apply Android Gradle Plugin or install Android SDK packages**. It neither requests nor accepts Android SDK licenses. It is useful when the SDK is unavailable, but is not an Android application build or device acceptance test.

## Run

From this directory, with JDK 17 available through `JAVA_HOME`:

```sh
../../gradlew -p . compileKotlin
../../gradlew -p . test --tests com.clearline.app.CurrentStateAuthorizationTest
```

The wrapper downloads the root project's pinned Gradle version if missing. The first run downloads public Maven dependencies; later runs may use `--offline` after the complete dependency set is cached. No model weights are downloaded or loaded.

Build output defaults to this directory's ignored `build/`. To put it elsewhere:

```sh
../../gradlew -p . compileKotlin test -PhostBuildDir=/tmp/clearline-host-typecheck-build
```

`CurrentStateAuthorizationTest.kt` is the only app test selected here. It exercises the real authorization adapter and typed core contracts using a read-only test store proxy. It does not make sponsor calls, exercise Android Keystore, use a microphone, or test Compose rendering. Other Android/Robolectric/instrumentation tests belong to their normal module or dedicated harness.

## Dependency selection and limits

The build extracts each AAR's `classes.jar` through a Gradle artifact transform. Concrete Android variants are explicit because this is a JVM compilation target; the host resolver must not select desktop or empty multiplatform facade artifacts. AndroidX/Compose versions match the native project's pinned version set, including Compose BOM `2025.04.01`: UI/foundation/runtime `1.8.0`, material3 `1.3.2`, and material-icons-core `1.7.8`. If the root dependency versions change, update this explicit list and rerun the check.

The Android API jar is `org.robolectric:android-all:15-robolectric-12650502`. It is published test bytecode, not an SDK platform installation. The compiler can identify missing public types/methods and Compose call errors, but this does not establish compatibility with an actual SDK build or a specific device.

This harness does **not** validate Android resources, the manifest, AAPT, generated `R`/`BuildConfig`, DEX, R8, native NDK linking, APK packaging, Room KSP generation, lifecycle behavior, Android permissions, or real audio/model execution. The production Room sources compile here, while generated implementation/schema verification must run separately. No result from this harness satisfies S24 installation, capture, inference, privacy, or recovery acceptance gates.

On September 25, 2026, this preserved repository harness passed full production Kotlin/Compose compilation and all **8 CurrentStateAuthorization tests** (0 failures/errors/skips). The earlier temporary harness exposed two invalid `OsConstants.O_DIRECTORY` references in audio code, which were fixed. Keep subsequent results in the native acceptance record; do not infer continued success from the presence of this project.
