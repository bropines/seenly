plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    jvm()
    android {
        namespace = "io.github.bropines.lens.overlay"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.lensProtocol)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions)
            // Real Lens answers, saved by the CLI, replayed as fixtures.
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            // Skia's native library for this machine: the JVM tests decode
            // patches and paint whole overlays, which needs a real backend.
            implementation(compose.desktop.currentOs)
        }
        named("androidHostTest").dependencies {
            // Android's real Bitmap, Canvas and text layout on the host, so
            // the Android side of the renderer is tested by running it.
            implementation("org.robolectric:robolectric:4.16")
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
