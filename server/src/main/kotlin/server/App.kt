
package server

import core.alerts.ChangeDetector
import core.discovery.*
import core.metrics.Uptime
import core.model.Device
import core.model.DeviceEvent
import core.persistence.Db
import core.persistence.DeviceDao
import core.persistence.EventDao
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.coroutines.*
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ScanRequest(val subnet: String? = null, val timeoutMs: Int? = 250)
data class LoginRequest(val username: String? = null, val password: String? = null)
data class ActionRequest(val ip: String? = null, val mac: String? = null, val url: String? = null, val command: String? = null)
data class ScheduleRequest(val subnet: String? = null, val freq: String? = null)
data class RuleRequest(val match: String? = null, val action: String? = null)
data class RuleEntry(val match: String, val action: String)

fun main() = startServer(File("web-ui"))

fun startServer(webUiDir: File, host: String = "127.0.0.1", port: Int = 8787) {
  val conn = Db.open("netninja.db")
  val devices = DeviceDao(conn)
  val events = EventDao(conn)
  val lastScanAt = AtomicReference<Long?>(null)
  val deviceCache = ConcurrentHashMap<String, Device>()
  val schedules = java.util.concurrent.CopyOnWriteArrayList<String>()
  val rules = java.util.concurrent.CopyOnWriteArrayList<RuleEntry>()

  val logQueue = ConcurrentLinkedQueue<String>()
  fun log(msg: String) { logQueue.add("${System.currentTimeMillis()}: $msg") }

  embeddedServer(Netty, host = host, port = port) {
    install(ContentNegotiation) { json() }
    install(CORS) {
      anyHost()
      allowHeader(HttpHeaders.ContentType)
      allowMethod(HttpMethod.Get)
      allowMethod(HttpMethod.Post)
      allowMethod(HttpMethod.Put)
    }

    routing {
      // Serve web-ui
      staticFiles("/ui", webUiDir, index = "ninja_mobile_new.html")
      get("/") { call.respondRedirect("/ui/ninja_mobile_new.html") }

      get("/api/v1/system/info") {
        call.respond(mapOf(
          "os" to System.getProperty("os.name"),
          "arch" to System.getProperty("os.arch"),
          "time" to System.currentTimeMillis()
        ))
      }

      get("/api/v1/network/info") {
        call.respond(localNetworkInfo())
      }

      post("/api/v1/auth/login") {
        val req = runCatching { call.receive<LoginRequest>() }.getOrNull() ?: LoginRequest()
        val user = req.username?.trim().orEmpty()
        val pass = req.password?.trim().orEmpty()
        val ok = user == "ninja" && pass == "neon"
        if (!ok) {
          call.respond(HttpStatusCode.Unauthorized, mapOf("ok" to false))
          return@post
        }
        call.respond(mapOf("ok" to true, "token" to "local-${System.currentTimeMillis()}"))
      }

      post("/api/v1/discovery/scan") {
        val req = runCatching { call.receive<ScanRequest>() }.getOrNull() ?: ScanRequest()
        val subnet = req.subnet?.trim().orEmpty()
        log("scan requested subnet=$subnet")

        val arp = ArpReader.read()
        val ips = resolveScanIps(subnet)
        val found = mutableListOf<Device>()
        val sem = Semaphore(64)
        val timeout = req.timeoutMs ?: 300

        coroutineScope {
          ips.map { ip ->
            async(Dispatchers.IO) {
              sem.withPermit {
                val mac = arp[ip]
                val reachable = try { InetAddress.getByName(ip).isReachable(timeout) } catch (_: Exception) { false }
                val openPorts = if (reachable) TcpScanner.scan(ip, timeout) else emptyList()
                val banners = if (openPorts.isNotEmpty()) openPorts.mapNotNull { p ->
                  BannerGrabber.grab(ip, p)?.let { p to it.trim() }
                }.toMap() else emptyMap()
                val hostname = if (reachable) resolveHostname(ip) else null
                val vendor = OuiDb.lookup(mac)
                val os = guessOs(openPorts, banners, hostname, vendor)
                val now = System.currentTimeMillis()
                val id = mac ?: ip
                val dev = Device(
                  id = id,
                  ip = ip,
                  mac = mac,
                  hostname = hostname,
                  os = os,
                  vendor = vendor,
                  online = reachable,
                  lastSeen = now,
                  openPorts = openPorts,
                  banners = banners
                )

                val old = devices.get(id)
                devices.upsert(dev)
                deviceCache[id] = dev
                val evs = ChangeDetector.events(old, dev)
                evs.forEach { e ->
                  events.insert(DeviceEvent(id, now, e))
                  log("event $id $e")
                }

                if (reachable) {
                  synchronized(found) { found += dev }
                }
              }
            }
          }.awaitAll()
        }

        lastScanAt.set(System.currentTimeMillis())
        call.respond(found)
      }

      get("/api/v1/discovery/results") {
        val out = if (deviceCache.isNotEmpty()) deviceCache.values.toList() else devices.all()
        call.respond(out)
      }

      get("/api/v1/devices/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val d = deviceCache[id] ?: devices.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(d)
      }

      get("/api/v1/devices/{id}/history") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(events.history(id))
      }

      get("/api/v1/devices/{id}/uptime") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val hist = events.history(id)
        call.respond(mapOf("uptimePct24h" to Uptime.pct(hist, 86_400_000)))
      }

      get("/api/v1/export/devices") {
        val out = if (deviceCache.isNotEmpty()) deviceCache.values.toList() else devices.all()
        call.respond(out)
      }

      get("/api/v1/schedules") {
        call.respond(schedules.toList())
      }

      post("/api/v1/schedules") {
        val req = runCatching { call.receive<ScheduleRequest>() }.getOrNull() ?: ScheduleRequest()
        val subnet = req.subnet?.trim().orEmpty()
        val freq = req.freq?.trim().orEmpty()
        if (subnet.isBlank() || freq.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        schedules += "SCAN $subnet @ $freq"
        call.respond(mapOf("ok" to true))
      }

      post("/api/v1/schedules/pause") {
        call.respond(mapOf("ok" to true))
      }

      get("/api/v1/rules") {
        call.respond(rules.toList())
      }

      post("/api/v1/rules") {
        val req = runCatching { call.receive<RuleRequest>() }.getOrNull() ?: RuleRequest()
        val match = req.match?.trim().orEmpty()
        val action = req.action?.trim().orEmpty()
        if (match.isBlank() || action.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        rules += RuleEntry(match, action)
        call.respond(mapOf("ok" to true))
      }

      post("/api/v1/actions/ping") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val start = System.currentTimeMillis()
        val reachable = try { InetAddress.getByName(ip).isReachable(350) } catch (_: Exception) { false }
        val rtt = if (reachable) (System.currentTimeMillis() - start) else null
        call.respond(mapOf("ok" to reachable, "ip" to ip, "rttMs" to rtt))
      }

      post("/api/v1/actions/http") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val url = req.url?.trim()
        if (url.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val status = runCatching {
          java.net.URL(url).openConnection().apply { connectTimeout = 800; readTimeout = 800 }.let { conn ->
            (conn as? java.net.HttpURLConnection)?.responseCode ?: 0
          }
        }.getOrDefault(0)
        call.respond(mapOf("ok" to (status in 200..399), "status" to status))
      }

      post("/api/v1/actions/wol") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val mac = req.mac?.trim()
        if (mac.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val ok = runCatching { sendMagicPacket(mac) }.isSuccess
        call.respond(mapOf("ok" to ok, "mac" to mac))
      }

      post("/api/v1/actions/snmp") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val reachable = runCatching { TcpScanner.scan(ip, 300).contains(161) }.getOrDefault(false)
        call.respond(mapOf("ok" to reachable, "ip" to ip))
      }

      post("/api/v1/actions/ssh") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val reachable = runCatching { TcpScanner.scan(ip, 400).contains(22) }.getOrDefault(false)
        call.respond(mapOf("ok" to reachable, "ip" to ip, "note" to "SSH port probe only"))
      }

      get("/api/v1/metrics") {
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        val load = (osBean as? com.sun.management.OperatingSystemMXBean)?.systemCpuLoad ?: -1.0
        val memTotal = Runtime.getRuntime().totalMemory()
        val memFree = Runtime.getRuntime().freeMemory()
        val memUsed = memTotal - memFree
        val all = devices.all()
        val online = all.count { it.online }
        call.respond(mapOf(
          "uptimeMs" to ManagementFactory.getRuntimeMXBean().uptime,
          "cpuLoad" to if (load >= 0) load else null,
          "memTotal" to memTotal,
          "memUsed" to memUsed,
          "devicesTotal" to all.size,
          "devicesOnline" to online,
          "lastScanAt" to lastScanAt.get()
        ))
      }

      // SSE stream for logs
      get("/api/v1/logs/stream") {
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
          while (true) {
            var emitted = 0
            while (true) {
              val line = logQueue.poll() ?: break
              write("data: ${line.replace("\n"," ")}\n\n")
              emitted++
            }
            flush()
            delay(if (emitted == 0) 350 else 50)
          }
        }
      }
    }
  }.start(wait = true)
}

