rootProject.name = "tether-xmpp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

include(":tether-xml")
include(":tether-debug")
include(":tether-crypto")
include(":tether-sasl")
include(":tether-transport")
include(":tether-core")
include(":tools:probe")

// The Android module needs the Android SDK and Google's Maven repository. Builds
// without them (some CI and sandboxes) skip it; GitHub's runners have both.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
if (androidSdk != null && providers.gradleProperty("tether.android").orNull != "false") {
    include(":tether-android")
}
