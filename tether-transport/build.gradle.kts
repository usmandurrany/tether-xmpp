import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val opensslHeaders = file("/usr/include/openssl/ssl.h").exists()

kotlin {
    explicitApi()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    linuxX64 {
        // The native transport links the system's OpenSSL. Hosts without its
        // headers (macOS, minimal containers) build the rest without it.
        if (opensslHeaders) {
            compilations.getByName("main").cinterops.create("tls") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/tls.def"))
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        if (opensslHeaders) {
            getByName("linuxX64Main").kotlin.srcDir("src/linuxX64Tls/kotlin")
            getByName("linuxX64Test").kotlin.srcDir("src/linuxX64TlsTest/kotlin")
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
