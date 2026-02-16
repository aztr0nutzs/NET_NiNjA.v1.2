
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
import io.ktor.server.websocket.*
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import java.time.Duration
import org.slf4j.LoggerFactory
import server.openclaw.OpenClawGatewayRegistry
import server.openclaw.OpenClawGatewayState
import server.openclaw.openClawRoutes
import server.openclaw.openClawWebSocketServer

@Serializable
data class ScanRequest(val subnet: String? = null, val timeoutMs: Int? = 250)

@Serializable
data class ActionRequest(val ip: String? = null, val mac: String? = null, val url: String? = null, val command: String? = null)

@Serializable
data class ScheduleRequest(val subnet: String? = null, val freq: String? = null)

@Serializable
data class RuleRequest(val match: String? = null, val action: String? = null)

@Serializable
data class DeviceActionRequest(val action: String? = null)

@Serializable
data class RuleEntry(val match: String, val action: String)

@Serializable
data class PermissionActionRequest(val action: String? = null, val context: String? = null)

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
data class RouterProxyRequest(
  val path: String? = null,
  val method: String? = null,
  val body: JsonElement? = null,
  val auth: Boolean? = null,
  val timeoutMs: Int? = null,
  val baseUrl: String? = null,
  val gatewayAuthorization: String? = null
)

