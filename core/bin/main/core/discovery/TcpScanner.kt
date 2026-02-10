
package core.discovery

import java.net.InetSocketAddress
import java.net.Socket

object TcpScanner {
  // Safe default ports only
  val safePorts = listOf(22, 80, 443, 445, 3389, 5555, 161)

  fun scan(ip: String, timeoutMs: Int = 200): List<Int> {
    val open = mutableListOf<Int>()
    for (p in safePorts) {
      try {
        Socket().use { s ->
          s.connect(InetSocketAddress(ip, p), timeoutMs)
          open += p
        }
      } catch (_: Exception) {}
    }
    return open
  }
}
