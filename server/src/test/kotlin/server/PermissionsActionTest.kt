package server

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.concurrent.thread

class PermissionsActionTest {
  @Test
  fun permissionsActionUnsupportedReturnsStructuredResponse() {
    val originalOsName = System.getProperty("os.name")
    try {
      // Force the handler down the unsupported path so we don't open real system settings during tests.
      System.setProperty("os.name", "Linux")

      val port = ServerSocket(0).use { it.localPort }

      val webUiDir = run {
        val fromModule = File("web-ui")
        if (fromModule.isDirectory) return@run fromModule
        val fromRepoRoot = File("..", "web-ui")
        if (fromRepoRoot.isDirectory) return@run fromRepoRoot
        File(".")
      }

      thread {
        try {
          startServer(webUiDir, "127.0.0.1", port)
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }

      val uri = URI("http://127.0.0.1:$port/api/v1/system/permissions/action")

      val body = """{"action":"OPEN_SETTINGS","context":"CAMERA"}"""

      val deadlineMs = System.currentTimeMillis() + 15_000
      var lastError: Exception? = null
      while (System.currentTimeMillis() < deadlineMs) {
        try {
          val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 1500
            readTimeout = 1500
          }
          conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

          val code = conn.responseCode
          val stream = if (code in 200..299) conn.inputStream else conn.errorStream
          val response = stream.bufferedReader().use { it.readText() }

          assertTrue(code == 200, "Expected 200, got $code. Body: $response")
          assertTrue(response.contains("\"platform\""), "Missing platform in response: $response")
          assertTrue(response.contains("\"ok\""), "Missing ok in response: $response")
          assertTrue(response.contains("\"message\""), "Missing message in response: $response")
          assertTrue(response.contains("unsupported", ignoreCase = true), "Expected unsupported message: $response")
          return
        } catch (e: Exception) {
          lastError = e
          Thread.sleep(250)
        }
      }

      throw AssertionError("Server did not become ready at $uri within timeout.", lastError)
    } finally {
      if (originalOsName == null) System.clearProperty("os.name") else System.setProperty("os.name", originalOsName)
    }
  }
}

