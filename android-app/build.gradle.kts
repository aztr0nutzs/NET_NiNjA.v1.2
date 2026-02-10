// Top-level build file for the Android application module.
// This file configures the Android Gradle Plugin (AGP) and Kotlin
// settings. The primary change here is to use a valid compileSdk
// version. Previously the project referenced API level 36, which
// does not exist. We use API 34 (Android 14) to ensure the project
// builds against a stable SDK.

import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

// Keep build outputs out of `android-app/build/` to reduce Windows file-lock contention during `:app:clean`.
// This makes `.\gradlew clean ...` much more reliable in IDE-heavy environments.
// If you hit persistent file locks in this directory, bump the folder name to move outputs.
buildDir = rootProject.layout.buildDirectory.dir("android-app-out").get().asFile

val syncWebUiAssets by tasks.registering(Sync::class) {
    // Single source of truth for the UI bundle lives at repo root `web-ui/`.
    from(rootProject.file("web-ui"))
    into(layout.buildDirectory.dir("generated/assets/web-ui"))
}

android {
    // Use the latest stable SDK. API 34 corresponds to Android 14.
    compileSdk = 34

    // Required by AGP 8+. Keep aligned with the manifest package.
    namespace = "com.netninja"

    sourceSets {
        named("main") {
            // Package a generated `web-ui/` asset directory so runtime code can use
            // `file:///android_asset/web-ui/...` and `AssetManager.list("web-ui")`.
            assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/assets").get().asFile))
        }
    }

    defaultConfig {
        applicationId = "com.netninja"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Keep FV-01 output clean: `assembleRelease` can otherwise trigger `lintVital*` tasks which
        // emit Kotlin-metadata compatibility errors for some JVM-only dependencies bundled into the app.
        // `./gradlew lint` is still required and remains the primary lint gate.
        checkReleaseBuilds = false
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

tasks.named("preBuild") {
    dependsOn(syncWebUiAssets)
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
    implementation(libs.ktor.server.websockets)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

