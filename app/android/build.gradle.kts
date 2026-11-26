plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.bropines.lens"
    // Compose 1.13 libraries need the 37.1 minor SDK.
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "io.github.bropines.seenly"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // CI passes the tag's version (-Plens.versionName=0.2.0) and a growing code (-Plens.versionCode=…).
        versionCode = providers.gradleProperty("lens.versionCode").map { it.toInt() }.getOrElse(1)
        versionName = providers.gradleProperty("lens.versionName").getOrElse("0.1.0")
    }

    signingConfigs {
        // A permanent, deliberately public development key: every machine and
        // CI build signs the same way, so a dev build installs over the last
        // one. Not for store releases.
        create("dev") {
            storeFile = file("dev.keystore")
            storePassword = "lens-dev"
            keyAlias = "lens-dev"
            keyPassword = "lens-dev"
        }
        // The real release key stays out of the repository: its path, alias and
        // passwords come from ~/.gradle/gradle.properties or the environment
        // (LENS_RELEASE_STORE_FILE and so on, for CI). Without them a release
        // build is signed with the dev key, which is fine for testing it.
        val releaseStore = providers.gradleProperty("lens.release.storeFile")
            .orElse(providers.environmentVariable("LENS_RELEASE_STORE_FILE")).orNull
        if (releaseStore != null) {
            create("release") {
                fun secret(property: String, env: String) =
                    providers.gradleProperty(property).orElse(providers.environmentVariable(env)).get()
                storeFile = file(releaseStore)
                storePassword = secret("lens.release.storePassword", "LENS_RELEASE_STORE_PASSWORD")
                keyAlias = secret("lens.release.keyAlias", "LENS_RELEASE_KEY_ALIAS")
                keyPassword = secret("lens.release.keyPassword", "LENS_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
            applicationIdSuffix = ".dev"
        }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("dev")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Adds Google ML Kit (src/google): text recognition and translation on the device, as a
        // fallback when Lens is unreachable, an always-offline mode and one more text translator.
        // It is closed-source and needs Google Play services.
        create("google") {
            dimension = "distribution"
            // ML Kit's translator is a 12-17 MB native library per ABI. x86 is emulators and a few
            // Chromebooks, and all four ABIs make a 70 MB APK; -Plens.allAbis builds one for an emulator.
            if (!providers.gradleProperty("lens.allAbis").isPresent) {
                ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            }
        }
        // Open-source code only: no ML Kit, no Play services (src/foss has the empty stand-in, and
        // the release workflow checks the APK for strays). Installs side by side with google.
        create("foss") {
            dimension = "distribution"
            applicationIdSuffix = ".foss"
        }
    }

    buildFeatures {
        compose = true
        // The updater asks which flavor's APK is this build's, and whether it is a debug build.
        buildConfig = true
    }
    // The README render of the screen translator's menu (src/test) reads resources through Robolectric.
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.app.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.filekit.dialogs.compose)
    implementation(libs.androidx.browser)
    // The screen translator scans and renders frames itself, and draws its menu with Compose.
    implementation(projects.lensProtocol)
    implementation(projects.lensOverlay)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    // The menu shows app/shared's localized strings (its public Res).
    implementation(libs.compose.resources)

    // On-device ML, google flavor only: see the flavors above and src/google.
    "googleImplementation"(libs.mlkit.text.latin)
    "googleImplementation"(libs.mlkit.text.chinese)
    "googleImplementation"(libs.mlkit.text.japanese)
    "googleImplementation"(libs.mlkit.text.korean)
    "googleImplementation"(libs.mlkit.text.devanagari)
    "googleImplementation"(libs.mlkit.language.id)
    "googleImplementation"(libs.mlkit.translate)
    "googleImplementation"(libs.play.services.base)
    // Language tags as the app spells them, for mapping to ML Kit's.
    "googleImplementation"(projects.translators)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.serialization.json)
}

// The README screenshot of the bubble menu, beside app/shared's (see its readmeShots):
//   ./gradlew readmeShots
val renderReadme = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "readmeShots" }
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    if (renderReadme) {
        filter.includeTestsMatching("io.github.bropines.lens.screen.BubbleMenuShots*")
        outputs.upToDateWhen { false }
    } else {
        exclude("**/BubbleMenuShots*")
        // The render is the only unit test; a plain test run then has nothing to do.
        failOnNoDiscoveredTests = false
    }
}
tasks.register("readmeShots") {
    group = "documentation"
    description = "Renders the README screenshot of the bubble menu into build/readme-shots."
    dependsOn("testGoogleDebugUnitTest")
}
