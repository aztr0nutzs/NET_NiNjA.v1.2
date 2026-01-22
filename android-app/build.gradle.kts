import org.gradle.api.tasks.Copy

plugins {
  id("com.android.application")
  kotlin("android")
  kotlin("plugin.serialization")
}

android {
  namespace = "com.netninja"
  compileSdk = 34

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
}

dependencies {
  implementation("androidx.core:core-ktx:1.12.0")
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("androidx.webkit:webkit:1.10.0")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

  // Embedded local API server (Android)
  implementation("io.ktor:ktor-server-cio:2.3.7")
  implementation("io.ktor:ktor-server-core:2.3.7")
  implementation("io.ktor:ktor-server-cors:2.3.7")
  implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
  implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
}

tasks.register<Copy>("copyWebUiIntoAssets") {
  from(rootProject.file("web-ui"))
  into(project.file("src/main/assets/web-ui"))
}
tasks.named("preBuild").configure { dependsOn("copyWebUiIntoAssets") }
