plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The rules of the games. Pure Kotlin with no UI, so the same code runs on phones, desktop and (later) the server.
kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
