package server

import kotlin.test.Test
import kotlin.test.assertTrue
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import java.io.File

class HealthTest {
    @Test
    fun testSystemInfoEndpoint() {
        // Start server on test port 8789 in background
        val port = 8789
        thread {
            try {
                startServer(File("web-ui"), "127.0.0.1", port)
            } catch (e: Exception) {
                // Print for test logs
                e.printStackTrace()
            }
        }

        // Wait for server to start
        Thread.sleep(2500)

        val url = URL("http://127.0.0.1:$port/api/v1/system/info")
        with(url.openConnection() as HttpURLConnection) {
            requestMethod = "GET"
            connectTimeout = 3000
            readTimeout = 3000
            connect()
            val code = responseCode
            val body = inputStream.bufferedReader().use { it.readText() }
            assertTrue(code == 200, "Expected 200, got $code. Body: $body")
            assertTrue(body.contains("os") || body.contains("time"), "Response body missing expected fields")
        }
    }
}