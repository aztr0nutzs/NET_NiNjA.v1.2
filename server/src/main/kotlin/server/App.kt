
package server

import com.netninja.cam.OnvifDiscoveryService
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
import kotlinx.serialization.Serializable
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import server.openclaw.OpenClawGatewayRegistry
import server.openclaw.openClawRoutes

@Serializable
data class ScanRequest(val subnet: String? = null, val timeoutMs: Int? = 250)

@Serializable
data class ActionRequest(val ip: String? = null, val mac: String? = null, val url: String? = null, val command: String? = null)

@Serializable
data class ScheduleRequest(val subnet: String? = null, val freq: String? = null)

@Serializable
data class RuleRequest(val match: String? = null, val action: String? = null)

@Serializable
data class RuleEntry(val match: String, val action: String)


@Serializable
data class DeviceMetaUpdate(
  val name: String? = null,
  val owner: String? = null,
  val room: String? = null,
  val note: String? = null,
  val trust: String? = null,
  val type: String? = null,
  val status: String? = null,
  val via: String? = null,
  val signal: String? = null,
  val activityToday: String? = null,
  val traffic: String? = null
)

@Serializable
data class PortScanRequest(val ip: String? = null, val timeoutMs: Int? = null)

@Serializable
data class ScanProgress(
  val progress: Int = 0,
  val phase: String = "IDLE",
  val networks: Int = 0,
  val devices: Int = 0,
  val rssiDbm: Double? = null,
  val ssid: String? = null,
  val bssid: String? = null,
  val subnet: String? = null,
  val gateway: String? = null,
  val linkUp: Boolean = true,
  val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ScheduleEntry(val subnet: String, val freqMs: Long, val nextRunAt: Long)

@Serializable
data class SystemInfo(val os: String? = null, val arch: String? = null, val timeMs: Long = System.currentTimeMillis())


fun main() {
  val config = resolveServerConfig()
  startServer(
    File("web-ui"),
    host = config.host,
    port = config.port,
    dbPath = config.dbPath,
    allowedOrigins = config.allowedOrigins
  )
}

fun startServer(
  webUiDir: File,
  host: String = "127.0.0.1",
  port: Int = 8787,
  dbPath: String = "netninja.db",
  allowedOrigins: List<String> = listOf("http://127.0.0.1:8787", "http://localhost:8787")
) {
  val conn = Db.open(dbPath)
  val devices = DeviceDao(conn)
  val events = EventDao(conn)
  val lastScanAt = AtomicReference<Long?>(null)
  val deviceCache = ConcurrentHashMap<String, Device>()
  val schedules = java.util.concurrent.CopyOnWriteArrayList<String>()
  val rules = java.util.concurrent.CopyOnWriteArrayList<RuleEntry>()
  val scheduleEntries = java.util.concurrent.CopyOnWriteArrayList<ScheduleEntry>()
  val scanProgress = AtomicReference(ScanProgress())
  val scanCancel = AtomicBoolean(false)
  val scanJob = AtomicReference<Job?>(null)
  val openClawGateway = OpenClawGatewayRegistry()

  val logQueue = ConcurrentLinkedQueue<String>()
  fun log(msg: String) { logQueue.add("${System.currentTimeMillis()}: $msg") }

  val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  fun scheduleFrequencyMs(freq: String): Long? = when (freq.lowercase()) {
    "hourly" -> 60 * 60 * 1000L
    "daily" -> 24 * 60 * 60 * 1000L
    "weekly" -> 7 * 24 * 60 * 60 * 1000L
    "monthly" -> 30 * 24 * 60 * 60 * 1000L
    else -> null
  }

  suspend fun performScan(subnet: String, timeoutMs: Int): List<Device> {
    scanCancel.set(false)
    val arp = ArpReader.read()
    val ips = resolveScanIps(subnet)
    val found = mutableListOf<Device>()
    val sem = Semaphore(64)
    val total = ips.size.coerceAtLeast(1)
    val completed = java.util.concurrent.atomic.AtomicInteger(0)
    val foundCount = java.util.concurrent.atomic.AtomicInteger(0)

    val netInfo = localNetworkInfo()
    scanProgress.set(
      ScanProgress(
        progress = 0,
        phase = "DISCOVERY",
        networks = 1,
        devices = 0,
        rssiDbm = null,
        ssid = netInfo["name"]?.toString(),
        bssid = null,
        subnet = subnet.ifBlank { netInfo["cidr"]?.toString() },
        gateway = netInfo["gateway"]?.toString(),
        linkUp = true,
        updatedAt = System.currentTimeMillis()
      )
    )

    if (ips.isEmpty()) {
      scanProgress.set(
        ScanProgress(
          progress = 0,
          phase = "NO_NETWORK",
          networks = 0,
          devices = 0,
          rssiDbm = null,
          ssid = netInfo["name"]?.toString(),
          bssid = null,
          subnet = subnet.ifBlank { netInfo["cidr"]?.toString() },
          gateway = netInfo["gateway"]?.toString(),
          linkUp = false,
          updatedAt = System.currentTimeMillis()
        )
      )
      return emptyList()
    }

    coroutineScope {
      ips.map { ip ->
        async(Dispatchers.IO) {
          sem.withPermit {
            if (scanCancel.get()) return@withPermit
            val mac = arp[ip]
            val reachable = try { InetAddress.getByName(ip).isReachable(timeoutMs) } catch (_: Exception) { false }
            val openPorts = if (reachable) TcpScanner.scan(ip, timeoutMs) else emptyList()
            val banners = if (openPorts.isNotEmpty()) openPorts.mapNotNull { p ->
              BannerGrabber.grab(ip, p)?.let { p to it.trim() }
            }.toMap() else emptyMap()
            val hostname = if (reachable) resolveHostname(ip) else null
            val vendor = OuiDb.lookup(mac)
            val os = guessOs(openPorts, banners, hostname, vendor)
            val now = System.currentTimeMillis()
            val id = mac ?: ip

            val old = devices.get(id)
            val dev = Device(
              id = id,
              ip = ip,
              name = old?.name ?: hostname ?: ip,
              mac = mac,
              hostname = hostname,
              os = os,
              vendor = vendor,
              online = reachable,
              lastSeen = now,
              openPorts = openPorts,
              banners = banners,
              owner = old?.owner,
              room = old?.room,
              note = old?.note,
              trust = old?.trust,
              type = old?.type,
              status = old?.status,
              via = old?.via,
              signal = old?.signal,
              activityToday = old?.activityToday,
              traffic = old?.traffic
            )

            devices.upsert(dev)
            deviceCache[id] = dev
            val evs = ChangeDetector.events(old, dev)
            evs.forEach { e ->
              events.insert(DeviceEvent(id, now, e))
              log("event $id $e")
            }

            if (old == null) {
              val autoBlock = rules.any { it.match == "new_device" && it.action == "block" }
              if (autoBlock) {
                val blocked = dev.copy(trust = "Blocked", status = "Blocked")
                devices.upsert(blocked)
                deviceCache[id] = blocked
              }
            }

            if (reachable) {
              synchronized(found) { found += dev }
              foundCount.incrementAndGet()
            }

            val done = completed.incrementAndGet()
            val pct = ((done.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            scanProgress.set(
              ScanProgress(
                progress = pct,
                phase = if (pct >= 100) "COMPLETE" else "SCANNING",
                networks = 1,
                devices = foundCount.get(),
                rssiDbm = null,
                ssid = netInfo["name"]?.toString(),
                bssid = null,
                subnet = subnet.ifBlank { netInfo["cidr"]?.toString() },
                gateway = netInfo["gateway"]?.toString(),
                linkUp = true,
                updatedAt = System.currentTimeMillis()
              )
            )
          }
        }
      }.awaitAll()
    }

    if (scanCancel.get()) {
      scanProgress.set(
        scanProgress.get().copy(
          phase = "CANCELLED",
          updatedAt = System.currentTimeMillis()
        )
      )
      return found
    }
    scanProgress.set(
      scanProgress.get().copy(
        progress = 100,
        phase = "COMPLETE",
        devices = foundCount.get(),
        updatedAt = System.currentTimeMillis()
      )
    )

    lastScanAt.set(System.currentTimeMillis())
    return found
  }

  fun currentResults(): List<Device> {
    return if (deviceCache.isNotEmpty()) deviceCache.values.toList() else devices.all()
  }

  fun scheduleScan(subnet: String, freq: String): Boolean {
    val freqMs = scheduleFrequencyMs(freq) ?: return false
    val entry = ScheduleEntry(subnet = subnet, freqMs = freqMs, nextRunAt = System.currentTimeMillis() + freqMs)
    scheduleEntries += entry
    return true
  }

  fun startScheduler() {
    schedulerScope.launch {
      while (isActive) {
        try {
          val now = System.currentTimeMillis()
          val due = scheduleEntries.filter { it.nextRunAt <= now }
          due.forEach { entry ->
            log("scheduled scan subnet=${entry.subnet}")
            val found = runCatching { performScan(entry.subnet, 300) }.getOrDefault(emptyList())
            log("scheduled scan complete devices=${found.size}")
            scheduleEntries.remove(entry)
            scheduleEntries += entry.copy(nextRunAt = now + entry.freqMs)
          }
        } catch (_: Exception) {}
        delay(15_000)
      }
    }
  }

  startScheduler()

  embeddedServer(Netty, port = port, host = host) {
    install(ContentNegotiation) { json() }
    install(CORS) {
      allowedOrigins
        .mapNotNull { origin -> runCatching { java.net.URI(origin) }.getOrNull() }
        .filter { it.scheme != null && !it.host.isNullOrBlank() && !it.host.contains('*') }
        .forEach { uri ->
          val hostPort = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
          allowHost(hostPort, schemes = listOf(uri.scheme))
        }
      allowHeader(HttpHeaders.ContentType)
      allowHeader(HttpHeaders.Authorization)
      allowMethod(HttpMethod.Get)
      allowMethod(HttpMethod.Post)
      allowMethod(HttpMethod.Put)
    }

    routing {
      // Serve web-ui
      staticFiles("/ui", webUiDir, index = "ninja_mobile_new.html")
      get("/") { call.respondRedirect("/ui/ninja_mobile_new.html") }

      get("/api/v1/system/info") {
        call.respond(
          SystemInfo(
            os = System.getProperty("os.name"),
            arch = System.getProperty("os.arch"),
            timeMs = System.currentTimeMillis()
          )
        )
      }

      get("/api/v1/system/permissions") {
        val ifaceUp = runCatching {
          java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).any { it.isUp && !it.isLoopback }
        }.getOrDefault(false)
        call.respond(
          mapOf(
            "nearbyWifi" to null,
            "fineLocation" to null,
            "coarseLocation" to null,
            "wifiState" to ifaceUp,
            "networkState" to ifaceUp
          )
        )
      }

      post("/api/v1/system/permissions/action") {
        val req = runCatching { call.receive<PermissionActionRequest>() }.getOrNull() ?: PermissionActionRequest()
        val action = req.action?.trim().orEmpty()
        if (action.isBlank()) {
          return@post call.respond(
            mapOf(
              "ok" to false,
              "action" to action,
              "message" to "Action is required."
            )
          )
        }
        val (ok, message) = tryLaunchSettings(action)
        call.respond(
          mapOf(
            "ok" to ok,
            "action" to action,
            "message" to message
          )
        )
      }

      get("/api/v1/system/state") {
        call.respond(
          mapOf(
            "scanProgress" to scanProgress.get(),
            "deviceCount" to currentResults().size,
            "lastScanAt" to lastScanAt.get(),
            "rules" to rules.toList(),
            "schedules" to schedules.toList(),
            "logQueueSize" to logQueue.size
          )
        )
      }

      get("/api/v1/network/info") {
        call.respond(localNetworkInfo())
      }

      post("/api/v1/discovery/scan") {
        val req = runCatching { call.receive<ScanRequest>() }.getOrNull() ?: ScanRequest()
        val subnet = req.subnet?.trim().orEmpty()
        log("scan requested subnet=$subnet")
        val timeout = req.timeoutMs ?: 300
        val existing = scanJob.get()
        if (existing != null && existing.isActive) {
          call.respond(currentResults())
          return@post
        }
        scanJob.set(
          schedulerScope.launch {
            runCatching { performScan(subnet, timeout) }
              .onFailure { err -> log("scan failed subnet=$subnet error=${err.message}") }
          }
        )
        call.respond(currentResults())
      }

      get("/api/v1/discovery/results") {
        call.respond(currentResults())
      }

      get("/api/v1/onvif/discover") {
        val service = OnvifDiscoveryService()
        val devices = withContext(Dispatchers.IO) { service.discover() }
        call.respond(devices)
      }

      get("/api/v1/discovery/progress") {
        call.respond(scanProgress.get())
      }

      post("/api/v1/discovery/stop") {
        scanCancel.set(true)
        scanJob.getAndSet(null)?.cancel()
        call.respond(mapOf("ok" to true))
      }

      get("/api/v1/devices/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val d = deviceCache[id] ?: devices.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(d)
      }

      get("/api/v1/devices/{id}/meta") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val d = deviceCache[id] ?: devices.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(
          DeviceMetaUpdate(
            name = d.name,
            owner = d.owner,
            room = d.room,
            note = d.note,
            trust = d.trust,
            type = d.type,
            status = d.status,
            via = d.via,
            signal = d.signal,
            activityToday = d.activityToday,
            traffic = d.traffic
          )
        )
      }

      put("/api/v1/devices/{id}/meta") {
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val patch = runCatching { call.receive<DeviceMetaUpdate>() }.getOrNull() ?: DeviceMetaUpdate()
        val d = deviceCache[id] ?: devices.get(id) ?: return@put call.respond(HttpStatusCode.NotFound)
        val updated = d.copy(
          name = patch.name ?: d.name,
          owner = patch.owner ?: d.owner,
          room = patch.room ?: d.room,
          note = patch.note ?: d.note,
          trust = patch.trust ?: d.trust,
          type = patch.type ?: d.type,
          status = patch.status ?: d.status,
          via = patch.via ?: d.via,
          signal = patch.signal ?: d.signal,
          activityToday = patch.activityToday ?: d.activityToday,
          traffic = patch.traffic ?: d.traffic
        )
        devices.upsert(updated)
        deviceCache[id] = updated
        call.respond(updated)
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
        call.respond(currentResults())
      }

      get("/api/v1/schedules") {
        call.respond(scheduleEntries.map { mapOf("subnet" to it.subnet, "freqMs" to it.freqMs, "nextRunAt" to it.nextRunAt) })
      }

      post("/api/v1/schedules") {
        val req = runCatching { call.receive<ScheduleRequest>() }.getOrNull() ?: ScheduleRequest()
        val subnet = req.subnet?.trim().orEmpty()
        val freq = req.freq?.trim().orEmpty()
        if (subnet.isBlank() || freq.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        if (!scheduleScan(subnet, freq)) return@post call.respond(HttpStatusCode.BadRequest)
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

      post("/api/v1/actions/portscan") {
        val req = runCatching { call.receive<PortScanRequest>() }.getOrNull() ?: PortScanRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val timeout = req.timeoutMs ?: 250
        val openPorts = TcpScanner.scan(ip, timeout)
        call.respond(mapOf("ok" to true, "ip" to ip, "openPorts" to openPorts))
      }

      post("/api/v1/actions/security") {
        val all = currentResults()
        val unknown = all.count { it.trust == null || it.trust == "Unknown" }
        val blocked = all.count { it.status == "Blocked" }
        val openPorts = all.sumOf { it.openPorts.size }
        call.respond(
          mapOf(
            "ok" to true,
            "devicesTotal" to all.size,
            "unknownDevices" to unknown,
            "blockedDevices" to blocked,
            "openPortsDetected" to openPorts,
            "recommendations" to listOf(
              "Review unknown devices",
              "Close unused ports",
              "Enable automatic blocking for new devices"
            )
          )
        )
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

      openClawRoutes(openClawGateway)

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
