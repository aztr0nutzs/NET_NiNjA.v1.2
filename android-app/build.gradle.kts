// Top-level build file for the Android application module.
// This file configures the Android Gradle Plugin (AGP) and Kotlin
// settings. The primary change here is to use a valid compileSdk
// version. Previously the project referenced API level 36, which
// does not exist. We use API 34 (Android 14) to ensure the project
// builds against a stable SDK.

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    // Use the latest stable SDK. API 34 corresponds to Android 14.
    compileSdk = 34

    // Required by AGP 8+. Keep aligned with the manifest package.
    namespace = "com.netninja"

    defaultConfig {
        applicationId = "com.netninja"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            // R8/ProGuard minification is currently disabled:
            // - Ktor/serialization dependencies are built with Kotlin metadata versions that are not parsed by the
            //   R8 bundled with AGP 8.5.2.
            // - The app embeds JVM-oriented Ktor server artifacts which also trigger missing-class checks under R8.
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
