package core.alerts

import core.model.Device
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangeDetectorTest {
  @Test
  fun detectsNewDevice() {
    val now = Device(id = "d1", ip = "192.168.1.10")
    assertEquals(listOf("NEW_DEVICE"), ChangeDetector.events(old = null, now = now))
  }

  @Test
  fun detectsDeviceOnline() {
    val old = Device(id = "d1", ip = "192.168.1.10", online = false)
    val now = old.copy(online = true)
    assertEquals(listOf("DEVICE_ONLINE"), ChangeDetector.events(old, now))
  }

  @Test
  fun detectsDeviceOffline() {
    val old = Device(id = "d1", ip = "192.168.1.10", online = true)
    val now = old.copy(online = false)
    assertEquals(listOf("DEVICE_OFFLINE"), ChangeDetector.events(old, now))
  }

  @Test
  fun detectsIpChanged() {
    val old = Device(id = "d1", ip = "192.168.1.10", online = true)
    val now = old.copy(ip = "192.168.1.11")
    assertEquals(listOf("IP_CHANGED"), ChangeDetector.events(old, now))
  }
}
