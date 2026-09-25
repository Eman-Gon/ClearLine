import org.gradle.api.artifacts.transform.*
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.util.zip.ZipFile
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}
abstract class AarToJar : TransformAction<TransformParameters.None> {
    @get:InputArtifact abstract val inputArtifact: Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        ZipFile(input).use { zip ->
            val entry = zip.getEntry("classes.jar")
            if (entry != null) zip.getInputStream(entry).use { source -> outputs.file(input.nameWithoutExtension + ".jar").outputStream().use { source.copyTo(it) } }
        }
    }
}
val androidRoot = rootDir.resolve("../..").canonicalFile
// This separate JVM project never applies AGP or touches the Android SDK.
layout.buildDirectory.set(file(providers.gradleProperty("hostBuildDir").getOrElse("build")))
val artifactType = Attribute.of("artifactType", String::class.java)
dependencies {
    registerTransform(AarToJar::class) { from.attribute(artifactType, "aar"); to.attribute(artifactType, "jar") }
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation("org.robolectric:android-all:15-robolectric-12650502")
    implementation("androidx.annotation:annotation-jvm:1.9.1")
    implementation("androidx.collection:collection-jvm:1.4.2")
    implementation("androidx.lifecycle:lifecycle-common-jvm:2.8.7")
    implementation("androidx.room:room-common-jvm:2.7.1")
    implementation("androidx.arch.core:core-common:2.2.0")
    val androidArtifacts = listOf(
        "androidx.arch.core:core-runtime:2.2.0", "androidx.core:core:1.16.0", "androidx.core:core-ktx:1.16.0",
        "androidx.activity:activity:1.10.1", "androidx.activity:activity-ktx:1.10.1", "androidx.activity:activity-compose:1.10.1",
        "androidx.lifecycle:lifecycle-runtime-android:2.8.7", "androidx.lifecycle:lifecycle-runtime-ktx:2.8.7",
        "androidx.lifecycle:lifecycle-runtime-compose-android:2.8.7", "androidx.lifecycle:lifecycle-viewmodel-android:2.8.7",
        "androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7", "androidx.lifecycle:lifecycle-viewmodel-compose-android:2.8.7",
        "androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7", "androidx.lifecycle:lifecycle-process:2.8.7",
        "androidx.lifecycle:lifecycle-livedata-core:2.8.7", "androidx.savedstate:savedstate:1.2.1", "androidx.savedstate:savedstate-ktx:1.2.1",
        "androidx.room:room-runtime-android:2.7.1", "androidx.sqlite:sqlite-android:2.5.0", "androidx.sqlite:sqlite-framework-android:2.5.0",
        "androidx.compose.runtime:runtime-android:1.8.0", "androidx.compose.runtime:runtime-saveable-android:1.8.0",
        "androidx.compose.ui:ui-android:1.8.0", "androidx.compose.ui:ui-text-android:1.8.0", "androidx.compose.ui:ui-unit-android:1.8.0",
        "androidx.compose.ui:ui-graphics-android:1.8.0", "androidx.compose.ui:ui-geometry-android:1.8.0", "androidx.compose.ui:ui-util-android:1.8.0",
        "androidx.compose.ui:ui-tooling-preview-android:1.8.0", "androidx.compose.foundation:foundation-android:1.8.0",
        "androidx.compose.foundation:foundation-layout-android:1.8.0", "androidx.compose.animation:animation-android:1.8.0",
        "androidx.compose.animation:animation-core-android:1.8.0", "androidx.compose.material:material-ripple-android:1.8.0",
        "androidx.compose.material:material-icons-core-android:1.7.8", "androidx.compose.material3:material3-android:1.3.2"
    )
    androidArtifacts.forEach { implementation("$it@aar") { isTransitive = false } }
}
configurations.configureEach { attributes.attribute(artifactType, "jar") }
kotlin {
    jvmToolchain(17)
    sourceSets.test {
        kotlin.srcDir(androidRoot.resolve("app/src/test/java"))
        kotlin.srcDir(androidRoot.resolve("app/src/test/kotlin"))
        kotlin.include("**/CurrentStateAuthorizationTest.kt")
    }
    sourceSets.main {
        for (module in listOf("core", "storage", "audio", "inference", "agent", "sponsors", "app")) {
            kotlin.srcDir(androidRoot.resolve("$module/src/main/kotlin"))
            kotlin.srcDir(androidRoot.resolve("$module/src/main/java"))
        }
    }
}

tasks.test { maxHeapSize = "1g" }
