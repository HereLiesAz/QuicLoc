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
    compileSdk = 36

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

    if (isBuilding) {
        vD += 1
        vC += 1
        versionProps["VERSION_D"] = vD.toString()
        versionProps["VERSION_C"] = vC.toString()
        versionProps.store(FileOutputStream(versionPropsFile), null)
    }

    val finalVersionName = "${vA}.${vB}.${vC}.${vD}"
    val finalVersionCode = vD

    defaultConfig {
        applicationId = "com.hereliesaz.quicloc"
        minSdk = 26
        targetSdk = 36
        versionCode = finalVersionCode
        versionName = finalVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        val appExtension = extensions.getByType<com.android.build.api.dsl.ApplicationExtension>()
        println(appExtension.defaultConfig.versionName)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.location)

    // MMS Sending
    implementation(libs.android.smsmms)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Guava ListenableFuture needed for CameraX
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.guava.listenablefuture)

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
