package server

import io.ktor.server.engine.ApplicationEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertTrue

class AuthEnforcementTest {
  @Test
  fun apiRejectsRequestsWithoutTokenWhenConfigured() {
    val port = ServerSocket(0).use { it.localPort }
    val token = "test-token"

    val webUiDir = run {
      val fromModule = File("web-ui")
      if (fromModule.isDirectory) return@run fromModule
      val fromRepoRoot = File("..", "web-ui")
      if (fromRepoRoot.isDirectory) return@run fromRepoRoot
      File(".")
    }

    val dbFile = kotlin.runCatching { File.createTempFile("netninja-test-", ".db") }
      .getOrElse { File("build/tmp/netninja-test-$port.db").apply { parentFile?.mkdirs() } }
      .apply { deleteOnExit() }

    val engine: ApplicationEngine = startServer(
      webUiDir = webUiDir,
      host = "127.0.0.1",
      port = port,
      dbPath = dbFile.absolutePath,
      allowedOrigins = listOf("http://127.0.0.1:$port", "http://localhost:$port"),
      authToken = token,
      wait = false
    )

    try {
      val uri = URI("http://127.0.0.1:$port/api/v1/metrics")
      val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 2000
        readTimeout = 2000
      }
      try {
        val code = conn.responseCode
        assertTrue(code == 401, "Expected 401 for missing token, got $code")
      } finally {
        conn.disconnect()
      }
    } finally {
      runCatching { engine.stop(1000, 2000) }
    }
  }
}

