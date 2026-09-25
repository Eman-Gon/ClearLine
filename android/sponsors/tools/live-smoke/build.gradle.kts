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
            "$repositoryRoot/android/sponsors/tools/live-smoke/src/main/kotlin",
        )
        // No Android substitutes: this exercises the actual portable adapters.
        kotlin.exclude("**/Android*.kt")
    }
    test { kotlin.srcDir("$repositoryRoot/android/sponsors/tools/live-smoke/src/test/kotlin") }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

tasks.test { useJUnit() }
tasks.register("writeRuntimeClasspath") {
    dependsOn("classes")
    val destination = layout.buildDirectory.file("runtime-classpath.txt")
    outputs.file(destination)
    doLast { destination.get().asFile.writeText(sourceSets["main"].runtimeClasspath.asPath) }
}
