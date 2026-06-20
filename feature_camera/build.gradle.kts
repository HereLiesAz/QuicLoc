plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hereliesaz.quicloc.camera"
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
}

dependencies {
    // The base app — provides the IntruderCamera contract this module implements.
    implementation(project(":app"))

    implementation(libs.androidx.core.ktx)
    // ComponentActivity (the type crossing the IntruderCamera boundary).
    implementation(libs.androidx.activity.compose)

    // CameraX — lives here, not in the base, so CAMERA stays out of the base
    // install until this module is downloaded.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Guava ListenableFuture needed for CameraX.
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.guava.listenablefuture)
}
