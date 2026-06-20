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

    // Guava ListenableFuture needed for CameraX.
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.guava.listenablefuture)

    // MMS — panic mode sends the intruder photo via MMS. Only TrackingService
    // uses klinker, so the dependency lives here, not in the base.
    implementation(libs.android.smsmms)
}
