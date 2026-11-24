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
        maven {
            url = uri("https://raw.githubusercontent.com/HereLiesAz/AzNavRail/update-maven/")
            content { includeGroup("com.github.HereLiesAz.AzNavRail") }
        }
    }
}
rootProject.name = "QuicLoc"
include(":app")
include(":feature_findmyphone")
