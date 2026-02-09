package server

import io.ktor.server.engine.ApplicationEngine
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val uiDir = locateWebUiDir(args)
  if (uiDir == null) {
    System.err.println("ERROR: web-ui directory not found. Pass path as first argument.")
    System.err.println("Usage: launcher <path-to-web-ui>")
    exitProcess(1)
  }

  val config = resolveServerConfig()
  val engine = startServer(
    webUiDir = uiDir,
    host = config.host,
    port = config.port,
    dbPath = config.dbPath,
    allowedOrigins = config.allowedOrigins,
    wait = false
  )

  Runtime.getRuntime().addShutdownHook(Thread {
    shutdownEngine(engine)
  })

  val baseUrl = "http://${config.host}:${config.port}/ui/ninja_mobile_new.html"
  waitForServerReady(config.host, config.port, Duration.ofSeconds(20))
  openBrowser(baseUrl)

  CountDownLatch(1).await()
}

private fun locateWebUiDir(args: Array<String>): File? {
  return when {
    args.isNotEmpty() -> File(args[0])
    File("web-ui").exists() -> File("web-ui")
    File("app/web-ui").exists() -> File("app/web-ui")
    File("../web-ui").exists() -> File("../web-ui")
    File("../app/web-ui").exists() -> File("../app/web-ui")
    else -> null
  }?.takeIf { it.exists() && it.isDirectory }
}

private fun waitForServerReady(host: String, port: Int, timeout: Duration) {
  val deadline = System.currentTimeMillis() + timeout.toMillis()
  val healthUri = URI("http://$host:$port/api/v1/system/info")
  while (System.currentTimeMillis() < deadline) {
    val ok = runCatching {
      val conn = (healthUri.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 1500
        readTimeout = 1500
      }
      try {
        conn.responseCode in 200..299
      } finally {
        conn.disconnect()
      }
    }.getOrDefault(false)
    if (ok) return
    Thread.sleep(250)
  }
  System.err.println("WARN: server did not report ready within ${timeout.seconds}s. Opening browser anyway.")
}

private fun openBrowser(url: String) {
  val opened = runCatching {
    if (Desktop.isDesktopSupported()) {
      Desktop.getDesktop().browse(URI(url))
      true
    } else {
      false
    }
  }.getOrDefault(false)

  if (!opened) {
    println("Open the dashboard in your browser: $url")
  }
}

private fun shutdownEngine(engine: ApplicationEngine) {
  runCatching { engine.stop(2000, 4000) }
}
