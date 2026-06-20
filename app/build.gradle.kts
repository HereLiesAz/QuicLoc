import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}



android {
    namespace = "com.hereliesaz.quicloc"
    compileSdk = 37

    val versionPropsFile = file("version.properties")
    val versionProps = Properties()

    if (versionPropsFile.canRead()) {
        versionProps.load(FileInputStream(versionPropsFile))
    } else {
        versionProps["VERSION_A"] = "1"
        versionProps["VERSION_B"] = "0"
        versionProps["VERSION_C"] = "0"
        versionProps["VERSION_D"] = "0"
    }

    val runTasks = gradle.startParameter.taskNames
    val isBuilding = runTasks.any { it.contains("assemble") || it.contains("bundle") }

    var vA = versionProps["VERSION_A"].toString().toInt()
    var vB = versionProps["VERSION_B"].toString().toInt()
    var vC = versionProps["VERSION_C"].toString().toInt()
    var vD = versionProps["VERSION_D"].toString().toInt()

    // CI passes -PversionBuild=<n> (e.g. `git rev-list --count HEAD`) to force a
    // deterministic, strictly-increasing versionCode for Play uploads. When the
    // override is present we do NOT touch version.properties, so the build stays
    // reproducible and the working tree clean. With no override, keep the local
    // auto-increment behavior unchanged.
    val versionOverride = (project.findProperty("versionBuild") as String?)?.toIntOrNull()

    val finalVersionCode: Int
    val finalVersionName: String
    if (versionOverride != null) {
        finalVersionCode = versionOverride
        finalVersionName = "${vA}.${vB}.${vC}.${versionOverride}"
    } else {
        if (isBuilding) {
            vD += 1
            vC += 1
            versionProps["VERSION_D"] = vD.toString()
            versionProps["VERSION_C"] = vC.toString()
            versionProps.store(FileOutputStream(versionPropsFile), null)
        }
        finalVersionCode = vD
        finalVersionName = "${vA}.${vB}.${vC}.${vD}"
    }

    // ---- Release signing -------------------------------------------------
    // Reads a git-ignored keystore.properties (local dev) or environment
    // variables (CI). Signing is only wired in when material is present, so an
    // unsigned `assembleRelease` (e.g. a PR build with no secrets) still builds.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.canRead()) FileInputStream(keystorePropsFile).use { load(it) }
    }
    fun signingProp(key: String, env: String): String? =
        (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
    val releaseStoreFile = signingProp("storeFile", "QUICLOC_KEYSTORE_FILE")
    val releaseStorePassword = signingProp("storePassword", "QUICLOC_KEYSTORE_PASSWORD")
    val releaseKeyAlias = signingProp("keyAlias", "QUICLOC_KEY_ALIAS")
    val releaseKeyPassword = signingProp("keyPassword", "QUICLOC_KEY_PASSWORD")
    // Only sign when every credential is present; otherwise fall back to an
    // unsigned release build rather than failing with a half-configured config.
    val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

    defaultConfig {
        applicationId = "com.hereliesaz.quicloc"
        minSdk = 26
        targetSdk = 36
        versionCode = finalVersionCode
        versionName = finalVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // On-demand dynamic feature modules. The ENTIRE find-my-phone / lockdown
    // feature (tracking service, lock screen, Device Admin receiver, intruder
    // camera) lives in :feature_findmyphone, so the base install declares none
    // of its sensitive surface until the user sets up find-my-phone (see
    // FindMyPhone). The module fuses into sideload APKs (dist:fusing include).
    dynamicFeatures += setOf(":feature_findmyphone")

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // Resolve relative paths against the repo root, where
                // keystore.properties lives (CI passes an absolute path).
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only signed when keystore material is supplied (see signingConfigs).
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.register("printVersionName") {
    doLast {
        println(project.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().defaultConfig.versionName)
    }
}

tasks.register("printApplicationId") {
    doLast {
        println(project.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().defaultConfig.applicationId)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.location)
    // Phone Number Hint API — lets the user one-tap-pick their own number
    // from a system bottom sheet, no permissions required.
    implementation(libs.play.services.auth)

    // Play Feature Delivery — SplitInstall (download the on-demand
    // :feature_findmyphone module) + SplitCompat (load it in-process). The
    // lockdown feature's heavy deps (CameraX, klinker MMS) live in the module,
    // not the base.
    implementation(libs.play.feature.delivery)

    // Material Icons
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // Encrypted SharedPreferences — keys managed by Android Keystore
    implementation(libs.androidx.security.crypto)

    // Biometric authentication
    implementation(libs.androidx.biometric)

    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.core.ktx)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
