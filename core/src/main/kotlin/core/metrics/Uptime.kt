
package core.metrics

import core.model.DeviceEvent

object Uptime {
  /** Compute uptime percent over [windowMs] based on ONLINE/OFFLINE transitions. */
  fun pct(events: List<DeviceEvent>, windowMs: Long, nowMs: Long = System.currentTimeMillis()): Double {
    if (windowMs <= 0) return 0.0
    if (events.isEmpty()) return 0.0

    val windowStart = nowMs - windowMs
    val clipped = events.filter { it.ts >= windowStart }.toMutableList()
    if (clipped.isEmpty()) return 0.0

    // Determine initial state by looking at last event before window start, if any
    var online = false
    val firstTs = clipped.first().ts
    var lastTs = firstTs
    var up = 0L

    for (e in clipped) {
      if (online) up += (e.ts - lastTs)
      online = e.event == "DEVICE_ONLINE"
      lastTs = e.ts
    }
    // extend to now
    if (online) up += (nowMs - lastTs)

    return (up.toDouble() / windowMs.toDouble()) * 100.0
  }
}
