pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "QuicLoc"
include(":app")
// Find-my-phone / lockdown feature is currently DISABLED (kept in the repo,
// not shipped). Re-enable by uncommenting this include, re-adding it to the
// app's `dynamicFeatures`, and flipping `FindMyPhone.ENABLED` to true.
// include(":feature_findmyphone")
