plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    jvm()
    android {
        namespace = "io.github.bropines.lens.translate"
        compileSdk {
            // Compose 1.13 libraries need the 37.1 minor SDK (checked in release builds).
            version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            // HttpClient is part of the public API (ProviderHost.http).
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            // Only for LiveSmokeTest, which is skipped unless LENS_LIVE=1.
            implementation(libs.ktor.client.okhttp)
        }
    }
}
