
package core.discovery

object OuiDb {
  private val fallback = mapOf(
    "B8:27:EB" to "Raspberry Pi",
    "00:1A:2B" to "Cisco",
    "FC:FB:FB" to "Google"
  )

  private val vendors: Map<String, String> by lazy { loadOuiPrefixes() }

  fun lookup(mac: String?): String? {
    if (mac.isNullOrBlank() || mac.length < 8) return null
    val key = mac.uppercase().substring(0, 8)
    return vendors[key] ?: fallback[key]
  }

  private fun loadOuiPrefixes(): Map<String, String> {
    // Check classpath first so a bundled OUI file can work cross-platform.
    OuiDb::class.java.getResourceAsStream("/oui-prefixes.txt")?.use { resource ->
      val parsed = parseOuiStream(resource)
      if (parsed.isNotEmpty()) return parsed
    }

    val candidates = listOf(
      // Linux
      "/usr/share/nmap/nmap-mac-prefixes",
      "/usr/share/ieee-data/oui.txt",
      "/usr/share/wireshark/manuf",
      // macOS (Homebrew)
      "/usr/local/share/nmap/nmap-mac-prefixes",
      "/usr/local/share/wireshark/manuf",
      "/opt/homebrew/share/wireshark/manuf",
      // Windows
      "C:\\Program Files\\Wireshark\\manuf",
      "C:\\Program Files (x86)\\Nmap\\nmap-mac-prefixes"
    )
    val file = candidates.map { java.io.File(it) }.firstOrNull { it.exists() } ?: return emptyMap()
    return parseOuiFile(file)
  }

  private fun parseOuiFile(file: java.io.File): Map<String, String> {
    val out = mutableMapOf<String, String>()
    file.forEachLine { line ->
      parseOuiLine(line, out)
    }
    return out
  }

  private fun parseOuiStream(stream: java.io.InputStream): Map<String, String> {
    val out = mutableMapOf<String, String>()
    stream.bufferedReader().useLines { lines ->
      lines.forEach { line ->
        parseOuiLine(line, out)
      }
    }
    return out
  }

  private fun parseOuiLine(line: String, out: MutableMap<String, String>) {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return
    if (trimmed.contains("(base 16)")) {
      val parts = trimmed.split("(base 16)")
      if (parts.size >= 2) {
        val prefix = parts[0].trim().replace("-", ":")
        if (prefix.count { it == ':' } == 2) {
          out[prefix.uppercase()] = parts[1].trim()
        }
      }
    } else if (trimmed.contains("\t")) {
      val parts = trimmed.split("\t")
      val prefix = parts.firstOrNull()?.trim() ?: return
      if (prefix.count { it == ':' } == 2) {
        out[prefix.uppercase()] = parts.drop(1).joinToString(" ").trim()
      }
    }
  }
}
