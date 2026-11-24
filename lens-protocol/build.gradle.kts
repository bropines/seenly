plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wire)
    id("lens.proto-editions")
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    jvm()
    android {
        namespace = "io.github.bropines.lens.protocol"
        compileSdk {
            // Compose 1.13 libraries need the 37.1 minor SDK (checked in release builds).
            version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.wire.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}

// Wire cannot parse `edition = "2023"`; it reads the downgraded copies instead.
// See build-logic/src/main/kotlin/lens/buildlogic/ProtoEditionsDowngrader.kt.
wire {
    sourcePath {
        srcDir("build/generated/proto-downgraded")
    }
    kotlin {
        // Bytes identical to protoc's, and so to what Chromium sends.
        encodeInTagOrder = true
    }
}

tasks.configureEach {
    if (name.startsWith("generate") && name.endsWith("Protos")) {
        dependsOn("downgradeProtoEditions")
    }
}

// ImageIO and Graphics2D must not reach for an X server on a build machine.
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
