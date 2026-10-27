plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// The rules of the games. Pure Kotlin with no UI, so the same code runs on phones, desktop and (later) the server.
kotlin {
    jvmToolchain(17)
    jvm()
    // iPhone / iPad (devices, Apple-silicon simulators, Intel-Mac simulators).
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
