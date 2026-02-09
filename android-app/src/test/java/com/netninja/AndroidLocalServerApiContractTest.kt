package com.netninja

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidLocalServerApiContractTest {
  @Test
  fun requiredEndpointsExistAndReturn200() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val server = AndroidLocalServer(context).apply {
      ipListOverride = { listOf("192.168.1.10") }
      reachabilityOverride = { _, _ -> true }
      hostnameOverride = { "test-host" }
      portScanOverride = { _, _ -> listOf(22) }
      localNetworkInfoOverride = {
        mapOf("name" to "Wi-Fi", "gateway" to "192.168.1.1", "linkUp" to true)
      }
      wifiEnabledOverride = { true }
      locationEnabledOverride = { true }
      permissionSnapshotOverride = {
        PermissionSnapshot(
          nearbyWifi = true,
          fineLocation = true,
          coarseLocation = true,
          networkState = true,
          wifiState = true,
          permissionPermanentlyDenied = false
        )
      }
    }

    // Ensure a device exists so /api/v1/devices/* endpoints can return 200.
    server.runScanForTest("192.168.1.0/24")
    val deviceId = server.devicesForTest().firstOrNull()?.id
    assertTrue("Expected at least one test device", !deviceId.isNullOrBlank())

    val port = ServerSocket(0).use { it.localPort }
    server.start(host = "127.0.0.1", port = port)

    fun get(uri: URI): Pair<Int, String> {
      val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 1500
        readTimeout = 1500
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
        connectTimeout = 1500
        readTimeout = 1500
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
        connectTimeout = 1500
        readTimeout = 1500
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

    try {
      val base = "http://127.0.0.1:$port"

      // Wait for readiness.
      val health = URI("$base/api/v1/system/info")
      val deadlineMs = System.currentTimeMillis() + 15_000
      var ready = false
      while (System.currentTimeMillis() < deadlineMs) {
        try {
          val (code, _) = get(health)
          if (code == 200) {
            ready = true
            break
          }
        } catch (_: Exception) {
          // Keep retrying until timeout.
        }
        Thread.sleep(200)
      }
      assertTrue("Server did not become ready at $health within timeout.", ready)

      assertEquals(200, get(URI("$base/api/v1/discovery/preconditions")).first)
      assertEquals(200, get(URI("$base/api/v1/discovery/progress")).first)
      assertEquals(200, postJson(URI("$base/api/v1/discovery/scan"), """{"subnet":"192.168.1.0/24","timeoutMs":80}""").first)
      assertEquals(200, get(URI("$base/api/v1/discovery/results")).first)
      assertEquals(200, postJson(URI("$base/api/v1/discovery/stop"), """{}""").first)

      assertEquals(200, get(URI("$base/api/v1/devices/$deviceId")).first)
      assertEquals(200, get(URI("$base/api/v1/devices/$deviceId/meta")).first)
      assertEquals(200, putJson(URI("$base/api/v1/devices/$deviceId/meta"), """{"name":"test-device"}""").first)
      assertEquals(200, get(URI("$base/api/v1/devices/$deviceId/history")).first)
      assertEquals(200, get(URI("$base/api/v1/devices/$deviceId/uptime")).first)

      assertEquals(200, get(URI("$base/api/v1/metrics")).first)

      // SSE endpoint: only assert that headers/status are available (do not consume the infinite stream).
      run {
        val conn = (URI("$base/api/v1/logs/stream").toURL().openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = 1500
          readTimeout = 500
        }
        try {
          assertEquals(200, conn.responseCode)
        } finally {
          conn.disconnect()
        }
      }

      assertEquals(200, get(URI("$base/api/v1/onvif/discover")).first)
      assertEquals(200, get(URI("$base/api/openclaw/nodes")).first)
      assertEquals(200, get(URI("$base/api/openclaw/stats")).first)
    } finally {
      server.stop()
    }
  }
}
