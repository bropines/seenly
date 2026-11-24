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
        versionCode = 1
        versionName = "0.1.0"
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
        // Google Play services allowed: on-device ML Kit, Google sign-in.
        create("google") { dimension = "distribution" }
        // Nothing proprietary on the device; installs side by side with google.
        create("foss") {
            dimension = "distribution"
            applicationIdSuffix = ".foss"
        }
    }

    buildFeatures { compose = true }
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
    // The menu shows app/shared's localized strings (its public Res).
    implementation(libs.compose.resources)

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
