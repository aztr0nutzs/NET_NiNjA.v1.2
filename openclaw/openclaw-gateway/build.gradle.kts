
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.openclaw.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openclaw.gateway"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
