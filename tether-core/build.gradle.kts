import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":tether-xml"))
            api(project(":tether-sasl"))
            api(project(":tether-transport"))
            implementation(project(":tether-crypto"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.conscrypt.openjdk)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
