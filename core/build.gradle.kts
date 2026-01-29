
plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
}

dependencies {
  implementation(libs.sqlite.jdbc)
  implementation(libs.jmdns)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
}

kotlin {
  jvmToolchain(17)
}
