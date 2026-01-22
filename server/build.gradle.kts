
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
  implementation("io.ktor:ktor-server-netty:2.3.7")
  implementation("io.ktor:ktor-server-core:2.3.7")
  implementation("io.ktor:ktor-server-cors:2.3.7")
  implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
  implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
  implementation("ch.qos.logback:logback-classic:1.4.14")
}
