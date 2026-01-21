
plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
}

dependencies {
  implementation("org.xerial:sqlite-jdbc:3.45.1.0")
  implementation("org.jmdns:jmdns:3.5.8")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

kotlin {
  jvmToolchain(17)
}