private fun resolveHostname(ip: String): String? {
  return runCatching {
    val host = InetAddress.getByName(ip).canonicalHostName
    if (host == ip) null else host
  }.getOrNull()
}

private fun guessOs(openPorts: List<Int>, banners: Map<Int, String>, hostname: String?, vendor: String?): String? {
  val bannerText = banners.values.joinToString(" ").lowercase()
  val host = hostname?.lowercase().orEmpty()
  val vend = vendor?.lowercase().orEmpty()
  return when {
    openPorts.contains(445) || openPorts.contains(3389) || bannerText.contains("microsoft") -> "Windows"
    openPorts.contains(5555) || host.contains("android") -> "Android"
    openPorts.contains(22) && (bannerText.contains("ubuntu") || bannerText.contains("debian") || bannerText.contains("openssh")) -> "Linux"
    vend.contains("apple") || host.contains("mac") -> "macOS"
    openPorts.contains(22) -> "Unix"
    else -> null
  }
}

private fun resolveScanIps(requested: String): List<String> {
  if (requested.isNotBlank()) return cidrToIps(requested)
  val localIps = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
    .flatMap { java.util.Collections.list(it.inetAddresses) }
    .mapNotNull { addr ->
      val ip = addr.hostAddress
      if (ip.contains(":")) null else ip
    }
  val primary = localIps.firstOrNull() ?: return emptyList()
  return cidrToIps("${primary.substringBeforeLast(".")}.0/24")
}

