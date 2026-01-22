
package com.netninja

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.text.format.Formatter
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

@Serializable data class Device(
  val id: String,
  val ip: String,
  val online: Boolean,
  val lastSeen: Long,
  val mac: String? = null,
  val hostname: String? = null,
  val vendor: String? = null,
  val os: String? = null
)

@Serializable data class ScanRequest(val subnet: String? = null, val timeoutMs: Int? = 300)
@Serializable data class LoginRequest(val username: String? = null, val password: String? = null)
@Serializable data class ActionRequest(val ip: String? = null, val mac: String? = null, val url: String? = null)
@Serializable data class ScheduleRequest(val subnet: String? = null, val freq: String? = null)
@Serializable data class RuleRequest(val match: String? = null, val action: String? = null)
@Serializable data class RuleEntry(val match: String, val action: String)

class AndroidLocalServer(private val ctx: Context) {
  private var engine: ApplicationEngine? = null
  private val devices = ConcurrentHashMap<String, Device>()
  private val logs = ConcurrentLinkedQueue<String>()
  private val lastScanAt = AtomicReference<Long?>(null)
  private val schedules = java.util.concurrent.CopyOnWriteArrayList<String>()
  private val rules = java.util.concurrent.CopyOnWriteArrayList<RuleEntry>()

  fun start(host: String = "127.0.0.1", port: Int = 8787) {
    val uiDir = File(ctx.filesDir, "web-ui").apply { mkdirs() }
    // Copy web-ui assets from APK assets to internal storage once
    AssetCopier.copyDir(ctx, "web-ui", uiDir)

    engine = embeddedServer(CIO, host = host, port = port) {
      install(ContentNegotiation) { json() }
      install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post); allowHeader(HttpHeaders.ContentType)
      }
      routing {
        staticFiles("/ui", uiDir, index = "ninja_mobile.html")
        get("/") { call.respondRedirect("/ui/ninja_mobile.html") }

        get("/api/v1/system/info") {
          call.respond(mapOf("platform" to "android", "time" to System.currentTimeMillis()))
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
          val subnet = req.subnet?.trim().takeUnless { it.isNullOrBlank() } ?: deriveSubnetCidr()
          log("scan subnet=$subnet")

          val found = mutableListOf<Device>()
          val arp = readArpTable()
          val ips = resolveScanIps(subnet)
          val timeout = req.timeoutMs ?: 300

          val sem = Semaphore(48)
          coroutineScope {
            ips.map { ip ->
              async(Dispatchers.IO) {
                sem.withPermit {
                  val arpDev = arp.firstOrNull { it.ip == ip }
                  val mac = arpDev?.mac
                  val reachable = try { InetAddress.getByName(ip).isReachable(timeout) } catch (_: Exception) { false }
                  if (!reachable && mac == null) return@withPermit
                  val ports = if (reachable) scanPorts(ip, timeout) else emptyList()
                  val hostname = if (reachable) resolveHostname(ip) else null
                  val vendor = lookupVendor(mac)
                  val os = guessOs(ports, hostname, vendor)
                  val dev = Device(
                    id = mac ?: ip,
                    ip = ip,
                    online = reachable,
                    lastSeen = System.currentTimeMillis(),
                    mac = mac,
                    hostname = hostname,
                    vendor = vendor,
                    os = os
                  )
                  devices[dev.id] = dev
                  if (reachable) {
                    synchronized(found) { found += dev }
                  }
                }
              }
            }.awaitAll()
          }

          lastScanAt.set(System.currentTimeMillis())
          log("scan complete: ${found.size} devices")
          call.respond(found)
        }

        get("/api/v1/discovery/results") { call.respond(devices.values.toList()) }

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
          val ok = try { InetAddress.getByName(ip).isReachable(350) } catch (_: Exception) { false }
          val rtt = if (ok) (System.currentTimeMillis() - start) else null
          call.respond(mapOf("ok" to ok, "ip" to ip, "rttMs" to rtt))
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
          val ok = runCatching { scanPorts(ip, 350).contains(161) }.getOrDefault(false)
          call.respond(mapOf("ok" to ok, "ip" to ip))
        }

        post("/api/v1/actions/ssh") {
          val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
          val ip = req.ip?.trim()
          if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
          val ok = runCatching { scanPorts(ip, 350).contains(22) }.getOrDefault(false)
          call.respond(mapOf("ok" to ok, "ip" to ip, "note" to "SSH port probe only"))
        }

        get("/api/v1/metrics") {
          val memTotal = Runtime.getRuntime().totalMemory()
          val memFree = Runtime.getRuntime().freeMemory()
          val memUsed = memTotal - memFree
          val cpuLoad = CpuSampler.sample()
          val all = devices.values.toList()
          val online = all.count { it.online }
          call.respond(mapOf(
            "uptimeMs" to SystemClock.elapsedRealtime(),
            "cpuLoad" to cpuLoad,
            "memTotal" to memTotal,
            "memUsed" to memUsed,
            "devicesTotal" to all.size,
            "devicesOnline" to online,
            "lastScanAt" to lastScanAt.get()
          ))
        }

