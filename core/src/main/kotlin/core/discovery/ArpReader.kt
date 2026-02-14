
package core.discovery

import java.io.File

object ArpReader {
  /**
   * Read the system ARP table and return a map of IP → MAC address.
   * Tries Linux /proc/net/arp first, then falls back to `arp -a`
   * (available on Windows and macOS).
   */
  fun read(): Map<String, String> {
    val f = File("/proc/net/arp")
    if (f.exists()) return parseLinuxArp(f)

    // Fallback: shell out to 'arp -a' (Windows / macOS / other)
    return try {
      val process = ProcessBuilder("arp", "-a").start()
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()
      parseArpCommand(output)
    } catch (_: Exception) {
      emptyMap()
    }
  }

  private fun parseLinuxArp(f: File): Map<String, String> {
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

  /**
   * Parse output of `arp -a` which varies by OS.
   * Windows format (one or more interface sections):
   *   Interface: 192.168.1.100 --- 0x7
   *     Internet Address      Physical Address      Type
   *     192.168.1.1           aa-bb-cc-dd-ee-ff     dynamic
   *     192.168.1.2           11-22-33-44-55-66     dynamic
   * macOS:    "? (192.168.1.1) at aa:bb:cc:dd:ee:ff on en0 ..."
   * Extracts IP + MAC pairs via regex.
   */
  private fun parseArpCommand(output: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    // Pattern 1: macOS / BSD style — "? (192.168.1.1) at aa:bb:cc:dd:ee:ff ..."
    val macosPattern = Regex("""\((\d+\.\d+\.\d+\.\d+)\)\s+at\s+([0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2})""")
    // Pattern 2: Windows style — "  192.168.1.1           aa-bb-cc-dd-ee-ff     dynamic"
    val winPattern = Regex("""^\s*(\d+\.\d+\.\d+\.\d+)\s+([0-9a-fA-F]{2}[:\-][0-9a-fA-F]{2}[:\-][0-9a-fA-F]{2}[:\-][0-9a-fA-F]{2}[:\-][0-9a-fA-F]{2}[:\-][0-9a-fA-F]{2})\s+\w+""")
    for (line in output.lines()) {
      // Try macOS pattern first
      val macosMatch = macosPattern.find(line)
      if (macosMatch != null) {
        val ip = macosMatch.groupValues[1]
        val mac = macosMatch.groupValues[2].uppercase().replace("-", ":")
        if (mac != "00:00:00:00:00:00" && mac != "FF:FF:FF:FF:FF:FF") {
          out[ip] = mac
        }
        continue
      }
      // Then try Windows pattern
      val winMatch = winPattern.find(line)
      if (winMatch != null) {
        val ip = winMatch.groupValues[1]
        val mac = winMatch.groupValues[2].uppercase().replace("-", ":")
        if (mac != "00:00:00:00:00:00" && mac != "FF:FF:FF:FF:FF:FF") {
          out[ip] = mac
        }
      }
    }
    return out
  }
}
