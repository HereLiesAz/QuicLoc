plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream

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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

tasks.register("printVersionName") {
    doLast {
        println(android.defaultConfig.versionName)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Encrypted SharedPreferences — keys managed by Android Keystore
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
