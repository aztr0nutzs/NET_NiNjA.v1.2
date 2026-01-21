
package core.discovery

import java.net.Socket

object BannerGrabber {
  fun grab(ip: String, port: Int, timeoutMs: Int = 300): String? {
    return try {
      Socket(ip, port).use { s ->
        s.soTimeout = timeoutMs
        val buf = ByteArray(256)
        val n = s.getInputStream().read(buf)
        if (n > 0) String(buf, 0, n) else null
      }
    } catch (_: Exception) { null }
  }
}
