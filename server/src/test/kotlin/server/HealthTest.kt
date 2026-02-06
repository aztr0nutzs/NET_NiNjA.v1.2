package server

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import kotlin.concurrent.thread

class HealthTest {
    @Test
    fun testSystemInfoEndpoint() {
        // Pick an available local port to avoid collisions on shared dev machines/CI.
        val port = ServerSocket(0).use { it.localPort }

        // Gradle's test working directory is typically the module directory (e.g. `server/`).
        // Resolve the repo's `web-ui/` folder robustly.
        val webUiDir = run {
            val fromModule = File("web-ui")
            if (fromModule.isDirectory) return@run fromModule
            val fromRepoRoot = File("..", "web-ui")
            if (fromRepoRoot.isDirectory) return@run fromRepoRoot
            // As a last resort, use the current directory so the server can still boot.
            File(".")
        }

        thread {
            try {
                startServer(webUiDir, "127.0.0.1", port)
            } catch (e: Exception) {
                // Print for test logs
                e.printStackTrace()
            }
        }

        val uri = URI("http://127.0.0.1:$port/api/v1/system/info")

        // Wait for server readiness with retries instead of a fixed sleep (reduces flakiness).
        val deadlineMs = System.currentTimeMillis() + 15_000
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                val url = uri.toURL()
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1500
                    readTimeout = 1500
                }
                conn.inputStream.use { input ->
                    val code = conn.responseCode
                    val body = input.bufferedReader().use { it.readText() }
                    assertTrue(code == 200, "Expected 200, got $code. Body: $body")
                    assertTrue(body.contains("os") || body.contains("time"), "Response body missing expected fields")
                    return
                }
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(250)
            }
        }

        throw AssertionError("Server did not become ready at $uri within timeout.", lastError)
    }
}
