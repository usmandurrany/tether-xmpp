import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    jvm {
        // Java 17 bytecode keeps the module usable on Android (API 26+) and current JVMs.
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    linuxX64()

    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        optIn.add("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":tether-core"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
