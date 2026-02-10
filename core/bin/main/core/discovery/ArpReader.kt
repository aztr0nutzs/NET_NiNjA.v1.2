
package core.discovery

import java.io.File

object ArpReader {
  /** Linux-only: /proc/net/arp */
  fun read(): Map<String, String> {
    val f = File("/proc/net/arp")
    if (!f.exists()) return emptyMap()
    val out = mutableMapOf<String, String>()
    f.readLines().drop(1).forEach { line ->
      val parts = line.trim().split(Regex("\\s+"))
      if (parts.size >= 4) {
        val ip = parts[0]
        val mac = parts[3]
        if (mac != "00:00:00:00:00:00") out[ip] = mac
      }
    }
    return out
  }
}