        get("/api/v1/logs/stream") {
          call.response.cacheControl(CacheControl.NoCache(null))
          call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            while (true) {
              var emitted = 0
              while (true) {
                val line = logs.poll() ?: break
                write("data: $line\n\n")
                emitted++
              }
              flush()
              delay(if (emitted == 0) 350 else 50)
            }
          }
        }
      }
    }.start(wait = false)

    log("engine started on $host:$port")
  }

  fun stop() { engine?.stop(500, 1000) }

  private fun log(s: String) { logs.add("${System.currentTimeMillis()}: $s") }

  private fun deriveSubnetCidr(): String {
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val dhcp = wm.dhcpInfo
    val ipInt = dhcp.ipAddress
    val maskInt = dhcp.netmask
    val ip = Formatter.formatIpAddress(ipInt)
    val mask = Formatter.formatIpAddress(maskInt)
    val prefix = maskToPrefix(mask)
    return "${ip.substringBeforeLast(".")}.0/$prefix"
  }

  private fun localNetworkInfo(): Map<String, Any?> {
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val dhcp = wm.dhcpInfo
    val ip = Formatter.formatIpAddress(dhcp.ipAddress)
    val gw = Formatter.formatIpAddress(dhcp.gateway)
    val mask = Formatter.formatIpAddress(dhcp.netmask)
    val cidr = "${ip.substringBeforeLast(".")}.0/${maskToPrefix(mask)}"
    return mapOf("name" to "Wi-Fi", "ip" to ip, "cidr" to cidr, "gateway" to gw)
  }

  private fun readArpTable(): List<Device> {
    val arpFile = File("/proc/net/arp")
    if (!arpFile.exists()) return emptyList()
    val lines = try { arpFile.readLines() } catch (_: Exception) { return emptyList() }
    if (lines.size <= 1) return emptyList()

    val out = mutableListOf<Device>()
    for (line in lines.drop(1)) {
      val parts = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
      if (parts.size < 4) continue
      val ip = parts[0]
      val flags = parts[2]
      val mac = parts[3]
      if (mac == "00:00:00:00:00:00") continue
      val online = flags != "0x0"
      out += Device(ip, ip, online, System.currentTimeMillis(), mac)
    }
    return out
  }

  private fun resolveScanIps(cidr: String): List<String> {
    return cidrToIps(cidr)
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

  private fun maskToPrefix(mask: String): Int {
    val parts = mask.split(".").map { it.toIntOrNull() ?: 0 }
    var count = 0
    for (p in parts) {
      count += Integer.bitCount(p)
    }
    return count.coerceIn(0, 32)
  }

  private fun scanPorts(ip: String, timeoutMs: Int): List<Int> {
    val ports = listOf(22, 80, 443, 445, 3389, 5555, 161)
    val open = mutableListOf<Int>()
    for (p in ports) {
      try {
        java.net.Socket().use { s ->
          s.connect(java.net.InetSocketAddress(ip, p), timeoutMs)
          open += p
        }
      } catch (_: Exception) {}
    }
    return open
  }

  private fun resolveHostname(ip: String): String? {
    return runCatching {
      val host = InetAddress.getByName(ip).canonicalHostName
      if (host == ip) null else host
    }.getOrNull()
  }

  private fun lookupVendor(mac: String?): String? {
    if (mac.isNullOrBlank() || mac.length < 8) return null
    val key = mac.uppercase().substring(0, 8)
    val vendors = mapOf(
      "B8:27:EB" to "Raspberry Pi",
      "00:1A:2B" to "Cisco",
      "FC:FB:FB" to "Google"
    )
    return vendors[key]
  }

  private fun guessOs(openPorts: List<Int>, hostname: String?, vendor: String?): String? {
    val host = hostname?.lowercase().orEmpty()
    val vend = vendor?.lowercase().orEmpty()
    return when {
      openPorts.contains(445) || openPorts.contains(3389) -> "Windows"
      openPorts.contains(5555) || host.contains("android") -> "Android"
      openPorts.contains(22) -> "Linux"
      vend.contains("apple") || host.contains("mac") -> "macOS"
      else -> null
    }
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
}

private object CpuSampler {
  private var lastIdle = 0L
  private var lastTotal = 0L

  fun sample(): Double? {
    return runCatching {
      val line = java.io.File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return null
      val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
      if (parts.size < 4) return null
      val idle = parts[3]
      val total = parts.sum()
      val deltaIdle = idle - lastIdle
      val deltaTotal = total - lastTotal
      lastIdle = idle
      lastTotal = total
      if (deltaTotal <= 0) return null
      1.0 - (deltaIdle.toDouble() / deltaTotal.toDouble())
    }.getOrNull()
  }
}
