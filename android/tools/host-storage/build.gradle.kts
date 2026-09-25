import org.gradle.api.artifacts.transform.*
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
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
val artifactType = Attribute.of("artifactType", String::class.java)
dependencies {
    registerTransform(AarToJar::class) { from.attribute(artifactType, "aar"); to.attribute(artifactType, "jar") }
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation("org.jspecify:jspecify:1.0.0")
    implementation("androidx.room:room-runtime-android:2.7.1@aar")
    implementation("androidx.room:room-common-jvm:2.7.1")
    implementation("androidx.sqlite:sqlite-android:2.5.0@aar")
    implementation("androidx.sqlite:sqlite-framework-android:2.5.0@aar")
    implementation("androidx.annotation:annotation-jvm:1.9.1")
    implementation("androidx.arch.core:core-runtime:2.2.0@aar")
    implementation("androidx.arch.core:core-common:2.2.0")
    implementation("androidx.collection:collection-jvm:1.4.5")
    implementation("org.robolectric:android-all:15-robolectric-12650502")
    ksp("androidx.room:room-compiler:2.7.1")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.6.1@aar")
    testImplementation("androidx.test:monitor:1.7.2@aar")
    testImplementation(libs.kotlinx.coroutines.test)
}
configurations.configureEach { attributes.attribute(artifactType, "jar") }
kotlin {
    jvmToolchain(17)
    sourceSets.main { kotlin.srcDir("../../storage/src/main/kotlin") }
    sourceSets.test { kotlin.srcDir("../../storage/src/test/kotlin") }
}
ksp { arg("room.schemaLocation", file("../../storage/schemas").absolutePath) }
tasks.test {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    maxHeapSize = "2g"
}
