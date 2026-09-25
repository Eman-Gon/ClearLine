#!/usr/bin/env bash
# Compile actual shared/sponsor sources and run their JVM tests without Android SDK.
# This does not exercise Android Keystore, backup rules, APK packaging, or a phone.
set -euo pipefail

task_script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export CLEARLINE_REPOSITORY_ROOT=$(cd -- "$task_script_dir/../../.." && pwd)
task_toolchain_root=${CLEARLINE_TOOLCHAIN_ROOT:-/private/tmp/clearline-android-toolchain}
if [[ ! -f "$task_toolchain_root/env.sh" ]]; then
  printf '%s\n' 'Missing isolated toolchain. See android/docs/TOOLCHAIN.md.' >&2
  exit 1
fi
# Uses the previously installed project toolchain; performs no SDK installation.
source "$task_toolchain_root/env.sh"
task_project_dir=$(mktemp -d "${TMPDIR:-/private/tmp}/clearline-sponsors-host-jvm.XXXXXX")

cat > "$task_project_dir/settings.gradle.kts" <<'GRADLE'
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
rootProject.name = "clearline-sponsors-host-jvm"
GRADLE

cat > "$task_project_dir/build.gradle.kts" <<'GRADLE'
plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
}

repositories { mavenCentral() }
kotlin { jvmToolchain(17) }

val repositoryRoot = providers.environmentVariable("CLEARLINE_REPOSITORY_ROOT").get()
kotlin.sourceSets {
    main {
        kotlin.srcDirs(
            "$repositoryRoot/android/core/src/main/kotlin",
            "$repositoryRoot/android/sponsors/src/main/java",
        )
        // These are real Android bindings. No framework stubs are substituted.
        kotlin.exclude("**/Android*.kt", "**/SponsorClients.kt")
    }
    test {
        kotlin.srcDirs(
            "$repositoryRoot/android/core/src/test/kotlin",
            "$repositoryRoot/android/sponsors/src/test/java",
        )
        kotlin.exclude("**/Android*.kt")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
GRADLE

printf 'Host JVM verification only; temporary project: %s\n' "$task_project_dir"
"$task_toolchain_root/gradle-8.11.1/bin/gradle" --no-daemon --console=plain \
  -p "$task_project_dir" test "$@"
printf 'Host JVM report: %s/build/reports/tests/test/index.html\n' "$task_project_dir"
