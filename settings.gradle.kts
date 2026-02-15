pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "NET_NiNjA"

include(":app", ":core", ":server")
project(":app").projectDir = file("android-app")
project(":core").projectDir = file("core")
project(":server").projectDir = file("server")
