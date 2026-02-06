// Top-level build file for the Android application module.
// This file configures the Android Gradle Plugin (AGP) and Kotlin
// settings. The primary change here is to use a valid compileSdk
// version. Previously the project referenced API level 36, which
// does not exist. We use API 34 (Android 14) to ensure the project
// builds against a stable SDK.

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    // Use the latest stable SDK. API 34 corresponds to Android 14.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.netninja"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    // Kotlin standard library and AndroidX support libraries. At least one
    // dependency is required for Gradle to configure the Kotlin plugin.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("androidx.core:core-ktx:1.10.1")
}