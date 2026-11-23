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
        applicationId = "io.github.bropines.lens"
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
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
            applicationIdSuffix = ".dev"
        }
        release {
            signingConfig = signingConfigs.getByName("dev")
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
}
