
package core.alerts

import core.model.Device

object ChangeDetector {
  fun events(old: Device?, now: Device): List<String> {
    if (old == null) return listOf("NEW_DEVICE")
    val out = mutableListOf<String>()
    if (old.online != now.online) out += if (now.online) "DEVICE_ONLINE" else "DEVICE_OFFLINE"
    if (old.ip != now.ip) out += "IP_CHANGED"
    return out
  }
}
