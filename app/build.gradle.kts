// app/build.gradle.kts — Build configuration for the :app module.
// This is where we declare the SDK versions, application ID, and all library dependencies.

plugins {
    // Apply the Android Application plugin — this module produces an .apk file.
    id("com.android.application")

    // Apply the Kotlin Android plugin — enables writing Kotlin code in this module.
    id("org.jetbrains.kotlin.android")
}

android {
    // The namespace is used for generated R class and BuildConfig.
    // It replaces the old "package" attribute in AndroidManifest.xml.
    namespace = "com.uyghur.keyboard"

    // compileSdk = the API level used to COMPILE your code.
    // You can use APIs up to this level, but must handle unavailability on older devices.
    compileSdk = 34

    defaultConfig {
        // applicationId = the unique identifier for your app on the Play Store and on-device.
        // This is what Android uses to distinguish your app from all others.
        applicationId = "com.uyghur.keyboard"

        // minSdk = the oldest Android version your app supports.
        // API 26 = Android 8.0 (Oreo). Covers ~95% of active devices.
        minSdk = 26

        // targetSdk = the API level you've tested against. Android enforces newer
        // behavior policies (permissions, background restrictions) based on this value.
        targetSdk = 34

        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "android"
            keyAlias = "release"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // ProGuard/R8 minification — disabled for now to simplify debugging.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // AGP 8.x requires Java 17 for compilation.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Tell the Kotlin compiler to also target JVM 17 bytecode.
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ─── ONNX Runtime ───────────────────────────────────────────────────
    // Microsoft's ONNX Runtime for Android — runs .onnx ML models on-device.
    // Supports CPU and (on supported hardware) GPU/NNAPI acceleration.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")

    // ─── AndroidX Core ──────────────────────────────────────────────────
    // core-ktx provides Kotlin extension functions for common Android APIs
    // (e.g., SharedPreferences, Bundle, etc.) making code more concise.
    implementation("androidx.core:core-ktx:1.12.0")

    // appcompat brings backward-compatible versions of UI elements and resources.
    // Even though we don't have an Activity, the IME service benefits from
    // compat resource handling.
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ─── Coroutines ──────────────────────────────────────────────────────
    // Kotlin coroutines for running ASR inference on a background thread
    // without blocking the keyboard UI.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
