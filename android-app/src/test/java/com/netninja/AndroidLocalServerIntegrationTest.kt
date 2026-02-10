package com.netninja

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidLocalServerIntegrationTest {

  private fun token(ctx: Context): String = LocalApiAuth.getOrCreateToken(ctx)

  private fun http(
    method: String,
    url: String,
    token: String? = null,
    body: String? = null,
    contentType: String = "application/json",
    connectTimeoutMs: Int = 2_000,
    readTimeoutMs: Int = 5_000
  ): Pair<Int, String> {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = connectTimeoutMs
      readTimeout = readTimeoutMs
      instanceFollowRedirects = false
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Connection", "close")
      if (token != null) {
        setRequestProperty("Authorization", "Bearer $token")
      }
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", contentType)
      }
    }
    if (body != null) {
      conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }
    val code = conn.responseCode
    val stream = if (code in 200..399) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    conn.disconnect()
    return code to text
  }

  private fun waitForServerUp(port: Int, token: String, timeoutMs: Long = 4_000L) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      try {
        val (code, _) = http(
          "GET",
          "http://127.0.0.1:$port/api/v1/system/info",
          token = token,
          connectTimeoutMs = 250,
          readTimeoutMs = 500
        )
        if (code == 200) return
      } catch (_: Exception) {
        // server may not be bound yet
      }
      Thread.sleep(50)
    }
    error("server did not become ready in ${timeoutMs}ms")
  }

  private fun waitForScanComplete(
    port: Int,
    token: String,
    expectedSubnet: String? = null,
    timeoutMs: Long = 8_000L
  ): JSONObject {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: JSONObject? = null
    while (System.currentTimeMillis() < deadline) {
      val (code, body) = http("GET", "http://127.0.0.1:$port/api/v1/discovery/progress", token)
      if (code == 200 && body.isNotBlank()) {
        val json = JSONObject(body)
        last = json
        if (
          json.optString("phase") == "COMPLETE" &&
          (expectedSubnet == null || json.optString("subnet") == expectedSubnet)
        ) {
          return json
        }
        if (json.optString("phase") == "CANCELLED") {
          // allow the follow-up scan to finish
        }
      }
      Thread.sleep(75)
    }
    return last ?: JSONObject()
  }

  @Test
  fun localApiRejectsUnauthorizedRequests() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val server = AndroidLocalServer(ctx)
    val port = server.startForTest()
    try {
      val t = token(ctx)
      waitForServerUp(port, t)

      val (code1, _) = http("GET", "http://127.0.0.1:$port/api/v1/system/info", token = null)
      assertEquals(401, code1)

      val (code2, body2) = http("GET", "http://127.0.0.1:$port/api/v1/system/info", token = t)
      assertEquals(200, code2)
      assertTrue(body2.contains("\"platform\""))
    } finally {
      server.stop()
    }
  }

  @Test
  fun scanRequestPersistsDeviceToDatabase() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val server = AndroidLocalServer(ctx).apply {
      // Ensure tests are not blocked by the battery-protection rate limit.
      minScanIntervalMsOverride = 0L

      wifiEnabledOverride = { true }
      locationEnabledOverride = { true }
      permissionSnapshotOverride = {
        PermissionSnapshot(
          nearbyWifi = true,
          fineLocation = true,
          coarseLocation = false,
          networkState = true,
          wifiState = true,
          permissionPermanentlyDenied = false
        )
      }

      localNetworkInfoOverride = {
        mapOf("name" to "TestNet", "gateway" to "10.0.0.1", "dns" to null, "iface" to "wlan0", "linkUp" to true)
      }
      ipListOverride = { _ -> listOf("10.0.0.2") }
      arpTableOverride = {
        listOf(
          Device(
            id = "aa:bb:cc:dd:ee:ff",
            ip = "10.0.0.2",
            name = "10.0.0.2",
            online = true,
            lastSeen = System.currentTimeMillis(),
            mac = "aa:bb:cc:dd:ee:ff"
          )
        )
      }
      reachabilityOverride = { _, _ -> true }
      hostnameOverride = { _ -> "test-host" }
      vendorLookupOverride = { _ -> "TestVendor" }
      portScanOverride = { _, _ -> listOf(22, 80) }
    }

    val port = server.startForTest()
    val t = token(ctx)
    try {
      waitForServerUp(port, t)
      val (codeScan, _) = http(
        "POST",
        "http://127.0.0.1:$port/api/v1/discovery/scan",
        token = t,
        body = """{"subnet":"10.0.0.0/30","timeoutMs":50}"""
      )
      assertEquals(200, codeScan)

      val progress = waitForScanComplete(port, t)
      assertEquals("COMPLETE", progress.optString("phase"))

      val (codeResults, bodyResults) = http("GET", "http://127.0.0.1:$port/api/v1/discovery/results", token = t)
      assertEquals(200, codeResults)
      assertTrue(bodyResults.contains("10.0.0.2"))
    } finally {
      server.stop()
    }

    // Restart server: ensure persisted device is loaded back from SQLite.
    val server2 = AndroidLocalServer(ctx)
    val port2 = server2.startForTest()
    try {
      waitForServerUp(port2, t)
      val (codeResults2, bodyResults2) = http("GET", "http://127.0.0.1:$port2/api/v1/discovery/results", token = t)
      assertEquals(200, codeResults2)
      assertTrue(bodyResults2.contains("10.0.0.2"))
    } finally {
      server2.stop()
    }
  }

  @Test
  fun permissionDenialReturnsNotReadyPreconditions() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val t = token(ctx)
    val server = AndroidLocalServer(ctx).apply {
      wifiEnabledOverride = { true }
      locationEnabledOverride = { true }
      permissionSnapshotOverride = {
        PermissionSnapshot(
          nearbyWifi = false,
          fineLocation = false,
          coarseLocation = false,
          networkState = true,
          wifiState = true,
          permissionPermanentlyDenied = false
        )
      }
    }
    val port = server.startForTest()
    try {
      waitForServerUp(port, t)
      val (code, body) = http("GET", "http://127.0.0.1:$port/api/v1/discovery/preconditions", token = t)
      assertEquals(200, code)
      val json = JSONObject(body)
      assertFalse(json.optBoolean("ready"))
      assertEquals("permission_missing", json.optString("blocker"))
      assertEquals("app_settings", json.optString("fixAction"))
    } finally {
      server.stop()
    }
  }

  @Test
  fun concurrentScanRequestsPreferLastSubnet() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val server = AndroidLocalServer(ctx).apply {
      minScanIntervalMsOverride = 0L
      wifiEnabledOverride = { true }
      locationEnabledOverride = { true }
      permissionSnapshotOverride = {
        PermissionSnapshot(
          nearbyWifi = true,
          fineLocation = true,
          coarseLocation = false,
          networkState = true,
          wifiState = true,
          permissionPermanentlyDenied = false
        )
      }
      localNetworkInfoOverride = {
        mapOf("name" to "TestNet", "gateway" to "10.0.0.1", "dns" to null, "iface" to "wlan0", "linkUp" to true)
      }
      ipListOverride = { cidr ->
        when {
          cidr.startsWith("10.0.0.0/30") -> listOf("10.0.0.2")
          else -> listOf("10.0.1.2")
        }
      }
      arpTableOverride = { emptyList() }
      reachabilityOverride = { _, _ -> true }
      portScanOverride = { ip, _ ->
        if (ip == "10.0.0.2") Thread.sleep(300) // make the first scan slow so cancellation is observable
        listOf(80)
      }
    }

    val port = server.startForTest()
    val t = token(ctx)
    try {
      waitForServerUp(port, t)
      val t1 = thread(start = true) {
        http(
          "POST",
          "http://127.0.0.1:$port/api/v1/discovery/scan",
          token = t,
          body = """{"subnet":"10.0.0.0/30","timeoutMs":50}"""
        )
      }
      Thread.sleep(30)
      val t2 = thread(start = true) {
        http(
          "POST",
          "http://127.0.0.1:$port/api/v1/discovery/scan",
          token = t,
          body = """{"subnet":"10.0.1.0/30","timeoutMs":50}"""
        )
      }
      t1.join()
      t2.join()

      val progress = waitForScanComplete(port, t, expectedSubnet = "10.0.1.0/30", timeoutMs = 10_000L)
      assertEquals("COMPLETE", progress.optString("phase"))
      assertEquals("10.0.1.0/30", progress.optString("subnet"))

      val (codeResults, bodyResults) = http("GET", "http://127.0.0.1:$port/api/v1/discovery/results", token = t)
      assertEquals(200, codeResults)
      val arr = JSONArray(bodyResults)
      assertNotNull(arr)
    } finally {
      server.stop()
    }
  }
}
