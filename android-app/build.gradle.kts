import org.gradle.api.tasks.Copy

plugins {
  id("com.android.application")
  kotlin("android")
  kotlin("plugin.serialization")
}

android {
  namespace = "com.netninja"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.netninja"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "1.0.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
    debug {
      isMinifyEnabled = false
      // Avoid install/update conflicts with an existing release or differently-signed package.
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.webkit)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  // Embedded local API server (Android)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cors)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)

  implementation("com.google.code.gson:gson:2.10")
  implementation("fi.iki.elonen:nanohttpd-websocket:2.3.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.12.1")
  testImplementation("androidx.test:core-ktx:1.5.0")
  testImplementation(libs.kotlinx.coroutines.core)
}

tasks.register<Copy>("copyWebUiIntoAssets") {
  from(rootProject.file("web-ui"))
  into(project.file("src/main/assets/web-ui"))
}
tasks.named("preBuild").configure { dependsOn("copyWebUiIntoAssets") }