private fun cidrToIps(cidr: String): List<String> {
  val parts = cidr.split("/")
  if (parts.size != 2) return emptyList()
  val baseIp = parts[0]
  val prefix = parts[1].toIntOrNull() ?: return emptyList()
  val base = ipToInt(baseIp)
  val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
  val network = base and mask
  val hostCount = (1L shl (32 - prefix)).coerceAtMost(1L shl 16).toInt()
  val out = mutableListOf<String>()
  for (i in 1 until hostCount - 1) {
    out += intToIp(network + i.toInt())
  }
  return out
}

private fun ipToInt(ip: String): Int {
  val parts = ip.split(".").map { it.toIntOrNull() ?: 0 }
  if (parts.size != 4) return 0
  return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
}

private fun intToIp(value: Int): String {
  return listOf(
    (value ushr 24) and 0xff,
    (value ushr 16) and 0xff,
    (value ushr 8) and 0xff,
    value and 0xff
  ).joinToString(".")
}

private fun sendMagicPacket(mac: String) {
  val macBytes = mac.split(":", "-", ".").mapNotNull { it.takeIf { s -> s.length == 2 }?.toInt(16)?.toByte() }
  if (macBytes.size != 6) return
  val packet = ByteArray(6 + 16 * 6)
  java.util.Arrays.fill(packet, 0, 6, 0xFF.toByte())
  for (i in 6 until packet.size) {
    packet[i] = macBytes[(i - 6) % 6]
  }
  val address = java.net.InetAddress.getByName("255.255.255.255")
  val dp = java.net.DatagramPacket(packet, packet.size, address, 9)
  java.net.DatagramSocket().use { it.broadcast = true; it.send(dp) }
}

private fun localNetworkInfo(): Map<String, Any?> {
  val iface = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
    .firstOrNull { it.isUp && !it.isLoopback && java.util.Collections.list(it.inetAddresses).any { a -> !a.hostAddress.contains(":") } }
  val addr = iface?.interfaceAddresses?.firstOrNull { it.address.hostAddress?.contains(":") == false }
  val ip = addr?.address?.hostAddress
  val prefix = addr?.networkPrefixLength?.toInt()
  val cidr = if (ip != null && prefix != null) "${ip.substringBeforeLast(".")}.0/$prefix" else null
  return mapOf(
    "name" to (iface?.displayName ?: "Network"),
    "ip" to ip,
    "cidr" to cidr,
    "gateway" to null
  )
}
