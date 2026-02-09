package server

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

class ApiContractTest {
  @Test
  fun requiredEndpointsExistAndReturn200() {
    val port = ServerSocket(0).use { it.localPort }

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

    thread(name = "netninja-server-$port", isDaemon = true) {
      try {
        startServer(
          webUiDir = webUiDir,
          host = "127.0.0.1",
          port = port,
          dbPath = dbFile.absolutePath,
          allowedOrigins = listOf("http://127.0.0.1:$port", "http://localhost:$port")
        )
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }

    fun get(uri: URI): Pair<Int, String> {
      val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 3000
        readTimeout = 5000
      }
      return try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to body
      } finally {
        conn.disconnect()
      }
    }

    fun postJson(uri: URI, body: String): Pair<Int, String> {
      val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        connectTimeout = 3000
        readTimeout = 5000
      }
      return try {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to response
      } finally {
        conn.disconnect()
      }
    }

    fun putJson(uri: URI, body: String): Pair<Int, String> {
      val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "PUT"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        connectTimeout = 3000
        readTimeout = 5000
      }
      return try {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to response
      } finally {
        conn.disconnect()
      }
    }

    // Wait for readiness.
    val deadlineMs = System.currentTimeMillis() + 15_000
    var lastError: Exception? = null
    val base = "http://127.0.0.1:$port"
    val health = URI("$base/api/v1/system/info")
    while (System.currentTimeMillis() < deadlineMs) {
      try {
        val (code, _) = get(health)
        if (code == 200) break
      } catch (e: Exception) {
        lastError = e
        Thread.sleep(250)
      }
    }
    if (System.currentTimeMillis() >= deadlineMs) {
      throw AssertionError("Server did not become ready at $health within timeout.", lastError)
    }

    assertTrue(get(URI("$base/api/v1/discovery/preconditions")).first == 200)
    assertTrue(get(URI("$base/api/v1/discovery/progress")).first == 200)
    assertTrue(postJson(URI("$base/api/v1/discovery/scan"), """{"subnet":"127.0.0.0/30","timeoutMs":80}""").first == 200)
    assertTrue(get(URI("$base/api/v1/discovery/results")).first == 200)
    assertTrue(postJson(URI("$base/api/v1/discovery/stop"), """{}""").first == 200)

    // Ensure at least one device is present so /api/v1/devices/* can return 200.
    val resultsUri = URI("$base/api/v1/discovery/results")
    val idRegex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
    val resultsDeadlineMs = System.currentTimeMillis() + 15_000
    var deviceId: String? = null
    while (System.currentTimeMillis() < resultsDeadlineMs) {
      val (code, body) = get(resultsUri)
      if (code == 200) {
        deviceId = idRegex.find(body)?.groupValues?.getOrNull(1)
        if (!deviceId.isNullOrBlank()) break
      }
      Thread.sleep(200)
    }
    assertTrue(!deviceId.isNullOrBlank(), "Expected at least one device in $resultsUri within timeout.")
    val encodedId = java.net.URLEncoder.encode(deviceId!!, Charsets.UTF_8)
    val deviceUri = URI("$base/api/v1/devices/$encodedId")
    val (deviceCode, deviceBody) = get(deviceUri)
    assertTrue(deviceCode == 200, "Expected 200 for $deviceUri, got $deviceCode. Body: $deviceBody")

    assertTrue(get(URI("$base/api/v1/devices/$encodedId/meta")).first == 200)
    assertTrue(putJson(URI("$base/api/v1/devices/$encodedId/meta"), """{"name":"loopback"}""").first == 200)
    assertTrue(get(URI("$base/api/v1/devices/$encodedId/history")).first == 200)
    assertTrue(get(URI("$base/api/v1/devices/$encodedId/uptime")).first == 200)

    assertTrue(get(URI("$base/api/v1/metrics")).first == 200)

    // SSE endpoint: only assert that headers/status are available (do not consume the infinite stream).
    run {
      val conn = (URI("$base/api/v1/logs/stream").toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 1500
        readTimeout = 500
      }
      try {
        val code = conn.responseCode
        assertTrue(code == 200, "Expected 200 for /api/v1/logs/stream, got $code")
      } finally {
        conn.disconnect()
      }
    }

    assertTrue(get(URI("$base/api/v1/onvif/discover")).first == 200)
    assertTrue(get(URI("$base/api/openclaw/nodes")).first == 200)
    assertTrue(get(URI("$base/api/openclaw/stats")).first == 200)
  }
}
