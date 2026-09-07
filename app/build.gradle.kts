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

    if (isBuilding) {
        vD += 1
        vC += 1
        versionProps["VERSION_D"] = vD.toString()
        versionProps["VERSION_C"] = vC.toString()
        versionProps.store(FileOutputStream(versionPropsFile), null)
    }
    val finalVersionCode = vD
    val finalVersionName = "${vA}.${vB}.${vC}.${vD}"

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
    val releaseStoreType = signingProp("storeType", "QUICLOC_KEYSTORE_TYPE")
    val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

    val requireSigning = System.getenv("QUICLOC_REQUIRE_SIGNING")?.equals("true", ignoreCase = true) == true
    if (requireSigning && !hasReleaseSigning) {
        val missing = buildList {
            if (releaseStoreFile == null) add("QUICLOC_KEYSTORE_FILE")
            if (releaseStorePassword == null) add("QUICLOC_KEYSTORE_PASSWORD")
            if (releaseKeyAlias == null) add("QUICLOC_KEY_ALIAS")
            if (releaseKeyPassword == null) add("QUICLOC_KEY_PASSWORD")
        }
        throw GradleException(
            "QUICLOC_REQUIRE_SIGNING is set but the release signing config is incomplete — " +
                "missing: ${missing.joinToString(", ")}. Refusing to produce an unsigned release artifact."
        )
    }

    defaultConfig {
        applicationId = "com.hereliesaz.quicloc"
        minSdk = 26
        targetSdk = 37
        versionCode = finalVersionCode
        versionName = finalVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    dynamicFeatures += setOf(":feature_findmyphone")

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                if (releaseStoreType != null) storeType = releaseStoreType
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

tasks.register("printVersionName") {
    doLast { println(project.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().defaultConfig.versionName) }
}
tasks.register("printVersionCode") {
    doLast { println(project.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().defaultConfig.versionCode) }
}
tasks.register("printApplicationId") {
    doLast { println(project.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().defaultConfig.applicationId) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.location)
    implementation(libs.play.services.auth)
    implementation(libs.play.feature.delivery)
    implementation(libs.androidx.tracing)

    // ProfileInstaller/ProfileVerifier is base-process startup code. Force the
    // implementation into the base APK rather than allowing dynamic-feature
    // dependency ownership/deduplication to strand AbstractResolvableFuture in
    // an on-demand split. The direct coordinate deliberately avoids catalog
    // alias ambiguity while resolving this packaging failure.
    implementation("androidx.concurrent:concurrent-futures:1.3.0")

    implementation(libs.guava.listenablefuture)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.android.smsmms)

    testImplementation(libs.junit)
    testImplementation(libs.core.ktx)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
