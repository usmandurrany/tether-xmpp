import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // Optional: apps add conscrypt-android or conscrypt-openjdk-uber to get tls-exporter.
            compileOnly(libs.conscrypt.openjdk)
        }
        jvmTest.dependencies {
            implementation(libs.conscrypt.openjdk)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
