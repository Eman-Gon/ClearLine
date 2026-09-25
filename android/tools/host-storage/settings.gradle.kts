pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement {
    repositories { mavenCentral(); maven { url = uri("https://dl.google.com/dl/android/maven2/"); metadataSources { mavenPom(); artifact(); ignoreGradleMetadataRedirection() } } }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
rootProject.name = "ClearLineHostStorageTests"
include(":core")
project(":core").projectDir = file("../../core")
