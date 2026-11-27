// WeChat's QR decoder with the part of OpenCV it needs, built from source for every ABI
// (src/main/cpp/CMakeLists.txt). The sources are downloaded by CMake, checked against their
// SHA-256, and kept outside the repository: the archives in Gradle's cache, unpacked in build/.
plugins {
    alias(libs.plugins.android.library)
}

val allAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
// -Plens.abi=arm64-v8a, as for the app: a quick local build compiles OpenCV for that ABI alone.
val onlyAbi: String? = providers.gradleProperty("lens.abi").orNull

android {
    namespace = "io.github.bropines.wechatqr"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += onlyAbi?.let { listOf(it) } ?: allAbis }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DQR_DOWNLOADS=${gradle.gradleUserHomeDir}/caches/seenly-opencv",
                    "-DQR_SOURCES=${layout.buildDirectory.get().asFile}/opencv-src",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
