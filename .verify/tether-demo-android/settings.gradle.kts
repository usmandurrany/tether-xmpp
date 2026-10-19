rootProject.name = "tether-demo-android"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")

// The SDK is built from source: tether-xmpp is a git submodule, included as a
// composite build, so `io.github.usmandurrany.tether:*` dependencies resolve
// to its modules. No publishing step is needed.
val sdk = file("tether-xmpp")
if (!sdk.resolve("settings.gradle.kts").exists()) {
    throw GradleException(
        "The Tether SDK sources are missing. Run `git submodule update --init` " +
            "(or clone with `git clone --recursive`), then sync again.",
    )
}

// The SDK only builds its Android module when it can find the Android SDK. Android
// Studio writes sdk.dir to this project's local.properties, so hand the same file
// to the SDK build when it has none of its own.
val ownLocalProperties = file("local.properties")
val sdkLocalProperties = sdk.resolve("local.properties")
if (ownLocalProperties.exists() && !sdkLocalProperties.exists()) {
    ownLocalProperties.copyTo(sdkLocalProperties)
}

includeBuild(sdk)
