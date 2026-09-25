# Room checks without an Android SDK

This independent Gradle build compiles the actual `core/` and `storage/` sources,
runs Room's pinned KSP compiler, and runs the storage tests using Robolectric's
Android 15 runtime. It extracts the real AndroidX AAR class files for JVM execution.
It does not substitute hand-written Android/Room stubs.

With JDK 17 and Gradle 8.11.1 available, from `android/` run:

```sh
gradle -p tools/host-storage test --console=plain
```

The first run downloads Maven dependencies and Robolectric's instrumented Android
runtime. It does not install Android SDK packages or accept Android SDK licenses.
Room schema JSON is generated under `storage/schemas/`. Reports are written under
`tools/host-storage/build/reports/tests/test/`; core reports are under
`core/build/reports/tests/test/`.

This build verifies Kotlin contracts, generated database queries, database
transactions and migration behavior on the host. It cannot build/install an APK or
verify an Android manifest, backup exclusions, actual device lifecycle, microphone,
ASR, Liquid runtime, S24 performance or phone network traffic. Use the regular
Android build and actual device acceptance gates for those checks.
