plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.aboutlibraries)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    jvm()
    android {
        namespace = "io.github.bropines.lens.app.shared"
        compileSdk {
            // Compose 1.13 libraries need the 37.1 minor SDK (checked in release builds).
            version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.lensProtocol)
            implementation(projects.lensOverlay)
            implementation(projects.translators)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons.extended)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.coil.compose)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.filekit.coil)
            implementation(libs.materialkolor)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
            api(libs.androidx.room.runtime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.aboutlibraries.compose.m3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.compose)
            implementation(libs.androidx.browser)
            // QR and barcodes: plain Java, no Play services, so one decoder serves both flavors.
            implementation(libs.zxing.core)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

compose.resources {
    packageOfResClass = "io.github.bropines.lens.app.resources"
    publicResClass = false
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

// Open-source licences shown in "About": collected from the dependency graph into a
// resource file. Regenerate after changing dependencies:
//   ./gradlew :app:shared:exportLibraryDefinitions
aboutLibraries {
    export {
        outputFile = file("src/commonMain/composeResources/files/aboutlibraries.json")
    }
}
