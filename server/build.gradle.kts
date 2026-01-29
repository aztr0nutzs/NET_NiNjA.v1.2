
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
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logback.classic)
}

kotlin {
  jvmToolchain(17)
}
