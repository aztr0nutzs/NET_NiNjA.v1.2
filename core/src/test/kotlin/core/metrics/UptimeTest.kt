package core.metrics

import core.model.DeviceEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class UptimeTest {
  @Test
  fun calculatesExpectedUptimeFromOnlineOfflineSequence() {
    val nowMs = 10_000L
    val windowMs = 10_000L
    val events = listOf(
      DeviceEvent("d1", 2_000L, "DEVICE_ONLINE"),
      DeviceEvent("d1", 7_000L, "DEVICE_OFFLINE")
    )

    val pct = Uptime.pct(events, windowMs = windowMs, nowMs = nowMs)
    assertEquals(50.0, pct, absoluteTolerance = 0.0001)
  }

  @Test
  fun calculatesExpectedUptimeWhenStillOnlineAtWindowEnd() {
    val nowMs = 10_000L
    val windowMs = 10_000L
    val events = listOf(
      DeviceEvent("d1", 1_000L, "DEVICE_ONLINE")
    )

    val pct = Uptime.pct(events, windowMs = windowMs, nowMs = nowMs)
    assertEquals(90.0, pct, absoluteTolerance = 0.0001)
  }
}
