package com.netninja

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidLocalServerTest {
  @Test
  fun scanPopulatesResults() = runBlocking {
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

    server.runScanForTest("192.168.1.0/24")

    val devices = server.devicesForTest()
    assertEquals(1, devices.size)
    val device = devices.first()
    assertEquals("192.168.1.10", device.ip)
    assertTrue(device.online)
    assertEquals("test-host", device.name)
  }

  @Test
  fun scanBlockedWithoutPermissionsOrInterface() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val server = AndroidLocalServer(context).apply {
      canAccessWifiDetailsOverride = { false }
      interfaceInfoOverride = { null }
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

    server.scheduleScanForTest("192.168.1.0/24")

    val progress = server.scanProgressForTest()
    assertEquals("PRECONDITION_BLOCKED", progress.phase)
  }

  @Test
  fun scanPreconditionsBlockedWhenWifiDisabled() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val server = AndroidLocalServer(context).apply {
      wifiEnabledOverride = { false }
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

    val preconditions = server.scanPreconditionsForTest("192.168.1.0/24")

    assertEquals(false, preconditions.ready)
    assertEquals("wifi_disabled", preconditions.blocker)
  }
}
