plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
    // TrackingLockActivity is a Compose Activity (the PIN-gate lock screen).
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hereliesaz.quicloc.lockdown"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            // AndroidX AARs each ship a META-INF/<group>_<artifact>.version
            // metadata file. CameraX/Compose here pull some AndroidX libs at
            // versions that differ from the base, so these files would collide
            // in the AAB's root/ ("Modules 'base' and 'feature_findmyphone'
            // contain entry 'root/META-INF/androidx.tracing_tracing.version'
            // with different content" at packageReleaseBundle). They're pure
            // informational metadata — drop the module's copies so only the
            // base contributes them and bundletool sees no conflict.
            excludes += "/META-INF/*.version"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // The base app — provides LocationHelper, WhitelistManager, AppSettings,
    // the FindMyPhone bridge (EXTRA_* keys), etc. The base does NOT see this
    // module, so there's no circular dependency.
    implementation(project(":app"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Play Feature Delivery — TrackingLockActivity calls SplitCompat.installActivity
    // in attachBaseContext so this on-demand split's code/resources load in-process.
    // The base's copy isn't transitive (implementation), so declare it here too.
    implementation(libs.play.feature.delivery)

    // Compose — the lock screen (TrackingLockActivity / LockScreenUI) moved
    // here out of the base. Share the base's BOM via the version catalog so the
    // two modules can't drift to different Compose versions.
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)

    // CameraX — the intruder-photo capture. Lives here so CAMERA stays out of
    // the base install until this module is downloaded.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Pinned to match the base's explicit version (see app/build.gradle.kts)
    // so R8 sees one androidx.tracing copy, not a base/module version skew.
    implementation(libs.androidx.tracing)

    // Guava ListenableFuture needed for CameraX.
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.guava.listenablefuture)

    // MMS — panic mode sends the intruder photo via MMS. TrackingService here
    // is the only caller, but the dependency is ALSO declared in :app — its
    // AAR manifest carries a <provider> (MmsFileProvider) that must be part
    // of the base's own manifest/dex (content providers in on-demand feature
    // modules crash the app at every launch until the module installs; see
    // the comment on this same dependency in app/build.gradle.kts). Kept
    // here too, at the same pinned version, purely so this file still
    // compiles against the klinker classes.
    implementation(libs.android.smsmms)

    // Test-only. Matches :app's own test dependency set.
    testImplementation(libs.junit)
    testImplementation(libs.core.ktx)
    testImplementation(libs.robolectric)
}
