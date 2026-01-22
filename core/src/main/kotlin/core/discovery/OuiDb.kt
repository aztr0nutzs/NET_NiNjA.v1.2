
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
    val candidates = listOf(
      "/usr/share/nmap/nmap-mac-prefixes",
      "/usr/share/ieee-data/oui.txt",
      "/usr/share/wireshark/manuf"
    )
    val file = candidates.map { java.io.File(it) }.firstOrNull { it.exists() } ?: return emptyMap()
    val out = mutableMapOf<String, String>()
    file.readLines().forEach { line ->
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
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
        val prefix = parts.firstOrNull()?.trim() ?: return@forEach
        if (prefix.count { it == ':' } == 2) {
          out[prefix.uppercase()] = parts.drop(1).joinToString(" ").trim()
        }
      }
    }
    return out
  }
}
