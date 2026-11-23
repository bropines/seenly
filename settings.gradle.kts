rootProject.name = "lens-kmp"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":lens-protocol")
include(":lens-overlay")
include(":google-translate")
include(":lens-cli")
include(":app:shared")
include(":app:android")
include(":app:desktop")
