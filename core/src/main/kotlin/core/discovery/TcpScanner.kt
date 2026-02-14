
package core.discovery

import java.net.InetSocketAddress
import java.net.Socket

object TcpScanner {
  // Common ports checked during network scans
  val safePorts = listOf(22, 80, 443, 445, 554, 3389, 5555, 8080, 8443, 161, 53)

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

  /**
   * Quick TCP-based reachability check. Uses port 80/443/445 probes as a
   * fallback when ICMP (InetAddress.isReachable) is blocked — common on
   * Windows where raw sockets require admin privileges.
   */
  fun isReachableTcp(ip: String, timeoutMs: Int): Boolean {
    val probePorts = intArrayOf(80, 443, 445)
    for (p in probePorts) {
      try {
        Socket().use { s ->
          s.connect(InetSocketAddress(ip, p), timeoutMs)
          return true
        }
      } catch (_: Exception) {}
    }
    return false
  }
}
