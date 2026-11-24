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
        // Host tests see the merged resources and manifest: the README renders
        // (ReadmeShots) start a real activity and read Compose resources.
        withHostTest { isIncludeAndroidResources = true }
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
        named("androidHostTest").dependencies {
            // The README screenshots: real screens rendered by Robolectric's
            // native graphics, captured through Compose's test rule.
            implementation(libs.robolectric)
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            implementation(libs.ktor.client.mock)
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

// "What's new" shows the changelog of the commit the app was built from: the two files at the
// repository root are added to the Compose resources (as files/CHANGELOG*.md) at build time
// instead of being copied into the source tree, where a copy would go stale.
val bundleChangelog = tasks.register<Sync>("bundleChangelog") {
    from("src/commonMain/composeResources")
    from(rootProject.files("CHANGELOG.md", "CHANGELOG_RU.md")) { into("files") }
    into(layout.buildDirectory.dir("generated/changelogResources"))
}

compose.resources {
    packageOfResClass = "io.github.bropines.lens.app.resources"
    // Public: the Android app's screen-translator menu shows the same strings.
    publicResClass = true
    customDirectory(sourceSetName = "commonMain", directoryProvider = layout.dir(bundleChangelog.map { it.destinationDir }))
}

// The README screenshots (src/androidHostTest/.../readme): the real screens
// rendered from made-up data into build/readme-shots, in English and Russian.
// They run only through this task; scripts/readme_shots.py frames them.
//   ./gradlew :app:shared:readmeShots
val renderReadme = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "readmeShots" }

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    // Draw through Robolectric's hardware renderer: shadows and elevation as on a phone.
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    if (renderReadme) {
        filter.includeTestsMatching("io.github.bropines.lens.app.readme.*")
        outputs.upToDateWhen { false }
    } else {
        exclude("**/readme/**")
        // The renders may be the only host tests; a plain test run then has nothing to do.
        failOnNoDiscoveredTests = false
    }
}

tasks.register("readmeShots") {
    group = "documentation"
    description = "Renders the README screenshots into build/readme-shots."
    dependsOn(tasks.withType<Test>().matching { it.name.contains("AndroidHostTest", ignoreCase = true) })
}

// Open-source licences shown in "About": collected from the dependency graph into a
// resource file. Regenerate after changing dependencies:
//   ./gradlew :app:shared:exportLibraryDefinitions
aboutLibraries {
    export {
        outputFile = file("src/commonMain/composeResources/files/aboutlibraries.json")
    }
}
