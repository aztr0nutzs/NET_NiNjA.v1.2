package com.netninja.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ServerConfigTest {

  private lateinit var context: Context
  private lateinit var config: ServerConfig

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    config = ServerConfig(context)
    config.resetToDefaults()
  }

  @Test
  fun defaultValues_areCorrect() {
    assertEquals(300, config.scanTimeoutMs)
    assertEquals(48, config.scanConcurrency)
    assertEquals(4096, config.maxScanTargets)
    assertEquals(60000L, config.minScanIntervalMs)
    assertEquals(250, config.portScanTimeoutMs)
    assertEquals(2, config.portScanRetries)
  }

  @Test
  fun setAndGet_intValue() {
    config.set("scan_timeout_ms", 500)
    assertEquals(500, config.scanTimeoutMs)
  }

  @Test
  fun setAndGet_longValue() {
    config.set("min_scan_interval_ms", 120000L)
    assertEquals(120000L, config.minScanIntervalMs)
  }

  @Test
  fun setAndGet_stringValue() {
    config.set("server_host", "0.0.0.0")
    assertEquals("0.0.0.0", config.serverHost)
  }

  @Test
  fun resetToDefaults_restoresOriginalValues() {
    config.set("scan_timeout_ms", 999)
    config.resetToDefaults()
    assertEquals(300, config.scanTimeoutMs)
  }

  @Test
  fun commonPorts_containsExpectedPorts() {
    val ports = config.commonPorts
    assertTrue(ports.contains(22))
    assertTrue(ports.contains(80))
    assertTrue(ports.contains(443))
  }

  @Test
  fun reachabilityProbePorts_containsCommonPorts() {
    val ports = config.reachabilityProbePorts
    assertTrue(ports.contains(80))
    assertTrue(ports.contains(443))
    assertTrue(ports.contains(22))
  }
}
