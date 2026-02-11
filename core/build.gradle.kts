
plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
}

dependencies {
  implementation(libs.sqlite.jdbc)
  implementation(libs.jmdns)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation("org.jetbrains.kotlin:kotlin-test")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

kotlin {
  // Align with the JDK shipped with Android Studio (JBR 21) on this machine.
  // If you need JDK 17 for CI, pin it via toolchain downloads in settings.gradle(.kts).
  jvmToolchain(21)
}
