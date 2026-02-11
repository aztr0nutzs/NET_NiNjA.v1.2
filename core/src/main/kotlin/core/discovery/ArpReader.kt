
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
   * Windows:  "  192.168.1.1          aa-bb-cc-dd-ee-ff     dynamic"
   * macOS:    "? (192.168.1.1) at aa:bb:cc:dd:ee:ff on en0 ..."
   * Extracts IP + MAC pairs via regex.
   */
  private fun parseArpCommand(output: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val pattern = Regex("""(\d+\.\d+\.\d+\.\d+)\)??\s+(?:at\s+)?([0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2}[:\-][0-9a-fA-F]{1,2})""")
    for (line in output.lines()) {
      val match = pattern.find(line) ?: continue
      val ip = match.groupValues[1]
      val mac = match.groupValues[2].uppercase().replace("-", ":")
      if (mac != "00:00:00:00:00:00" && mac != "FF:FF:FF:FF:FF:FF") {
        out[ip] = mac
      }
    }
    return out
  }
}