@Serializable
data class ScanProgress(
  val progress: Int = 0,
  val phase: String = "IDLE",
  val message: String? = null,
  val fixAction: String? = null,
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

@Serializable
data class MetricsResponse(
  val uptimeMs: Long,
  val cpuLoad: Double? = null,
  val memTotal: Long? = null,
  val memUsed: Long? = null,
  val devicesTotal: Int? = null,
  val devicesOnline: Int? = null,
  val lastScanAt: Long? = null,
  val error: String? = null
)

@Serializable
data class SpeedtestServer(
  val id: String,
  val name: String,
  val location: String
)

@Serializable
data class SpeedtestConfig(
  val serverId: String = "neo-1",
  val realMode: Boolean = true,
  val precisionRange: Boolean = false
)

@Serializable
data class SpeedtestConfigPatch(
  val serverId: String? = null,
  val realMode: Boolean? = null,
  val precisionRange: Boolean? = null
)

@Serializable
data class SpeedtestControlRequest(
  val serverId: String? = null,
  val realMode: Boolean? = null,
  val precisionRange: Boolean? = null
)

@Serializable
data class SpeedtestLiveState(
  val running: Boolean = false,
  val phase: String = "IDLE",
  val progress: Int = 0,
  val serverId: String = "neo-1",
  val pingMs: Double? = null,
  val jitterMs: Double? = null,
  val downloadMbps: Double? = null,
  val uploadMbps: Double? = null,
  val lossPct: Double? = null,
  val bufferbloat: String? = null,
  val error: String? = null,
  val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class SpeedtestResultRequest(
  val running: Boolean? = null,
  val phase: String? = null,
  val progress: Int? = null,
  val serverId: String? = null,
  val pingMs: Double? = null,
  val jitterMs: Double? = null,
  val downloadMbps: Double? = null,
  val uploadMbps: Double? = null,
  val lossPct: Double? = null,
  val bufferbloat: String? = null,
  val error: String? = null
)

private val LOGGER = LoggerFactory.getLogger("server.App")

fun main() {
  val config = resolveServerConfig()
  startServer(
    File("web-ui"),
    host = config.host,
    port = config.port,
    dbPath = config.dbPath,
    allowedOrigins = config.allowedOrigins,
    authToken = config.authToken
  )
}

fun startServer(
  webUiDir: File,
  host: String = "127.0.0.1",
  port: Int = 8787,
  dbPath: String = "netninja.db",
  allowedOrigins: List<String> = listOf("http://127.0.0.1:8787", "http://localhost:8787"),
  authToken: String? = null,
  wait: Boolean = true
): ApplicationEngine {
  // Always enforce token auth. For local dev, a token is auto-created/persisted if NET_NINJA_TOKEN is not set.
  val expectedToken = ServerApiAuth.loadOrCreate(dbPath = dbPath, envToken = authToken)
  val isLoopbackBind = host == "127.0.0.1" || host == "localhost" || host == "::1"
  if (!isLoopbackBind && authToken.isNullOrBlank()) {
    // For non-loopback binds, ops must supply a token via NET_NINJA_TOKEN.
    throw IllegalStateException("NET_NINJA_TOKEN must be set when binding to non-loopback host '$host'.")
  }

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
  val speedtestConfig = AtomicReference(SpeedtestConfig())
  val speedtestLive = AtomicReference(SpeedtestLiveState())
  val speedtestAbort = AtomicBoolean(false)
  val speedtestServers = listOf(
    SpeedtestServer(id = "neo-1", name = "NEO-1", location = "Downtown Relay"),
    SpeedtestServer(id = "arc-7", name = "ARC-7", location = "Subnet Spire"),
    SpeedtestServer(id = "hex-3", name = "HEX-3", location = "Industrial Backbone"),
    SpeedtestServer(id = "luna-9", name = "LUNA-9", location = "Edge Satellite")
  )
  val openClawGateway = OpenClawGatewayRegistry()
  OpenClawGatewayState.bindRegistry(openClawGateway)

  val logQueue = ConcurrentLinkedQueue<String>()
  fun log(msg: String) { logQueue.add("${System.currentTimeMillis()}: $msg") }
  log("auth enabled token=${expectedToken.take(6)}… (source=${if (!authToken.isNullOrBlank()) "env" else "file/generated"})")

  fun logException(where: String, t: Throwable, fields: Map<String, Any?> = emptyMap()) {
    if (t is CancellationException) throw t
    val suffix = if (fields.isEmpty()) "" else " fields=$fields"
    LOGGER.error("$where failed: ${t.message}$suffix", t)
    log("ERROR where=$where msg=${t.message ?: t::class.simpleName}")
  }

  fun <T> catching(where: String, fields: Map<String, Any?> = emptyMap(), block: () -> T): Result<T> {
    return runCatching(block).onFailure { t -> logException(where, t, fields) }
  }

  fun <T> catchingOrNull(where: String, fields: Map<String, Any?> = emptyMap(), block: () -> T): T? {
    return catching(where, fields, block).getOrNull()
  }

  fun <T> catchingOrDefault(where: String, default: T, fields: Map<String, Any?> = emptyMap(), block: () -> T): T {
    return catching(where, fields, block).getOrElse { default }
  }

  suspend fun <T> catchingSuspend(where: String, fields: Map<String, Any?> = emptyMap(), block: suspend () -> T): Result<T> {
    return try {
      Result.success(block())
    } catch (t: Throwable) {
      logException(where, t, fields)
      Result.failure(t)
    }
  }

  suspend fun <T> catchingSuspendOrNull(where: String, fields: Map<String, Any?> = emptyMap(), block: suspend () -> T): T? {
    return catchingSuspend(where, fields, block).getOrNull()
  }

  suspend fun <T> catchingSuspendOrDefault(
    where: String,
    default: T,
    fields: Map<String, Any?> = emptyMap(),
    block: suspend () -> T
  ): T {
    return catchingSuspend(where, fields, block).getOrElse { default }
  }

  suspend fun <T> retryTransientOrNull(
    where: String,
    attempts: Int,
    initialDelayMs: Long,
    maxDelayMs: Long,
    fields: Map<String, Any?> = emptyMap(),
    isTransient: (Throwable) -> Boolean,
    block: suspend () -> T
  ): T? {
    require(attempts >= 1) { "attempts must be >= 1" }
    var delayMs = initialDelayMs.coerceAtLeast(0)
    repeat(attempts) { idx ->
      try {
        return block()
      } catch (t: Throwable) {
        if (t is CancellationException) throw t
        val lastAttempt = idx == attempts - 1
        if (lastAttempt || !isTransient(t)) {
          logException(where, t, fields + mapOf("attempt" to (idx + 1), "attempts" to attempts))
          return null
        }
      }
      if (delayMs > 0) delay(delayMs)
      delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
    }
    return null
  }

  suspend fun isReachableWithRetry(ip: String, timeoutMs: Int, attempts: Int): Boolean {
    // Try ICMP first (works on Linux, often blocked on Windows without admin)
    val icmpResult = retryTransientOrNull(
      where = "isReachable",
      attempts = attempts,
      initialDelayMs = 40,
      maxDelayMs = 200,
      fields = mapOf("ip" to ip, "timeoutMs" to timeoutMs),
      isTransient = { t -> t is java.net.SocketTimeoutException || t is java.io.IOException }
    ) {
      InetAddress.getByName(ip).isReachable(timeoutMs)
    } ?: false

    if (icmpResult) return true

    // Fallback: TCP probe on common ports (essential on Windows where ICMP needs admin)
    return withContext(Dispatchers.IO) {
      TcpScanner.isReachableTcp(ip, timeoutMs)
    }
  }

  suspend fun resolveHostnameWithRetry(ip: String): String? {
    return retryTransientOrNull(
      where = "resolveHostname",
      attempts = 2,
      initialDelayMs = 40,
      maxDelayMs = 200,
      fields = mapOf("ip" to ip),
      isTransient = { t ->
        t is java.net.SocketTimeoutException || (t is java.io.IOException && t !is java.net.UnknownHostException)
      }
    ) {
      val host = InetAddress.getByName(ip).canonicalHostName
      if (host == ip) null else host
    }
  }

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
        message = null,
        fixAction = null,
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
          message = "Scan blocked: no network interface/subnet detected.",
          fixAction = null,
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
            val reachable = isReachableWithRetry(ip, timeoutMs, attempts = 2)
            val openPorts = if (reachable) TcpScanner.scan(ip, timeoutMs) else emptyList()
            val banners = if (openPorts.isNotEmpty()) openPorts.mapNotNull { p ->
              BannerGrabber.grab(ip, p)?.let { p to it.trim() }
            }.toMap() else emptyMap()
            val hostname = if (reachable) resolveHostnameWithRetry(ip) else null
            val vendor = OuiDb.lookup(mac)
            val os = guessOs(openPorts, banners, hostname, vendor)
            val now = System.currentTimeMillis()
            val id = mac ?: ip

            val old = devices.get(id)
            val via = old?.via ?: netInfo["iface"]?.toString() ?: netInfo["name"]?.toString()
            val trust = old?.trust ?: "Unknown"
            val status = old?.status ?: if (reachable) "Online" else "Offline"
            val type = old?.type ?: guessDeviceType(os, vendor, openPorts, banners, hostname)
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
              trust = trust,
              type = type,
              status = status,
              via = via,
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
                // Do not emit COMPLETE until the scan is actually finalized (see end-of-scan block below).
                phase = "SCANNING",
                message = null,
                fixAction = null,
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
          message = "Scan cancelled.",
          fixAction = null,
          updatedAt = System.currentTimeMillis()
        )
      )
      return found
    }
    scanProgress.set(
      scanProgress.get().copy(
        progress = 100,
        phase = "COMPLETE",
        message = null,
        fixAction = null,
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
            val found = catchingSuspend("scheduler:performScan", fields = mapOf("subnet" to entry.subnet)) {
              performScan(entry.subnet, 300)
            }.getOrDefault(emptyList())
            log("scheduled scan complete devices=${found.size}")
            scheduleEntries.remove(entry)
            scheduleEntries += entry.copy(nextRunAt = now + entry.freqMs)
          }
        } catch (e: Exception) {
          logException("scheduler:loop", e)
        }
        delay(15_000)
      }
    }
  }

  startScheduler()

  val engine = embeddedServer(Netty, port = port, host = host) {
    environment.monitor.subscribe(ApplicationStopping) {
      catching("shutdown:scanJob.cancel") { scanJob.get()?.cancel() }
      catching("shutdown:schedulerScope.cancel") { schedulerScope.cancel() }
      catching("shutdown:conn.close") { conn.close() }
    }

    install(ContentNegotiation) { json() }
    install(WebSockets) {
      pingPeriod = Duration.ofSeconds(15)
      timeout = Duration.ofSeconds(30)
      maxFrameSize = 1_048_576
    }
    install(CORS) {
      allowedOrigins
        .mapNotNull { origin -> catchingOrNull("cors:parseOrigin", fields = mapOf("origin" to origin)) { java.net.URI(origin) } }
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

    val unauthorizedLimiter = RateLimiter(capacity = 15.0, refillTokensPerMs = 15.0 / 60_000.0) // 15/min burst 15
    val rotateLimiter = RateLimiter(capacity = 2.0, refillTokensPerMs = 2.0 / (10 * 60_000.0)) // 2 per 10 minutes
    intercept(ApplicationCallPipeline.Plugins) {
      val path = call.request.path()
      val requiresAuth = when {
        path == "/api/v1/system/info" -> false // allow health/readiness without auth
        path.startsWith("/api/") -> true
        path == "/openclaw/ws" -> true
        else -> false
      }
      if (!requiresAuth) return@intercept

      fun extractToken(): String? {
        val auth = call.request.headers[HttpHeaders.Authorization]?.trim().orEmpty()
        if (auth.startsWith("Bearer ", ignoreCase = true)) {
          val raw = auth.substringAfter("Bearer ", "").trim()
          if (raw.isNotBlank()) return raw
        }
        call.request.headers[ServerApiAuth.HEADER_TOKEN]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        call.request.queryParameters[ServerApiAuth.QUERY_PARAM]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        call.request.queryParameters["t"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return null
      }

      val provided = extractToken()
      if (!ServerApiAuth.validate(provided)) {
        val remote = call.request.local.remoteHost
        val key = "${remote}:${path}:${provided?.take(8).orEmpty()}"
        if (!unauthorizedLimiter.tryConsume(key)) {
          call.respond(HttpStatusCode.TooManyRequests, mapOf("ok" to false, "error" to "rate_limited"))
          finish()
          return@intercept
        }
        call.respond(HttpStatusCode.Unauthorized, mapOf("ok" to false, "error" to "unauthorized"))
        finish()
        return@intercept
      }

      // Sensitive endpoint throttling (auth already verified).
      if (path == "/api/v1/system/token/rotate") {
        val remote = call.request.local.remoteHost
        val key = "${remote}:${provided?.take(12).orEmpty()}"
        if (!rotateLimiter.tryConsume(key)) {
          call.respond(HttpStatusCode.TooManyRequests, mapOf("ok" to false, "error" to "rate_limited"))
          finish()
          return@intercept
        }
      }
    }

    routing {
      // Serve web-ui
      staticFiles("/ui", webUiDir, index = "ninja_mobile_new.html")
      staticFiles("/assets", File(webUiDir, "assets"))
      get("/") { call.respondRedirect("/ui/ninja_mobile_new.html") }

      openClawWebSocketServer()
      openClawRoutes()

      get("/api/v1/system/info") {
        call.respond(
          SystemInfo(
            os = System.getProperty("os.name"),
            arch = System.getProperty("os.arch"),
            timeMs = System.currentTimeMillis()
          )
        )
      }

      post("/api/v1/system/token/rotate") {
        val rotated = ServerApiAuth.rotate(dbPath = dbPath, graceMs = 5 * 60_000L)
        log("token rotated previousValidUntilMs=${rotated.previousValidUntilMs}")
        call.respond(mapOf("ok" to true, "token" to rotated.token, "previousValidUntilMs" to rotated.previousValidUntilMs))
      }

      get("/api/v1/system/permissions") {
        val ifaceUp = catchingOrDefault("systemPermissions:ifaceUp", false) {
          java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).any { it.isUp && !it.isLoopback }
        }
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
        val req = catchingSuspendOrDefault("permissionAction:receive", PermissionActionRequest()) { call.receive<PermissionActionRequest>() }
        val action = req.action?.trim().orEmpty()
        if (action.isBlank()) {
          call.respond(
            HttpStatusCode.BadRequest,
            PermissionActionResponse(
              ok = false,
              message = "Missing 'action'.",
              platform = detectHostPlatform().apiName,
              details = PermissionActionDetails(action = null, context = req.context?.trim())
            )
          )
          return@post
        }

        call.respond(performPermissionAction(action = action, context = req.context?.trim()))
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

      post("/api/v1/router/proxy") {
        val req = catchingSuspendOrDefault("routerProxy:receive", RouterProxyRequest()) { call.receive<RouterProxyRequest>() }
        val method = req.method?.trim()?.uppercase().orEmpty().ifBlank { "GET" }
        val path = req.path?.trim().orEmpty()
        val baseUrl = req.baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "http://192.168.12.1"
        val timeoutMs = (req.timeoutMs ?: 5000).coerceIn(500, 15_000)
        val gatewayAuthorization = req.gatewayAuthorization?.trim().orEmpty()

        if (path.isBlank() || !path.startsWith("/") || !path.startsWith("/TMI/")) {
          call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "invalid_path"))
          return@post
        }

        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
          call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "invalid_method"))
          return@post
        }

        val target = try {
          java.net.URL(baseUrl.trimEnd('/') + path)
        } catch (_: Exception) {
          call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "invalid_base_url"))
          return@post
        }

        val connection = try {
          (target.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty(HttpHeaders.Accept, ContentType.Application.Json.toString())
            if (gatewayAuthorization.isNotBlank()) {
              setRequestProperty(HttpHeaders.Authorization, gatewayAuthorization)
            }
          }
        } catch (_: Exception) {
          call.respond(HttpStatusCode.BadGateway, mapOf("ok" to false, "error" to "proxy_connect_failed"))
          return@post
        }

        try {
          val payloadText = req.body?.let { Json.encodeToString(JsonElement.serializer(), it) }
          if (payloadText != null && method in setOf("POST", "PUT", "PATCH", "DELETE")) {
            connection.doOutput = true
            connection.setRequestProperty(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            connection.outputStream.use { out ->
              out.write(payloadText.toByteArray(Charsets.UTF_8))
            }
          }

          val statusCode = catchingOrDefault("routerProxy:status", 502, fields = mapOf("path" to path, "method" to method)) {
            connection.responseCode
          }
          val responseText = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
          val responseType = connection.contentType
            ?.substringBefore(';')
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> catchingOrNull("routerProxy:contentType.parse", fields = mapOf("contentType" to raw)) { ContentType.parse(raw) } }
            ?: ContentType.Application.Json

          call.respondText(
            text = responseText,
            contentType = responseType,
            status = HttpStatusCode.fromValue(statusCode.coerceIn(100, 599))
          )
        } catch (_: Exception) {
          call.respond(HttpStatusCode.BadGateway, mapOf("ok" to false, "error" to "proxy_request_failed"))
        } finally {
          connection.disconnect()
        }
      }

      get("/api/v1/speedtest/servers") {
        call.respond(speedtestServers)
      }

      get("/api/v1/speedtest/config") {
        call.respond(speedtestConfig.get())
      }

      post("/api/v1/speedtest/config") {
        val req = catchingSuspendOrDefault("speedtest:config.receive", SpeedtestConfigPatch()) { call.receive<SpeedtestConfigPatch>() }
        val current = speedtestConfig.get()
        val updated = current.copy(
          serverId = req.serverId?.trim()?.takeIf { it.isNotBlank() } ?: current.serverId,
          realMode = req.realMode ?: current.realMode,
          precisionRange = req.precisionRange ?: current.precisionRange
        )
        speedtestConfig.set(updated)
        call.respond(updated)
      }

      post("/api/v1/speedtest/start") {
        val req = catchingSuspendOrDefault("speedtest:start.receive", SpeedtestControlRequest()) { call.receive<SpeedtestControlRequest>() }
        val current = speedtestConfig.get()
        val updatedConfig = current.copy(
          serverId = req.serverId?.trim()?.takeIf { it.isNotBlank() } ?: current.serverId,
          realMode = req.realMode ?: current.realMode,
          precisionRange = req.precisionRange ?: current.precisionRange
        )
        speedtestConfig.set(updatedConfig)
        speedtestAbort.set(false)
        val live = speedtestLive.get().copy(
          running = true,
          phase = "PING",
          progress = 0,
          serverId = updatedConfig.serverId,
          error = null,
          updatedAt = System.currentTimeMillis()
        )
        speedtestLive.set(live)
        call.respond(mapOf("ok" to true, "config" to updatedConfig, "state" to live))
      }

      post("/api/v1/speedtest/abort") {
        speedtestAbort.set(true)
        val updated = speedtestLive.get().copy(
          running = false,
          phase = "ABORTED",
          error = "aborted",
          updatedAt = System.currentTimeMillis()
        )
        speedtestLive.set(updated)
        call.respond(mapOf("ok" to true, "state" to updated))
      }

      post("/api/v1/speedtest/reset") {
        speedtestAbort.set(false)
        val cfg = speedtestConfig.get()
        val reset = SpeedtestLiveState(serverId = cfg.serverId)
        speedtestLive.set(reset)
        call.respond(mapOf("ok" to true, "state" to reset))
      }

      get("/api/v1/speedtest/live") {
        call.respond(speedtestLive.get())
      }

      post("/api/v1/speedtest/result") {
        val req = catchingSuspendOrDefault("speedtest:result.receive", SpeedtestResultRequest()) { call.receive<SpeedtestResultRequest>() }
        val prev = speedtestLive.get()
        val updated = prev.copy(
          running = req.running ?: prev.running,
          phase = req.phase ?: prev.phase,
          progress = req.progress?.coerceIn(0, 100) ?: prev.progress,
          serverId = req.serverId?.takeIf { it.isNotBlank() } ?: prev.serverId,
          pingMs = req.pingMs ?: prev.pingMs,
          jitterMs = req.jitterMs ?: prev.jitterMs,
          downloadMbps = req.downloadMbps ?: prev.downloadMbps,
          uploadMbps = req.uploadMbps ?: prev.uploadMbps,
          lossPct = req.lossPct ?: prev.lossPct,
          bufferbloat = req.bufferbloat ?: prev.bufferbloat,
          error = req.error,
          updatedAt = System.currentTimeMillis()
        )
        speedtestLive.set(updated)
        call.respond(mapOf("ok" to true, "state" to updated))
      }

      get("/api/v1/speedtest/ping") {
        val now = System.currentTimeMillis()
        val updated = speedtestLive.get().copy(
          phase = if (speedtestLive.get().running) "PING" else speedtestLive.get().phase,
          updatedAt = now
        )
        speedtestLive.set(updated)
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respond(mapOf("ok" to true, "serverTimeMs" to now))
      }

      get("/api/v1/speedtest/download") {
        val bytes = call.request.queryParameters["bytes"]?.toLongOrNull()?.coerceIn(1_024L, 250_000_000L) ?: 25_000_000L
        val startedAt = System.currentTimeMillis()
        speedtestLive.set(
          speedtestLive.get().copy(
            running = true,
            phase = "DOWNLOAD",
            progress = 0,
            error = null,
            updatedAt = startedAt
          )
        )
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondBytesWriter(contentType = ContentType.Application.OctetStream) {
          val chunk = ByteArray(64 * 1024)
          var sent = 0L
          while (sent < bytes) {
            if (speedtestAbort.get()) break
            val n = minOf(chunk.size.toLong(), bytes - sent).toInt()
            repeat(n) { idx ->
              chunk[idx] = ((sent + idx) and 0xFFL).toByte()
            }
            writeFully(chunk, 0, n)
            sent += n
            if (sent % (1024L * 1024L) == 0L) {
              val pct = ((sent.toDouble() / bytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
              speedtestLive.set(speedtestLive.get().copy(progress = pct, updatedAt = System.currentTimeMillis()))
            }
          }
          flush()
        }
      }

      post("/api/v1/speedtest/upload") {
        val startedAt = System.currentTimeMillis()
        speedtestLive.set(
          speedtestLive.get().copy(
            running = true,
            phase = "UPLOAD",
            error = null,
            updatedAt = startedAt
          )
        )
        val channel = call.receiveChannel()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          if (speedtestAbort.get()) break
          val read = channel.readAvailable(buffer, 0, buffer.size)
          if (read <= 0) break
          total += read.toLong()
        }
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val mbps = (total * 8.0) / (elapsedMs.toDouble() / 1000.0) / 1_000_000.0
        val updated = speedtestLive.get().copy(
          running = false,
          phase = if (speedtestAbort.get()) "ABORTED" else "COMPLETE",
          progress = if (speedtestAbort.get()) speedtestLive.get().progress else 100,
          uploadMbps = mbps,
          updatedAt = System.currentTimeMillis()
        )
        speedtestLive.set(updated)
        call.respond(mapOf("ok" to true, "bytesReceived" to total, "elapsedMs" to elapsedMs, "uploadMbps" to mbps))
      }

      // Desktop builds do not have Android-style permission gates, but the UI expects a stable endpoint.
      get("/api/v1/discovery/preconditions") {
        call.respond(
          mapOf(
            "ready" to true,
            "blocker" to null,
            "reason" to null,
            "fixAction" to null
          )
        )
      }
      post("/api/v1/discovery/scan") {
        val req = catchingSuspendOrDefault("scan:receive", ScanRequest()) { call.receive<ScanRequest>() }
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
            catchingSuspend("scan:performScan", fields = mapOf("subnet" to subnet, "timeoutMs" to timeout)) {
              performScan(subnet, timeout)
            }.onFailure { err -> log("scan failed subnet=$subnet error=${err.message}") }
          }
        )
        call.respond(currentResults())
      }

      get("/api/v1/discovery/results") {
        call.respond(currentResults())
      }

      get("/api/v1/onvif/discover") {
        val devices = catchingSuspend("onvif:discover") {
          // Keep the probe timeout below the endpoint timeout so blocked UDP reads cannot linger past the HTTP request.
          val service = OnvifDiscoveryService(timeoutMs = 1200)
          withTimeoutOrNull(1500) {
            withContext(Dispatchers.IO) { service.discover() }
          } ?: emptyList()
        }.getOrDefault(emptyList())
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
        val patch = catchingSuspendOrDefault("deviceMeta:receive", DeviceMetaUpdate(), fields = mapOf("id" to id)) { call.receive<DeviceMetaUpdate>() }
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

      post("/api/v1/devices/{id}/actions") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val req = catchingSuspendOrDefault("deviceAction:receive", DeviceActionRequest(), fields = mapOf("id" to id)) { call.receive<DeviceActionRequest>() }
        val action = req.action?.trim()?.lowercase()
        if (action.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val d = deviceCache[id] ?: devices.get(id) ?: return@post call.respond(HttpStatusCode.NotFound)
        val updated = applyDeviceAction(d, action) ?: return@post call.respond(HttpStatusCode.BadRequest)
        devices.upsert(updated)
        deviceCache[id] = updated
        val ts = System.currentTimeMillis()
        events.insert(DeviceEvent(id, ts, "ACTION_${action.uppercase()}"))
        log("device action $action id=$id")
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
        val req = catchingSuspendOrDefault("schedules:receive", ScheduleRequest()) { call.receive<ScheduleRequest>() }
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
        val req = catchingSuspendOrDefault("rules:receive", RuleRequest()) { call.receive<RuleRequest>() }
        val match = req.match?.trim().orEmpty()
        val action = req.action?.trim().orEmpty()
        if (match.isBlank() || action.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        rules += RuleEntry(match, action)
        call.respond(mapOf("ok" to true))
      }

      post("/api/v1/actions/ping") {
        val req = catchingSuspendOrDefault("action:ping.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val start = System.currentTimeMillis()
        val reachable = isReachableWithRetry(ip, 350, attempts = 2)
        val rtt = if (reachable) (System.currentTimeMillis() - start) else null
        call.respond(mapOf("ok" to reachable, "ip" to ip, "rttMs" to rtt))
      }

      post("/api/v1/actions/http") {
        val req = catchingSuspendOrDefault("action:http.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val url = req.url?.trim()
        if (url.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val status = catchingOrDefault("action:http.request", 0, fields = mapOf("url" to url.take(256))) {
          java.net.URL(url).openConnection().apply { connectTimeout = 800; readTimeout = 800 }.let { conn ->
            (conn as? java.net.HttpURLConnection)?.responseCode ?: 0
          }
        }
        call.respond(mapOf("ok" to (status in 200..399), "status" to status))
      }

      post("/api/v1/actions/portscan") {
        val req = catchingSuspendOrDefault("action:portscan.receive", PortScanRequest()) { call.receive<PortScanRequest>() }
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
        val req = catchingSuspendOrDefault("action:wol.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val mac = req.mac?.trim()
        if (mac.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val ok = catching("action:wol.send", fields = mapOf("mac" to mac)) { sendMagicPacket(mac) }.isSuccess
        call.respond(mapOf("ok" to ok, "mac" to mac))
      }

      post("/api/v1/actions/snmp") {
        val req = catchingSuspendOrDefault("action:snmp.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val reachable = catchingOrDefault("action:snmp.portProbe", false, fields = mapOf("ip" to ip)) { TcpScanner.scan(ip, 300).contains(161) }
        call.respond(mapOf("ok" to reachable, "ip" to ip))
      }

      post("/api/v1/actions/ssh") {
        val req = catchingSuspendOrDefault("action:ssh.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val reachable = catchingOrDefault("action:ssh.portProbe", false, fields = mapOf("ip" to ip)) { TcpScanner.scan(ip, 400).contains(22) }
        call.respond(mapOf("ok" to reachable, "ip" to ip, "note" to "SSH port probe only"))
      }

      get("/api/v1/metrics") {
        val payload = catching("metrics:build") {
          val osBean = ManagementFactory.getOperatingSystemMXBean()
          val load = (osBean as? com.sun.management.OperatingSystemMXBean)?.systemCpuLoad ?: -1.0
          val memTotal = Runtime.getRuntime().totalMemory()
          val memFree = Runtime.getRuntime().freeMemory()
          val memUsed = memTotal - memFree
          val all = catching("metrics:devices.all") { devices.all() }.getOrElse { deviceCache.values.toList() }
          val online = all.count { it.online }
          MetricsResponse(
            uptimeMs = ManagementFactory.getRuntimeMXBean().uptime,
            cpuLoad = if (load >= 0) load else null,
            memTotal = memTotal,
            memUsed = memUsed,
            devicesTotal = all.size,
            devicesOnline = online,
            lastScanAt = lastScanAt.get()
          )
        }.getOrElse { err ->
          MetricsResponse(
            uptimeMs = ManagementFactory.getRuntimeMXBean().uptime,
            cpuLoad = null,
            memTotal = Runtime.getRuntime().totalMemory(),
            memUsed = null,
            devicesTotal = deviceCache.size,
            devicesOnline = null,
            lastScanAt = lastScanAt.get(),
            error = err.message ?: err::class.simpleName
          )
        }
        call.respond(payload)
      }


      // SSE stream for logs
      get("/api/v1/logs/stream") {
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
          // Exit cleanly on client disconnect/cancellation to avoid leaking coroutines.
          try {
            while (true) {
              var emitted = 0
              while (true) {
                val line = logQueue.poll() ?: break
                write("data: ${line.replace("\n", " ")}\n\n")
                emitted++
              }
              flush()
              delay(if (emitted == 0) 350 else 50)
            }
          } catch (_: CancellationException) {
            // Normal: client disconnected.
          } catch (_: Throwable) {
            // Broken pipe / shutdown. Treat as disconnect.
          }
        }
      }
    }
  }
  engine.start(wait = wait)
  return engine
}

private fun applyDeviceAction(device: Device, action: String): Device? {
  return when (action.lowercase()) {
    "block" -> device.copy(status = "Blocked", trust = "Blocked")
    "unblock" -> device.copy(
      status = if (device.online) "Online" else "Offline",
      trust = if (device.trust == "Blocked") "Unknown" else device.trust
    )
    "pause" -> device.copy(status = "Paused")
    "unpause" -> device.copy(status = if (device.online) "Online" else "Offline")
    "trust" -> device.copy(trust = "Trusted")
    "untrust" -> device.copy(trust = "Unknown")
    else -> null
  }
}

private fun guessDeviceType(
  os: String?,
  vendor: String?,
  openPorts: List<Int>,
  banners: Map<Int, String>,
  hostname: String?
): String? {
  val vend = vendor?.lowercase().orEmpty()
  val host = hostname?.lowercase().orEmpty()
  val bannerText = banners.values.joinToString(" ").lowercase()
  return when {
    openPorts.contains(554) || bannerText.contains("rtsp") -> "Camera"
    openPorts.contains(9100) || vend.contains("hp") || vend.contains("brother") -> "Printer"
    openPorts.contains(53) || openPorts.contains(67) -> "Router"
    (openPorts.contains(80) || openPorts.contains(443)) &&
      (vend.contains("ubiquiti") || vend.contains("netgear") || vend.contains("linksys")) -> "Gateway"
    vend.contains("apple") || host.contains("iphone") || host.contains("ipad") -> "Mobile"
    vend.contains("samsung") || host.contains("android") -> "Mobile"
    vend.contains("microsoft") || os == "Windows" -> "PC"
    vend.contains("raspberry") || host.contains("pi") -> "IoT"
    else -> os
  }
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
  // Find the best non-loopback, non-virtual, non-link-local IPv4 interface.
  val bestIface = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
    .sortedByDescending { it.interfaceAddresses.size } // prefer interfaces with more addresses
    .flatMap { iface ->
      iface.interfaceAddresses.mapNotNull { ifAddr ->
        val addr = ifAddr.address
        val ip = addr.hostAddress
        // Skip IPv6, loopback, link-local (169.254.x.x), APIPA, and multicast
        if (ip.contains(":")) return@mapNotNull null
        if (addr.isLoopbackAddress) return@mapNotNull null
        if (addr.isLinkLocalAddress) return@mapNotNull null
        if (ip.startsWith("127.")) return@mapNotNull null
        if (ip.startsWith("169.254.")) return@mapNotNull null
        // Skip common virtual/VPN adapter name patterns (Hyper-V, VMware, VirtualBox, Docker)
        val ifName = iface.displayName?.lowercase() ?: ""
        if (ifName.contains("virtual") || ifName.contains("vmware") || ifName.contains("vbox") ||
            ifName.contains("docker") || ifName.contains("hyper-v") || ifName.contains("vethernet")) return@mapNotNull null
        val prefix = ifAddr.networkPrefixLength.toInt()
        Triple(ip, prefix, iface)
      }
    }
  if (bestIface.isEmpty()) {
    // Ultimate fallback — any IPv4 that is not 127.x
    val anyIp = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
      .flatMap { java.util.Collections.list(it.inetAddresses) }
      .mapNotNull { a -> a.hostAddress.takeIf { !it.contains(":") && !it.startsWith("127.") } }
      .firstOrNull() ?: return emptyList()
    return cidrToIps("${anyIp.substringBeforeLast(".")}.0/24")
  }
  val (ip, prefix, _) = bestIface.first()
  return cidrToIps("${ip.substringBeforeLast(".")}.0/$prefix")
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
  // Find the best real network interface — filter out loopback, virtual, and link-local on Windows.
  val iface = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
    .filter { nif ->
      val name = nif.displayName?.lowercase() ?: ""
      // Exclude common virtual adapter patterns (Hyper-V, VMware, VirtualBox, Docker)
      !name.contains("virtual") && !name.contains("vmware") && !name.contains("vbox") &&
        !name.contains("docker") && !name.contains("hyper-v") && !name.contains("vethernet")
    }
    .firstOrNull { nif ->
      nif.interfaceAddresses.any { ifAddr ->
        val a = ifAddr.address
        val ip = a.hostAddress
        !ip.contains(":") && !a.isLoopbackAddress && !a.isLinkLocalAddress &&
          !ip.startsWith("127.") && !ip.startsWith("169.254.")
      }
    }
  val addr = iface?.interfaceAddresses?.firstOrNull { ifAddr ->
    val a = ifAddr.address
    val ip = a.hostAddress
    !ip.contains(":") && !a.isLoopbackAddress && !a.isLinkLocalAddress &&
      !ip.startsWith("127.") && !ip.startsWith("169.254.")
  }
  val ip = addr?.address?.hostAddress
  val prefix = addr?.networkPrefixLength?.toInt()
  val cidr = if (ip != null && prefix != null) "${ip.substringBeforeLast(".")}.0/$prefix" else null
  val gateway = resolveDefaultGateway()
  val dns = resolveDnsServers()
  val mac = iface?.hardwareAddress?.toList()?.joinToString(":") { b -> "%02x".format(b) }
  val linkUp = ip != null && cidr != null
  return mapOf(
    "name" to (iface?.displayName ?: "Network"),
    "ip" to ip,
    "cidr" to cidr,
    "gateway" to gateway,
    "dns" to dns,
    "iface" to iface?.name,
    "mac" to mac,
    "linkUp" to linkUp
  )
}

private fun resolveDefaultGateway(): String? {
  // Linux: parse /proc/net/route
  val routeFile = File("/proc/net/route")
  if (routeFile.exists()) {
    return try {
      routeFile.readLines().drop(1).mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size <= 2) return@mapNotNull null
        val dest = parts[1]
        val gateway = parts[2]
        if (dest != "00000000") return@mapNotNull null
        val gwInt = gateway.toLong(16)
        val b1 = (gwInt and 0xFF).toInt()
        val b2 = ((gwInt shr 8) and 0xFF).toInt()
        val b3 = ((gwInt shr 16) and 0xFF).toInt()
        val b4 = ((gwInt shr 24) and 0xFF).toInt()
        "$b1.$b2.$b3.$b4"
      }.firstOrNull()
    } catch (t: Throwable) {
      LOGGER.warn("resolveDefaultGateway /proc failed: ${t.message}", t)
      null
    }
  }

  // Cross-platform fallback: try `ip route` then `netstat -rn`
  return resolveGatewayViaShell()
}

/** Shell-based gateway resolution for Windows, macOS, and non-/proc Linux. */
private fun resolveGatewayViaShell(): String? {
  val ipPattern = Regex("""(\d+\.\d+\.\d+\.\d+)""")
  val os = System.getProperty("os.name", "").lowercase()

  val commands: List<List<String>> = when {
    os.contains("win") -> listOf(
      listOf("cmd", "/c", "ipconfig"),
      listOf("cmd", "/c", "route", "print", "0.0.0.0")
    )
    else -> listOf(
      listOf("ip", "route", "show", "default"),
      listOf("netstat", "-rn")
    )
  }

  for (cmd in commands) {
    try {
      val process = ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()
      if (process.exitValue() != 0) continue

      for (line in output.lines()) {
        val lower = line.lowercase().trim()
        // Linux/macOS: "default via 192.168.1.1 ..."
        if (lower.startsWith("default")) {
          val match = ipPattern.find(line)
          if (match != null) return match.groupValues[1]
        }
        // Windows ipconfig: "Default Gateway . . . . . . . . . : 192.168.1.1"
        if (lower.contains("default gateway") || lower.contains("puerta de enlace") || lower.contains("passerelle")) {
          val match = ipPattern.find(line)
          if (match != null) return match.groupValues[1]
        }
        // Windows route print: "0.0.0.0  ... 192.168.1.1 ..."
        if (lower.startsWith("0.0.0.0")) {
          val ips = ipPattern.findAll(line).map { it.groupValues[1] }.toList()
          val gw = ips.firstOrNull { it != "0.0.0.0" }
          if (gw != null) return gw
        }
      }
    } catch (_: Exception) {
      // command not available – try next
    }
  }
  LOGGER.debug("resolveDefaultGateway: no cross-platform method succeeded")
  return null
}

private fun resolveDnsServers(): String? {
  // Linux / macOS: /etc/resolv.conf
  val resolv = File("/etc/resolv.conf")
  if (resolv.exists()) {
    val servers = try {
      resolv.readLines().mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith("nameserver")) return@mapNotNull null
        trimmed.split(Regex("\\s+")).getOrNull(1)
      }.filter { it.isNotBlank() }
    } catch (t: Throwable) {
      LOGGER.warn("resolveDnsServers /etc/resolv.conf failed: ${t.message}", t)
      emptyList()
    }
    if (servers.isNotEmpty()) return servers.joinToString(", ")
  }

  // Windows fallback: parse `nslookup` output (universally available)
  return resolveDnsViaShell()
}

/** Shell-based DNS server resolution for Windows and fallback. */
private fun resolveDnsViaShell(): String? {
  val os = System.getProperty("os.name", "").lowercase()
  val ipPattern = Regex("""(\d+\.\d+\.\d+\.\d+)""")

  // On Windows, prefer `ipconfig /all` which reliably lists DNS servers.
  if (os.contains("win")) {
    try {
      val process = ProcessBuilder("cmd", "/c", "ipconfig", "/all")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()
      val dnsServers = mutableListOf<String>()
      var inDnsSection = false
      for (line in output.lines()) {
        val trimmed = line.trim()
        // "DNS Servers . . . . . . . . . . . : 192.168.1.1"
        if (trimmed.lowercase().contains("dns server")) {
          inDnsSection = true
          val match = ipPattern.find(trimmed)
          if (match != null) dnsServers += match.groupValues[1]
        } else if (inDnsSection && trimmed.startsWith("  ").not() && trimmed.contains(":")) {
          // New field — exit DNS section
          inDnsSection = false
        } else if (inDnsSection) {
          // Continuation line with another DNS IP
          val match = ipPattern.find(trimmed)
          if (match != null) dnsServers += match.groupValues[1]
        }
      }
      if (dnsServers.isNotEmpty()) return dnsServers.distinct().joinToString(", ")
    } catch (_: Exception) {}
  }

  // Fallback: nslookup (cross-platform)
  val commands: List<List<String>> = when {
    os.contains("win") -> listOf(listOf("cmd", "/c", "nslookup", "localhost"))
    else -> listOf(listOf("nslookup", "localhost"))
  }

  for (cmd in commands) {
    try {
      val process = ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()

      // nslookup prints "Server:  <ip>" as the first server line
      for (line in output.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("Server:", ignoreCase = true) ||
            trimmed.startsWith("Address:", ignoreCase = true)) {
          val match = ipPattern.find(trimmed)
          if (match != null) return match.groupValues[1]
        }
      }
    } catch (_: Exception) {
      // command not available
    }
  }
  LOGGER.debug("resolveDnsServers: no cross-platform method succeeded")
  return null
}
