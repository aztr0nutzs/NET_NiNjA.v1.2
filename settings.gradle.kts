pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
  versionCatalogs {
    create("libs") {
      from(files("gradle/libs.versions.toml"))
    }
  }
}

rootProject.name = "NET_NiNjA"

include(":app", ":core", ":server")
project(":app").projectDir = file("android-app")
project(":core").projectDir = file("core")
project(":server").projectDir = file("server")
