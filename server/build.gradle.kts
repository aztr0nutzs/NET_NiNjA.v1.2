
plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  application
}

application {
  mainClass.set("server.MainKt")
}

dependencies {
  implementation(project(":core"))
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cors)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.server.websockets)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logback.classic)

  testImplementation(kotlin("test"))
}

kotlin {
  // Align with the JDK shipped with Android Studio (JBR 21) on this machine.
  // If you need JDK 17 for CI, pin it via toolchain downloads in settings.gradle(.kts).
  jvmToolchain(21)
}
