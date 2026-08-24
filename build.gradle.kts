plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}

// This repo is synced by Google Drive for Desktop (G:\My Drive\...). Its
// virtual filesystem driver intermittently rejects Gradle's build-output file
// operations on Windows with "The parameter is incorrect" (e.g.
// signingConfigWriterDebug writing signing-config-data.json). Redirect every
// project's build directory to local disk so intermediates never round-trip
// through the Drive sync driver. No-op on CI / non-Windows, where
// LOCALAPPDATA isn't set.
val localAppData = System.getenv("LOCALAPPDATA")
if (localAppData != null) {
    val localBuildRoot = file("$localAppData/GradleBuilds/${rootProject.name}")
    allprojects {
        layout.buildDirectory.set(localBuildRoot.resolve(if (this === rootProject) "_root" else name))
    }
}
