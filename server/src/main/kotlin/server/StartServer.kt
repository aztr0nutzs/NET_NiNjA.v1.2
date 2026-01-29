package server

import core.persistence.Db
import core.persistence.DeviceDao
import core.persistence.EventDao
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import java.io.File

fun startStaticServer(
  uiDir: File,
  port: Int = 8787,
  dbPath: String = "netninja.db"
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
  require(uiDir.exists() && uiDir.isDirectory) { "web-ui directory not found: ${uiDir.absolutePath}" }

  val conn = Db.open(dbPath)
  val deviceDao = DeviceDao(conn)
  val eventDao = EventDao(conn)

  val engine = embeddedServer(Netty, port = port) {
    routing {
      staticFiles("/", uiDir) {
        default("index.html")
        cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 300)) }
      }
      get("/health") { call.respondText("OK", ContentType.Text.Plain) }
    }
  }

  Runtime.getRuntime().addShutdownHook(Thread {
    runCatching { engine.stop(1000, 2000) }
    runCatching { conn.close() }
  })

  engine.start(wait = true)
  return engine
}
