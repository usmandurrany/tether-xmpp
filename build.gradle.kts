buildscript {
    // Android Gradle Plugin only when the Android module is part of the build
    // (see settings.gradle.kts), so builds without the SDK never fetch it.
    if (findProject(":tether-android") != null) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies { classpath(libs.android.gradle) }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "io.github.usmandurrany.tether"
    version = "0.1.0-SNAPSHOT"
}

// Library modules publish to a Maven repository (`./gradlew publishToMavenLocal`
// for local use), so apps can depend on them by coordinates.
subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") { apply(plugin = "maven-publish") }
}
