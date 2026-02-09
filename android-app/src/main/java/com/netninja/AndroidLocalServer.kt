package com.netninja

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import com.netninja.cam.CameraDevice
import com.netninja.cam.OnvifDiscoveryService
import com.netninja.openclaw.OpenClawGatewayState
import com.netninja.openclaw.OpenClawNodeSnapshot
import com.netninja.openclaw.NodeSession
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.coroutines.coroutineContext
import android.database.sqlite.SQLiteDatabase

@Serializable data class Device(
  val id: String,
  val ip: String,
  val name: String? = null,
  val online: Boolean,
  val lastSeen: Long,
  val mac: String? = null,
  val hostname: String? = null,
  val vendor: String? = null,
  val os: String? = null,
  val owner: String? = null,
  val room: String? = null,
  val note: String? = null,
  val trust: String? = null,
  val type: String? = null,
  val status: String? = null,
  val via: String? = null,
  val signal: String? = null,
  val activityToday: String? = null,
  val traffic: String? = null,
  val openPorts: List<Int> = emptyList()
)

@Serializable data class DeviceEvent(val deviceId: String, val ts: Long, val event: String)
@Serializable data class ScanRequest(val subnet: String? = null, val timeoutMs: Int? = 300)
@Serializable data class ActionRequest(val ip: String? = null, val mac: String? = null, val url: String? = null)
@Serializable data class ScheduleRequest(val subnet: String? = null, val freq: String? = null)
@Serializable data class RuleRequest(val match: String? = null, val action: String? = null)
@Serializable data class RuleEntry(val match: String, val action: String)
@Serializable data class DeviceMetaUpdate(
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
@Serializable data class DeviceActionRequest(val action: String? = null)
@Serializable data class PortScanRequest(val ip: String? = null, val timeoutMs: Int? = null)
@Serializable data class PermissionSnapshot(
  val nearbyWifi: Boolean? = null,
  val fineLocation: Boolean = false,
  val coarseLocation: Boolean = false,
  val networkState: Boolean = false,
  val wifiState: Boolean = false,
  val permissionPermanentlyDenied: Boolean = false
)
@Serializable data class ScanPreconditions(
  val ready: Boolean,
  val blocker: String? = null,
  val reason: String? = null,
  val fixAction: String? = null,
  val androidVersion: Int = Build.VERSION.SDK_INT,
  val wifiEnabled: Boolean,
  val locationEnabled: Boolean,
  val permissions: PermissionSnapshot,
  val subnet: String? = null
)
@Serializable data class ScanProgress(
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
data class RouterInfo(
  val ok: Boolean,
  val gatewayIp: String? = null,
  val mac: String? = null,
  val vendor: String? = null,
  val openTcpPorts: List<Int> = emptyList(),
  val adminUrls: List<String> = emptyList(),
  val note: String? = null
)
@Serializable data class PermissionsActionRequest(val action: String? = null, val context: String? = null)

@Serializable
data class PermissionsActionResponse(
  val ok: Boolean,
  val message: String,
  val platform: String = "android",
  val details: Map<String, String?> = emptyMap()
)
@Serializable data class OpenClawStatus(val nodes: Int, val uptimeMs: Long)

@Serializable
data class OpenClawWsMessage(
  val type: String,
  val nodeId: String? = null,
  val capabilities: List<String> = emptyList(),
  val payload: String? = null
)

@Serializable
data class OpenClawGatewaySnapshot(
  val nodes: List<OpenClawNodeSnapshot>,
  val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class OpenClawStatsResponse(
  val uptimeMs: Long,
  val nodeCount: Int
)

@Serializable
data class MetricsResponse(
  val uptimeMs: Long,
  val memTotal: Long,
  val memUsed: Long,
  val devicesTotal: Int,
  val devicesOnline: Int,
  val lastScanAt: Long? = null
)

class AndroidLocalServer(private val ctx: Context) {

  private val db = LocalDatabase(ctx)

  private val devices = ConcurrentHashMap<String, Device>()
  private val deviceEvents = ConcurrentHashMap<String, MutableList<DeviceEvent>>()
  private val maxEventsPerDevice = 4000

  private val schedules = CopyOnWriteArrayList<String>()
  private val rules = CopyOnWriteArrayList<RuleEntry>()

  private val logs = ConcurrentLinkedQueue<String>()
  private val lastScanAt = AtomicReference<Long?>(null)
  private val lastScanRequestedAt = AtomicReference<Long?>(null)
  private val lastScanResults = AtomicReference<List<Device>>(emptyList())
  private val lastScanError = AtomicReference<String?>(null)
  private val scanProgress = AtomicReference(ScanProgress())
  private val scanCancel = AtomicBoolean(false)
  private val activeScanId = AtomicReference<String?>(null)
  private val scanScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var scanJob: Job? = null

  // Automation + resilience
  private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var schedulerJob: Job? = null
  private var schedulerPaused = false
  private val minScanIntervalMs = 60_000L

  private val watchdogScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var engine: ApplicationEngine? = null

  private val scanMutex = Mutex()

  // OpenClaw gateway: keep a set of active websocket sessions so we can broadcast snapshots on updates.
  private val openClawWsSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
  private val openClawJson = Json { ignoreUnknownKeys = true }

  // Test hooks (override network/environment behavior).
  internal var canAccessWifiDetailsOverride: (() -> Boolean)? = null
  internal var interfaceInfoOverride: (() -> InterfaceInfo?)? = null
  internal var arpTableOverride: (() -> List<Device>)? = null
  internal var reachabilityOverride: ((String, Int) -> Boolean)? = null
  internal var hostnameOverride: ((String) -> String?)? = null
  internal var ipListOverride: ((String) -> List<String>)? = null
  internal var vendorLookupOverride: ((String?) -> String?)? = null
  internal var portScanOverride: ((String, Int) -> List<Int>)? = null
  internal var localNetworkInfoOverride: (() -> Map<String, Any?>)? = null
  internal var wifiEnabledOverride: (() -> Boolean)? = null
  internal var locationEnabledOverride: (() -> Boolean)? = null
  internal var permissionSnapshotOverride: (() -> PermissionSnapshot)? = null
  internal var onvifDiscoverOverride: (suspend () -> List<CameraDevice>)? = null

  internal fun scheduleScanForTest(subnet: String) {
    scheduleScan(subnet, reason = "test")
  }

  internal suspend fun runScanForTest(subnet: String) {
    performScan(subnet)
  }

  internal fun scanProgressForTest(): ScanProgress = scanProgress.get()

  internal fun devicesForTest(): List<Device> = devices.values.toList()

  internal fun scanPreconditionsForTest(subnet: String? = null): ScanPreconditions =
    scanPreconditions(subnet)

  fun start(host: String = "127.0.0.1", port: Int = 8787) {
    // UI assets -> internal storage (atomic copy)
    val uiDir = File(ctx.filesDir, "web-ui").apply { mkdirs() }
    AssetCopier.copyDir(ctx, "web-ui", uiDir)

    verifyDatabase()
    loadPersistedState()

    startScheduler()

    // Watchdog: restart engine on crash with backoff
    watchdogScope.launch {
      while (isActive) {
        try {
          engine = embeddedServer(CIO, host = host, port = port) {
            install(ContentNegotiation) { json() }
            install(WebSockets) {
              pingPeriod = Duration.ofSeconds(15)
              timeout = Duration.ofSeconds(30)
              maxFrameSize = 1_048_576
            }

            // Localhost-only (permit file-scheme origins used by WebView bootstrap)
            install(CORS) {
              anyHost()
              allowMethod(HttpMethod.Get)
              allowMethod(HttpMethod.Post)
              allowHeader(HttpHeaders.ContentType)
              allowOrigins { origin ->
                origin == "null" ||
                  origin.startsWith("file://") ||
                  origin.startsWith("http://127.0.0.1") ||
                  origin.startsWith("http://localhost")
              }
            }

            setupRoutes(uiDir)
          }
          engine?.start(wait = true)
        } catch (t: Throwable) {
          log("ENGINE CRASH: ${t.message}")
          delay(3000)
        }
      }
    }

    log("engine boot requested on $host:$port")
  }

  fun stop() {
    runCatching { schedulerJob?.cancel() }
    runCatching { schedulerScope.cancel() }
    runCatching { scanJob?.cancel() }
    runCatching { scanScope.cancel() }
    runCatching { watchdogScope.cancel() }
    runCatching { engine?.stop(500, 1000) }
  }

  private suspend fun broadcastOpenClawSnapshot() {
    val snapshot = OpenClawGatewaySnapshot(nodes = OpenClawGatewayState.listNodes())
    val payload = openClawJson.encodeToString(snapshot)
    openClawWsSessions.values.forEach { session ->
      runCatching { session.send(payload) }
    }
  }

  private fun Application.setupRoutes(uiDir: File) {
    routing {
      staticFiles("/ui", uiDir, index = "ninja_mobile_new.html")
      get("/") { call.respondRedirect("/ui/ninja_mobile_new.html") }

      // Public probe
      get("/api/v1/system/info") {
        @Serializable
        data class SystemInfo(val platform: String, val timeMs: Long)
        call.respond(SystemInfo(platform = "android", timeMs = System.currentTimeMillis()))
      }

      // --- AUTH REMOVED (LOCAL-ONLY UI) ---
      get("/api/v1/network/info") {
        call.respond(localNetworkInfo())
      }

      get("/api/v1/router/info") {
        val net = localNetworkInfo()
        val gatewayIp = net["gateway"]?.toString()?.trim()
          ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
        if (gatewayIp == null) {
          call.respond(RouterInfo(ok = false, gatewayIp = null, note = "Gateway unavailable"))
          return@get
        }

        val routerMac = runCatching { readArpTable().firstOrNull { it.ip == gatewayIp }?.mac }.getOrNull()
        val vendor = lookupVendor(routerMac)
        val ports = withContext(Dispatchers.IO) {
          scanTcpPorts(
            gatewayIp,
            timeoutMs = 250,
            ports = listOf(80, 443, 8080, 8443, 7547, 22, 23, 53)
          )
        }
        val urls = buildList {
          if (ports.contains(443) || ports.contains(8443)) add("https://$gatewayIp")
          if (ports.contains(80) || ports.contains(8080)) add("http://$gatewayIp")
        }

        call.respond(
          RouterInfo(
            ok = true,
            gatewayIp = gatewayIp,
            mac = routerMac,
            vendor = vendor,
            openTcpPorts = ports.sorted(),
            adminUrls = urls
          )
        )
      }

      get("/api/v1/discovery/preconditions") {
        call.respond(scanPreconditions())
      }

      post("/api/v1/discovery/scan") {
        val req = runCatching { call.receive<ScanRequest>() }.getOrNull() ?: ScanRequest()
        val subnet = req.subnet?.trim().takeUnless { it.isNullOrBlank() } ?: deriveSubnetCidr()
        val preconditions = scanPreconditions(subnet)
        if (!preconditions.ready) {
          val msg = preconditions.reason ?: "scan blocked: prerequisites not met"
          logEvent(
            "SCAN_START_BLOCKED",
            mapOf(
              "reason" to preconditions.blocker,
              "androidVersion" to Build.VERSION.SDK_INT,
              "subnet" to subnet
            )
          )
          updateScanProgress("PRECONDITION_BLOCKED", message = msg)
          call.respond(cachedResults())
          return@post
        }
        if (subnet == null) {
          val msg = "scan blocked: missing subnet (permission or network unavailable)"
          logEvent(
            "SCAN_START_BLOCKED",
            mapOf("reason" to "missing_subnet", "androidVersion" to Build.VERSION.SDK_INT)
          )
          updateScanProgress("PERMISSION_BLOCKED", message = msg)
          call.respond(cachedResults())
          return@post
        }
        scheduleScan(subnet, reason = "manual")
        call.respond(cachedResults())
      }

      get("/api/v1/discovery/results") {
        call.respond(cachedResults())
      }

      get("/api/v1/onvif/discover") {
        val devices = runCatching {
          onvifDiscoverOverride?.let { override ->
            withTimeoutOrNull(1500) { override() } ?: emptyList()
          } ?: run {
            // Keep the probe timeout below the endpoint timeout so blocked UDP reads cannot linger past the HTTP request.
            val service = OnvifDiscoveryService(ctx, timeoutMs = 1200)
            withTimeoutOrNull(1500) {
              withContext(Dispatchers.IO) { service.discover() }
            } ?: emptyList()
          }
        }.getOrDefault(emptyList())
        call.respond(devices)
      }

      // --- OpenClaw gateway (HTTP + WebSocket) ---
      // Back-compat aliases (legacy paths without /api prefix).
      get("/openclaw/status") {
        call.respond(OpenClawStatus(OpenClawGatewayState.nodeCount(), OpenClawGatewayState.uptimeMs()))
      }

      get("/openclaw/nodes") {
        call.respond(OpenClawGatewayState.listNodes())
      }

      // Desktop/server contract paths (used by UI bundle).
      get("/api/openclaw/nodes") {
        call.respond(OpenClawGatewayState.listNodes())
      }

      get("/api/openclaw/stats") {
        call.respond(OpenClawStatsResponse(uptimeMs = OpenClawGatewayState.uptimeMs(), nodeCount = OpenClawGatewayState.nodeCount()))
      }

      webSocket("/openclaw/ws") {
        var nodeId: String? = null
        try {
          for (frame in incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            val msg = runCatching { openClawJson.decodeFromString(OpenClawWsMessage.serializer(), text) }.getOrNull()
              ?: continue

            when (msg.type.trim().uppercase(java.util.Locale.ROOT)) {
              "HELLO" -> {
                val resolvedId = msg.nodeId?.trim().orEmpty()
                if (resolvedId.isBlank()) {
                  close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing nodeId"))
                  return@webSocket
                }
                nodeId = resolvedId
                openClawWsSessions[resolvedId] = this

                val session = NodeSession(resolvedId) { payload ->
                  runCatching { outgoing.trySend(Frame.Text(payload)) }
                  Unit
                }
                OpenClawGatewayState.register(resolvedId, msg.capabilities, session)
                broadcastOpenClawSnapshot()
              }

              "HEARTBEAT" -> {
                val resolvedId = nodeId ?: msg.nodeId?.trim()
                if (!resolvedId.isNullOrBlank()) {
                  OpenClawGatewayState.updateHeartbeat(resolvedId)
                  broadcastOpenClawSnapshot()
                }
              }

              "RESULT" -> {
                val resolvedId = nodeId ?: msg.nodeId?.trim()
                if (!resolvedId.isNullOrBlank()) {
                  OpenClawGatewayState.updateResult(resolvedId, msg.payload)
                  broadcastOpenClawSnapshot()
                }
              }
            }
          }
        } finally {
          val id = nodeId
          if (id != null) {
            openClawWsSessions.remove(id)
            broadcastOpenClawSnapshot()
          }
        }
      }

      get("/api/v1/discovery/progress") {
        call.respond(scanProgress.get())
      }

      post("/api/v1/discovery/stop") {
        scanCancel.set(true)
        scanJob?.cancel()
        val scanId = activeScanId.get()
        emitScanCancelled("user_stop")
        logEvent("scan_cancelled", mapOf("reason" to "user_stop", "scanId" to scanId))
        call.respond(mapOf("ok" to true))
      }

      get("/api/v1/devices/{id}") {
        val id = call.parameters["id"]?.lowercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val d = devices[id] ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(d)
      }

      get("/api/v1/devices/{id}/meta") {
        val id = call.parameters["id"]?.lowercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val d = devices[id] ?: return@get call.respond(HttpStatusCode.NotFound)
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
        val id = call.parameters["id"]?.lowercase() ?: return@put call.respond(HttpStatusCode.BadRequest)
        val patch = runCatching { call.receive<DeviceMetaUpdate>() }.getOrNull() ?: DeviceMetaUpdate()
        val d = devices[id] ?: return@put call.respond(HttpStatusCode.NotFound)
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
        devices[id] = updated
        saveDevice(updated)
        call.respond(updated)
      }

      post("/api/v1/devices/{id}/actions") {
        val id = call.parameters["id"]?.lowercase() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val req = runCatching { call.receive<DeviceActionRequest>() }.getOrNull() ?: DeviceActionRequest()
        val action = req.action?.trim()?.lowercase()
        if (action.isNullOrBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing action"))
          return@post
        }
        val d = devices[id] ?: return@post call.respond(HttpStatusCode.NotFound)
        val updated = applyDeviceAction(d, action) ?: return@post call.respond(HttpStatusCode.BadRequest)
        devices[id] = updated
        saveDevice(updated)
        recordDeviceEvent(id, "ACTION_${action.uppercase()}")
        log("device action $action id=$id")
        call.respond(updated)
      }

      get("/api/v1/devices/{id}/history") {
        val id = call.parameters["id"]?.lowercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(deviceEvents[id].orEmpty())
      }

      get("/api/v1/devices/{id}/uptime") {
        val id = call.parameters["id"]?.lowercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val hist = deviceEvents[id].orEmpty()
        call.respond(mapOf("uptimePct24h" to uptimePct(hist, 86_400_000L)))
      }

      get("/api/v1/export/devices") {
        call.respond(devices.values.toList())
      }

      // Schedules
      get("/api/v1/schedules") {
        call.respond(schedules.toList())
      }
      post("/api/v1/schedules") {
        val req = runCatching { call.receive<ScheduleRequest>() }.getOrNull() ?: ScheduleRequest()
        val subnet = req.subnet?.trim().orEmpty()
        val freq = req.freq?.trim().orEmpty()
        if (subnet.isBlank() || freq.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing subnet/freq"))
          return@post
        }
        val entry = "SCAN $subnet @ $freq"
        schedules += entry
        saveSchedule(entry)
        log("schedule stored: $entry")
        call.respond(mapOf("ok" to true))
      }
      post("/api/v1/schedules/pause") {
        schedulerPaused = !schedulerPaused
        call.respond(mapOf("ok" to true, "paused" to schedulerPaused))
      }

      // Rules
      get("/api/v1/rules") {
        call.respond(rules.toList())
      }
      post("/api/v1/rules") {
        val req = runCatching { call.receive<RuleRequest>() }.getOrNull() ?: RuleRequest()
        val match = req.match?.trim().orEmpty()
        val action = req.action?.trim().orEmpty()
        if (match.isBlank() || action.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing match/action"))
          return@post
        }
        val entry = RuleEntry(match, action)
        rules += entry
        saveRule(match, action)
        log("rule stored: $match -> $action")
        call.respond(mapOf("ok" to true))
      }

      // Actions
      post("/api/v1/actions/ping") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val start = System.currentTimeMillis()
        val ok = isLikelyReachable(ip, 350, retries = 2)
        val rtt = if (ok) (System.currentTimeMillis() - start) else null
        call.respond(mapOf("ok" to ok, "ip" to ip, "rttMs" to rtt))
      }

      post("/api/v1/actions/http") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val urlStr = req.url?.trim().orEmpty()
        if (urlStr.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing url"))

        val url = runCatching { URL(urlStr) }.getOrNull()
        if (url == null || (url.protocol != "http" && url.protocol != "https")) {
          return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "invalid url"))
        }
        val host = url.host.lowercase()
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") {
          return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "localhost target blocked"))
        }

        val status = runCatching {
          val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 800
            readTimeout = 800
            requestMethod = "HEAD"
            instanceFollowRedirects = false
          }
          conn.inputStream.use { }
          conn.responseCode
        }.getOrDefault(0)

        call.respond(mapOf("ok" to (status in 200..399), "status" to status))
      }

      post("/api/v1/actions/portscan") {
        val req = runCatching { call.receive<PortScanRequest>() }.getOrNull() ?: PortScanRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val timeout = req.timeoutMs ?: 250
        val openPorts = scanPorts(ip, timeout)
        call.respond(mapOf("ok" to true, "ip" to ip, "openPorts" to openPorts))
      }

      post("/api/v1/actions/wol") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val mac = req.mac?.trim()
        if (mac.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing mac"))
        val ok = runCatching { sendMagicPacket(mac) }.isSuccess
        call.respond(mapOf("ok" to ok, "mac" to mac))
      }

      post("/api/v1/actions/snmp") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val ok = runCatching { scanPorts(ip, 350).contains(161) }.getOrDefault(false)
        call.respond(mapOf("ok" to ok, "ip" to ip))
      }

      post("/api/v1/actions/ssh") {
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val ok = runCatching { scanPorts(ip, 350).contains(22) }.getOrDefault(false)
        call.respond(mapOf("ok" to ok, "ip" to ip, "note" to "SSH port probe only"))
      }

      post("/api/v1/actions/security") {
        val all = devices.values.toList()
        val unknown = all.count { it.trust == null || it.trust == "Unknown" }
        val blocked = all.count { it.status == "Blocked" }
        call.respond(
          mapOf(
            "ok" to true,
            "devicesTotal" to all.size,
            "unknownDevices" to unknown,
            "blockedDevices" to blocked,
            "recommendations" to listOf(
              "Review unknown devices",
              "Close unused ports",
              "Enable automatic blocking for new devices"
            )
          )
        )
      }

      get("/api/v1/metrics") {
        val memTotal = Runtime.getRuntime().totalMemory()
        val memFree = Runtime.getRuntime().freeMemory()
        val memUsed = memTotal - memFree
        val all = devices.values.toList()
        call.respond(
          MetricsResponse(
            // Robolectric/host JVM environments may not fully implement Android clock APIs.
            uptimeMs = runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000L },
            memTotal = memTotal,
            memUsed = memUsed,
            devicesTotal = all.size,
            devicesOnline = all.count { it.online },
            lastScanAt = lastScanAt.get()
          )
        )
      }

      get("/api/v1/system/permissions") {
        call.respond(permissionSummary())
      }

      post("/api/v1/system/permissions/action") {
        val req = runCatching { call.receive<PermissionsActionRequest>() }.getOrNull()
        val resp = runCatching { handlePermissionAction(req?.action, req?.context) }
          .getOrElse { e ->
            PermissionsActionResponse(
              ok = false,
              message = "Failed to perform permission action.",
              details = mapOf("error" to e.message)
            )
          }
        call.respond(resp)
      }

      get("/api/v1/system/state") {
        call.respond(
          mapOf(
            "scanInProgress" to (scanJob?.isActive == true),
            "lastScanAt" to lastScanAt.get(),
            "lastScanRequestedAt" to lastScanRequestedAt.get(),
            "lastScanError" to lastScanError.get(),
            "cachedResults" to cachedResults().size,
            "rateLimitMs" to minScanIntervalMs,
            "permissions" to permissionSummary()
          )
        )
      }

      get("/api/v1/logs/stream") {
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
          while (coroutineContext.isActive) {
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
  }

  // ----------------- Scheduler + automation -----------------

  private fun startScheduler() {
    schedulerJob?.cancel()
    schedulerJob = schedulerScope.launch {
      while (isActive) {
        try {
          if (!schedulerPaused && schedules.isNotEmpty()) {
            runScheduledScans()
          }
        } catch (t: Throwable) {
          log("SCHEDULER ERROR: ${t.message}")
        }
        delay(30_000L)
      }
    }
  }

  private suspend fun runScheduledScans() {
    val now = System.currentTimeMillis()
    val last = lastScanAt.get() ?: 0L
    if (now - last < minScanIntervalMs) return

    for (entry in schedules) {
      val parts = entry.split("@")
      if (parts.size < 2) continue
      val subnet = parts[0].removePrefix("SCAN").trim()
      if (subnet.isBlank()) continue
      logEvent("scan_scheduled", mapOf("subnet" to subnet))
      scheduleScan(subnet, reason = "scheduled")
    }
  }

  private suspend fun performScan(subnet: String) {
    // Prevent overlapping scans
    if (!scanMutex.tryLock()) {
      logEvent("scan_skipped", mapOf("reason" to "already_running"))
      return
    }
    try {
      val scanId = ensureActiveScanId()
      scanCancel.set(false)
      lastScanError.set(null)
      val timeout = 300
      val ips = cidrToIps(subnet).take(4096)
      val sem = Semaphore(48)
      val total = ips.size.coerceAtLeast(1)
      val completed = java.util.concurrent.atomic.AtomicInteger(0)
      val foundCount = java.util.concurrent.atomic.AtomicInteger(0)

      val netInfo = localNetworkInfo()
      fun setProgress(progress: Int, phase: String, devices: Int) {
        scanProgress.set(
          ScanProgress(
            progress = progress.coerceIn(0, 100),
            phase = phase,
            networks = 1,
            devices = devices,
            rssiDbm = null,
            ssid = netInfo["name"]?.toString(),
            bssid = null,
            subnet = subnet,
            gateway = netInfo["gateway"]?.toString(),
            linkUp = netInfo["linkUp"] as? Boolean ?: true,
            updatedAt = System.currentTimeMillis()
          )
        )
      }
      setProgress(0, "DISCOVERY", 0)
      logEvent(
        "SCAN_FOREGROUND_SERVICE_STARTED",
        mapOf(
          "scanId" to scanId,
          "service" to "EngineService"
        )
      )
      logEvent(
        "scan_start",
        mapOf(
          "scanId" to scanId,
          "subnet" to subnet,
          "targets" to ips.size,
          "timeoutMs" to timeout
        )
      )

      warmUpArp(ips, timeout, netInfo, subnet)
      val arp = readArpTable()

      coroutineScope {
        ips.map { ip ->
          async(Dispatchers.IO) {
            sem.withPermit {
              if (scanCancel.get()) return@withPermit
              val arpDev = arp.firstOrNull { it.ip == ip }
              val mac = arpDev?.mac
              val reachable = isLikelyReachable(ip, timeout, retries = 2) || mac != null

              if (!reachable && mac == null) return@withPermit

              val id = (mac ?: ip).lowercase()
              val now = System.currentTimeMillis()

              val prev = devices[id]
              val hostname = if (reachable) resolveHostname(ip) else null
              val vendor = lookupVendor(mac)
              val os = guessOs(ip, timeout, hostname, vendor)
              val type = prev?.type ?: guessDeviceType(os, vendor, hostname)
              val via = prev?.via ?: netInfo["iface"]?.toString() ?: netInfo["name"]?.toString()
              val trust = prev?.trust ?: "Unknown"
              val status = prev?.status ?: if (reachable) "Online" else "Offline"
              val newDev = Device(
                id = id,
                ip = ip,
                name = prev?.name ?: hostname ?: ip,
                online = reachable,
                lastSeen = now,
                mac = mac?.lowercase(),
                hostname = hostname,
                vendor = vendor,
                os = os,
                owner = prev?.owner,
                room = prev?.room,
                note = prev?.note,
                trust = trust,
                type = type,
                status = status,
                via = via,
                signal = prev?.signal,
                activityToday = prev?.activityToday,
                traffic = prev?.traffic,
                openPorts = openPorts
              )

              val old = devices.put(id, newDev)
              saveDevice(newDev)
              emitEvents(old, newDev)
              evaluateRules(old, newDev)

              if (newDev.online) {
                foundCount.incrementAndGet()
              }

              val done = completed.incrementAndGet()
              val pct = ((done.toDouble() / total.toDouble()) * 90.0).toInt().coerceIn(0, 90)
              setProgress(pct + 10, if (pct >= 90) "COMPLETE" else "SCANNING", foundCount.get())
            }
          }
        }.awaitAll()
      }

      lastScanAt.set(System.currentTimeMillis())
      if (scanCancel.get()) {
        scanProgress.set(
          scanProgress.get().copy(
            phase = "CANCELLED",
            updatedAt = System.currentTimeMillis()
          )
        )
        emitScanCancelled("cancel_flag", mapOf("subnet" to subnet))
        return
      }
      scanProgress.set(
        scanProgress.get().copy(
          progress = 100,
          phase = "COMPLETE",
          devices = foundCount.get(),
          updatedAt = System.currentTimeMillis()
        )
      )
      lastScanResults.set(devices.values.toList())
      logEvent("scan_complete", mapOf("devices" to devices.size, "subnet" to subnet, "scanId" to scanId))
      emitScanCompleted(mapOf("devices" to devices.size, "subnet" to subnet))
    } catch (t: Throwable) {
      lastScanError.set(t.message)
      logEvent("scan_failed", mapOf("error" to t.message, "subnet" to subnet))
    } finally {
      scanMutex.unlock()
    }
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

  private fun evaluateRules(old: Device?, now: Device) {
    val wentOnline = old?.online == false && now.online
    val wentOffline = old?.online == true && !now.online
    val isNew = old == null

    for (rule in rules) {
      when (rule.match.lowercase()) {
        "device_online" -> if (wentOnline) executeRule(rule, now)
        "device_offline" -> if (wentOffline) executeRule(rule, now)
        "new_device" -> if (isNew) executeRule(rule, now)
      }
    }
  }

  private fun executeRule(rule: RuleEntry, device: Device) {
    log("rule fired: ${rule.match} -> ${rule.action} (${device.id})")
    when (rule.action.lowercase()) {
      "log" -> log("rule log: ${device.id}")
      "wol" -> device.mac?.let { runCatching { sendMagicPacket(it) } }
      "block" -> {
        val updated = device.copy(trust = "Blocked", status = "Blocked")
        devices[device.id] = updated
        saveDevice(updated)
        log("device blocked by rule: ${device.id}")
      }
      else -> log("unknown rule action: ${rule.action}")
    }
  }

  // ----------------- Event history + uptime -----------------

  private fun emitEvents(old: Device?, now: Device) {
    val id = now.id.lowercase()
    fun add(event: String) {
      recordDeviceEvent(id, event)
    }

    if (old == null) {
      add("NEW_DEVICE")
      if (now.online) add("DEVICE_ONLINE")
      return
    }
    if (old.online != now.online) add(if (now.online) "DEVICE_ONLINE" else "DEVICE_OFFLINE")
    if (old.ip != now.ip) add("IP_CHANGED")
  }

  private fun recordDeviceEvent(id: String, event: String) {
    val list = deviceEvents.getOrPut(id) { mutableListOf() }
    val e = DeviceEvent(deviceId = id, ts = System.currentTimeMillis(), event = event)
    list.add(e)
    saveEvent(e)
    if (list.size > maxEventsPerDevice) {
      list.subList(0, list.size - maxEventsPerDevice).clear()
    }
  }

  private fun uptimePct(events: List<DeviceEvent>, windowMs: Long, nowMs: Long = System.currentTimeMillis()): Double {
    if (windowMs <= 0L || events.isEmpty()) return 0.0
    val windowStart = nowMs - windowMs
    val sorted = events.sortedBy { it.ts }

    var onlineAtStart = false
    for (e in sorted) {
      if (e.ts >= windowStart) break
      when (e.event) {
        "DEVICE_ONLINE" -> onlineAtStart = true
        "DEVICE_OFFLINE" -> onlineAtStart = false
      }
    }

    val inWindow = sorted.filter { it.ts in windowStart..nowMs }
      .filter { it.event == "DEVICE_ONLINE" || it.event == "DEVICE_OFFLINE" }

    var online = onlineAtStart
    var lastTs = windowStart
    var upMs = 0L

    for (e in inWindow) {
      val ts = e.ts.coerceIn(windowStart, nowMs)
      if (online) upMs += (ts - lastTs).coerceAtLeast(0L)
      online = (e.event == "DEVICE_ONLINE")
      lastTs = ts
    }
    if (online) upMs += (nowMs - lastTs).coerceAtLeast(0L)

    return ((upMs.toDouble() / windowMs.toDouble()) * 100.0).coerceIn(0.0, 100.0)
  }

  // ----------------- Persistence -----------------

  private fun verifyDatabase() {
    try {
      db.writableDatabase.rawQuery("PRAGMA integrity_check;", null).use { c ->
        if (c.moveToFirst() && c.getString(0) != "ok") {
          log("DB corruption detected, rebuilding")
          ctx.deleteDatabase("netninja.db")
        }
      }
    } catch (e: Exception) {
      log("DB failure, recreating: ${e.message}")
      ctx.deleteDatabase("netninja.db")
    }
  }

  private fun columnsFor(table: String): Set<String> {
    val cols = mutableSetOf<String>()
    runCatching {
      db.readableDatabase.rawQuery("PRAGMA table_info($table);", null).use { c ->
        val nameIdx = c.getColumnIndex("name").takeIf { it >= 0 } ?: 1
        while (c.moveToNext()) {
          cols += c.getString(nameIdx)
        }
      }
    }
    return cols
  }

  private val deviceTableColumns: Set<String> by lazy { columnsFor("devices") }

  private fun hasDeviceColumn(name: String): Boolean = deviceTableColumns.contains(name)

  private fun loadPersistedState() {
    val r = db.readableDatabase

    runCatching {
      val hasOpenPorts = hasDeviceColumn("openPorts")
      val sql = if (hasOpenPorts) {
        "SELECT id, ip, name, online, lastSeen, mac, hostname, vendor, os, owner, room, note, trust, type, status, via, signal, activityToday, traffic, openPorts FROM devices"
      } else {
        "SELECT id, ip, name, online, lastSeen, mac, hostname, vendor, os, owner, room, note, trust, type, status, via, signal, activityToday, traffic FROM devices"
      }
      r.rawQuery(sql, null).use { c ->
        while (c.moveToNext()) {
          val id = c.getString(0)
          val openPortsRaw = if (hasOpenPorts) c.getString(19) else null
          val openPorts = openPortsRaw
            ?.split(",")
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull() }
            ?: emptyList()
          devices[id] = Device(
            id = id,
            ip = c.getString(1),
            name = c.getString(2),
            online = c.getInt(3) == 1,
            lastSeen = c.getLong(4),
            mac = c.getString(5),
            hostname = c.getString(6),
            vendor = c.getString(7),
            os = c.getString(8),
            owner = c.getString(9),
            room = c.getString(10),
            note = c.getString(11),
            trust = c.getString(12),
            type = c.getString(13),
            status = c.getString(14),
            via = c.getString(15),
            signal = c.getString(16),
            activityToday = c.getString(17),
            traffic = c.getString(18),
            openPorts = openPorts
          )
        }
      }
    }

    runCatching {
      r.rawQuery("SELECT match, action FROM rules", null).use { c ->
        while (c.moveToNext()) {
          rules += RuleEntry(c.getString(0), c.getString(1))
        }
      }
    }

    runCatching {
      r.rawQuery("SELECT entry FROM schedules", null).use { c ->
        while (c.moveToNext()) {
          schedules += c.getString(0)
        }
      }
    }

    runCatching {
      r.rawQuery("SELECT deviceId, ts, event FROM events", null).use { c ->
        while (c.moveToNext()) {
          val id = c.getString(0)
          deviceEvents.getOrPut(id) { mutableListOf() }
            .add(DeviceEvent(id, c.getLong(1), c.getString(2)))
        }
      }
    }

    lastScanResults.set(devices.values.toList())
  }

  private fun saveDevice(d: Device) {
    val w = db.writableDatabase
    val cv = ContentValues().apply {
      put("id", d.id)
      put("ip", d.ip)
      put("name", d.name)
      put("online", if (d.online) 1 else 0)
      put("lastSeen", d.lastSeen)
      put("mac", d.mac)
      put("hostname", d.hostname)
      put("vendor", d.vendor)
      put("os", d.os)
      put("owner", d.owner)
      put("room", d.room)
      put("note", d.note)
      put("trust", d.trust)
      put("type", d.type)
      put("status", d.status)
      put("via", d.via)
      put("signal", d.signal)
      put("activityToday", d.activityToday)
      put("traffic", d.traffic)
      if (hasDeviceColumn("openPorts")) {
        put("openPorts", d.openPorts.joinToString(","))
      }
    }
    w.insertWithOnConflict("devices", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
  }

  private fun saveEvent(e: DeviceEvent) {
    val w = db.writableDatabase
    val cv = ContentValues().apply {
      put("deviceId", e.deviceId)
      put("ts", e.ts)
      put("event", e.event)
    }
    w.insert("events", null, cv)
  }

  private fun saveRule(match: String, action: String) {
    val w = db.writableDatabase
    val cv = ContentValues().apply {
      put("match", match)
      put("action", action)
    }
    w.insert("rules", null, cv)
  }

  private fun saveSchedule(entry: String) {
    val w = db.writableDatabase
    val cv = ContentValues().apply {
      put("entry", entry)
    }
    w.insert("schedules", null, cv)
  }

  private fun log(s: String) {
    val line = "${System.currentTimeMillis()}: $s"
    logs.add(line)
    saveLog(line)
  }

  private fun logEvent(event: String, fields: Map<String, Any?> = emptyMap()) {
    val payload = buildString {
      append("{\"event\":\"")
      append(event)
      append("\",\"ts\":")
      append(System.currentTimeMillis())
      for ((k, v) in fields) {
        append(",\"")
        append(k)
        append("\":")
        when (v) {
          null -> append("null")
          is Number, is Boolean -> append(v.toString())
          else -> append("\"").append(v.toString().replace("\"", "\\\"")).append("\"")
        }
      }
      append("}")
    }
    log(payload)
  }

  private fun generateScanId(): String {
    return "scan-${System.currentTimeMillis()}-${UUID.randomUUID()}"
  }

  private fun ensureActiveScanId(): String {
    val existing = activeScanId.get()
    if (!existing.isNullOrBlank()) return existing
    val newId = generateScanId()
    activeScanId.set(newId)
    return newId
  }

  private fun emitScanCancelled(reason: String, extra: Map<String, Any?> = emptyMap()) {
    val scanId = activeScanId.get() ?: return
    logEvent("SCAN_CANCELLED", mapOf("scanId" to scanId, "reason" to reason) + extra)
    activeScanId.set(null)
  }

  private fun emitScanCompleted(extra: Map<String, Any?> = emptyMap()) {
    val scanId = activeScanId.get() ?: return
    logEvent("SCAN_COMPLETED", mapOf("scanId" to scanId) + extra)
    activeScanId.set(null)
  }

  private fun saveLog(msg: String) {
    try {
      val w = db.writableDatabase
      val cv = ContentValues().apply {
        put("ts", System.currentTimeMillis())
        put("msg", msg)
      }
      w.insert("logs", null, cv)
    } catch (_: Exception) {
      // Logging must never crash the engine.
    }
  }

  // ----------------- Network helpers -----------------

  private fun deriveSubnetCidr(): String? {
    if (canAccessWifiDetails()) {
      val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      val dhcp = runCatching { wm.dhcpInfo }.getOrNull()
      val ip = dhcp?.let { Formatter.formatIpAddress(it.ipAddress) }
      val mask = dhcp?.let { Formatter.formatIpAddress(it.netmask) }
      if (!ip.isNullOrBlank() && !mask.isNullOrBlank() && ip != "0.0.0.0" && mask != "0.0.0.0") {
        val prefix = maskToPrefix(mask)
        val base = ipToInt(ip) and if (prefix == 0) 0 else -1 shl (32 - prefix)
        return "${intToIp(base)}/$prefix"
      }
    }
    return deriveSubnetFromInterfaces()
  }

  private fun localNetworkInfo(): Map<String, Any?> {
    localNetworkInfoOverride?.let { return it() }
    if (canAccessWifiDetails()) {
      val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      val dhcp = runCatching { wm.dhcpInfo }.getOrNull()
      val ip = dhcp?.let { Formatter.formatIpAddress(it.ipAddress) }
      val gw = dhcp?.let { Formatter.formatIpAddress(it.gateway) }
      val mask = dhcp?.let { Formatter.formatIpAddress(it.netmask) }
      val dns1 = dhcp?.let { Formatter.formatIpAddress(it.dns1) }
      val dns2 = dhcp?.let { Formatter.formatIpAddress(it.dns2) }
      val dns = listOfNotNull(dns1, dns2).filter { it != "0.0.0.0" }.joinToString(", ").ifBlank { null }
      val cidr = if (!ip.isNullOrBlank() && !mask.isNullOrBlank() && ip != "0.0.0.0" && mask != "0.0.0.0") {
        val prefix = maskToPrefix(mask)
        val base = ipToInt(ip) and if (prefix == 0) 0 else -1 shl (32 - prefix)
        "${intToIp(base)}/$prefix"
      } else {
        null
      }
      val linkUp = ip != null && ip != "0.0.0.0" && cidr != null
      if (linkUp) {
        return mapOf(
          "name" to "Wi-Fi",
          "ip" to ip,
          "cidr" to cidr,
          "gateway" to gw,
          "dns" to dns,
          "iface" to "Wi-Fi",
          "linkUp" to true
        )
      }
    }

    val iface = selectInterfaceInfo()
    if (iface == null) {
      return mapOf("name" to "Wi-Fi", "ip" to null, "cidr" to null, "gateway" to null, "linkUp" to false)
    }
    val base = ipToInt(iface.ip) and if (iface.prefix == 0) 0 else -1 shl (32 - iface.prefix)
    return mapOf(
      "name" to iface.name,
      "ip" to iface.ip,
      "cidr" to "${intToIp(base)}/${iface.prefix}",
      "gateway" to null,
      "dns" to null,
      "iface" to iface.name,
      "linkUp" to true
    )
  }

  private fun readArpTable(): List<Device> {
    arpTableOverride?.let { return it() }
    val arpFile = File("/proc/net/arp")
    if (!arpFile.exists() || !arpFile.canRead()) return emptyList()
    val lines = runCatching { arpFile.readLines() }.getOrDefault(emptyList())
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
      out += Device(
        id = mac.lowercase(),
        ip = ip,
        name = ip,
        online = online,
        lastSeen = System.currentTimeMillis(),
        mac = mac.lowercase()
      )
    }
    return out
  }

  private fun cidrToIps(cidr: String): List<String> {
    ipListOverride?.let { return it(cidr) }
    val parts = cidr.split("/")
    if (parts.size != 2) return emptyList()
    val baseIp = parts[0]
    val prefix = parts[1].toIntOrNull() ?: return emptyList()

    val base = ipToInt(baseIp)
    val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
    val network = base and mask

    val hostCount = (1L shl (32 - prefix)).coerceAtMost(1L shl 16).toInt()
    val out = ArrayList<String>(minOf(hostCount, 4096))

    for (i in 1 until hostCount - 1) {
      out += intToIp(network + i)
      if (out.size >= 4096) break
    }
    return out
  }

  private fun ipToInt(ip: String): Int {
    val parts = ip.split(".").map { it.toIntOrNull() ?: return 0 }
    if (parts.size != 4) return 0
    return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
  }

  private fun intToIp(value: Int): String =
    listOf((value ushr 24) and 0xff, (value ushr 16) and 0xff, (value ushr 8) and 0xff, value and 0xff).joinToString(".")

  private fun maskToPrefix(mask: String): Int {
    val parts = mask.split(".").map { it.toIntOrNull() ?: 0 }
    var count = 0
    for (p in parts) count += Integer.bitCount(p)
    return count.coerceIn(0, 32)
  }

  private fun scanPorts(ip: String, timeoutMs: Int): List<Int> {
    portScanOverride?.let { return it(ip, timeoutMs) }
    val ports = listOf(22, 80, 443, 445, 3389, 5555, 161)
    val open = ArrayList<Int>(4)
    val timeout = timeoutMs.coerceIn(80, 2_000)
    for (p in ports) {
      repeat(2) { attempt ->
        try {
          Socket().use { s ->
            s.connect(InetSocketAddress(ip, p), timeout)
            open += p
          }
          return@repeat
        } catch (_: Exception) {
          if (attempt == 0) Thread.sleep(25)
        }
      }
    }
    return open
  }

  private fun scanTcpPorts(ip: String, timeoutMs: Int, ports: List<Int>): List<Int> {
    val timeout = timeoutMs.coerceIn(80, 2_000)
    val open = ArrayList<Int>(4)
    for (p in ports.distinct()) {
      try {
        Socket().use { s ->
          s.connect(InetSocketAddress(ip, p), timeout)
          open += p
        }
      } catch (_: Exception) {
        // closed/unreachable
      }
    }
    return open
  }

  private fun isLikelyReachable(ip: String, timeoutMs: Int, retries: Int = 1): Boolean {
    reachabilityOverride?.let { return it(ip, timeoutMs) }
    val timeout = timeoutMs.coerceIn(80, 1_000)
    val probePorts = intArrayOf(80, 443, 22)
    repeat(retries.coerceAtLeast(1)) { attempt ->
      val pingOk = runCatching { InetAddress.getByName(ip).isReachable(timeout) }.getOrDefault(false)
      if (pingOk) return true
      for (p in probePorts) {
        try {
          Socket().use { s ->
            s.connect(InetSocketAddress(ip, p), timeout)
            return true
          }
        } catch (_: Exception) {
          // keep trying
        }
      }
      if (attempt < retries - 1) Thread.sleep(40L * (attempt + 1))
    }
    return false
  }

  private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

  private fun canAccessWifiDetails(): Boolean {
    canAccessWifiDetailsOverride?.let { return it() }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return hasPermission("android.permission.NEARBY_WIFI_DEVICES")
    }
    return hasPermission("android.permission.ACCESS_FINE_LOCATION") || hasPermission("android.permission.ACCESS_COARSE_LOCATION")
  }

  private fun permissionSnapshot(): PermissionSnapshot {
    permissionSnapshotOverride?.let { return it() }
    val nearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      hasPermission("android.permission.NEARBY_WIFI_DEVICES")
    } else {
      null
    }
    return PermissionSnapshot(
      nearbyWifi = nearbyWifi,
      fineLocation = hasPermission("android.permission.ACCESS_FINE_LOCATION"),
      coarseLocation = hasPermission("android.permission.ACCESS_COARSE_LOCATION"),
      networkState = hasPermission("android.permission.ACCESS_NETWORK_STATE"),
      wifiState = hasPermission("android.permission.ACCESS_WIFI_STATE"),
      permissionPermanentlyDenied = isPermissionPermanentlyDenied()
    )
  }

  private fun permissionSummary(): Map<String, Any?> {
    val snapshot = permissionSnapshot()
    return mapOf(
      "nearbyWifi" to snapshot.nearbyWifi,
      "fineLocation" to snapshot.fineLocation,
      "coarseLocation" to snapshot.coarseLocation,
      "networkState" to snapshot.networkState,
      "wifiState" to snapshot.wifiState,
      "permissionPermanentlyDenied" to snapshot.permissionPermanentlyDenied,
      "camera" to hasPermission(android.Manifest.permission.CAMERA),
      "mic" to hasPermission(android.Manifest.permission.RECORD_AUDIO),
      "notifications" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)
      } else {
        null
      }
    )
  }

  private fun isPermissionPermanentlyDenied(): Boolean {
    val prefs = ctx.getSharedPreferences("netninja_permissions", Context.MODE_PRIVATE)
    val keys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      listOf("android.permission.NEARBY_WIFI_DEVICES")
    } else {
      listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
    }
    return keys.any { key -> prefs.getBoolean("${key}_permanent_denied", false) }
  }

  private fun wifiEnabled(): Boolean {
    wifiEnabledOverride?.let { return it() }
    return runCatching {
      val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      wm.isWifiEnabled
    }.getOrDefault(false)
  }

  private fun locationServicesEnabled(): Boolean {
    locationEnabledOverride?.let { return it() }
    val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      locationManager.isLocationEnabled
    } else {
      locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
  }

  private fun scanPreconditions(subnet: String? = null): ScanPreconditions {
    val permissions = permissionSnapshot()
    val permissionOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissions.nearbyWifi == true
    } else {
      permissions.fineLocation || permissions.coarseLocation
    }
    if (!permissionOk) {
      val blocker = if (permissions.permissionPermanentlyDenied) "permission_permanently_denied" else "permission_missing"
      val reason = if (permissions.permissionPermanentlyDenied) {
        "Scan blocked: permission permanently denied. Open app settings to grant access."
      } else {
        "Scan blocked: Wi‑Fi scan permission missing."
      }
      return ScanPreconditions(
        ready = false,
        blocker = blocker,
        reason = reason,
        fixAction = "app_settings",
        wifiEnabled = wifiEnabled(),
        locationEnabled = locationServicesEnabled(),
        permissions = permissions,
        subnet = subnet
      )
    }
    if (!locationServicesEnabled()) {
      return ScanPreconditions(
        ready = false,
        blocker = "location_disabled",
        reason = "Scan blocked: Location services are disabled.",
        fixAction = "location_settings",
        wifiEnabled = wifiEnabled(),
        locationEnabled = false,
        permissions = permissions,
        subnet = subnet
      )
    }
    if (!wifiEnabled()) {
      return ScanPreconditions(
        ready = false,
        blocker = "wifi_disabled",
        reason = "Scan blocked: Wi‑Fi is disabled.",
        fixAction = "wifi_settings",
        wifiEnabled = false,
        locationEnabled = true,
        permissions = permissions,
        subnet = subnet
      )
    }
    if (subnet == null && selectInterfaceInfo() == null) {
      return ScanPreconditions(
        ready = false,
        blocker = "missing_subnet",
        reason = "Scan blocked: network unavailable.",
        fixAction = "wifi_settings",
        wifiEnabled = true,
        locationEnabled = true,
        permissions = permissions,
        subnet = null
      )
    }
    return ScanPreconditions(
      ready = true,
      wifiEnabled = wifiEnabled(),
      locationEnabled = locationServicesEnabled(),
      permissions = permissions,
      subnet = subnet
    )
  }

  private fun handlePermissionAction(actionRaw: String?, contextRaw: String?): PermissionsActionResponse {
    val action = actionRaw.normalizedPermissionAction()
    val context = contextRaw?.trim()?.uppercase(java.util.Locale.ROOT)

    if (action.isBlank()) {
      return PermissionsActionResponse(
        ok = false,
        message = "Missing 'action'.",
        details = mapOf("action" to null, "context" to context)
      )
    }

    // Back-compat for scan-precondition fix actions.
    val legacy = when (actionRaw?.trim()) {
      "app_settings", "location_settings", "wifi_settings" -> actionRaw.trim()
      else -> null
    }
    if (legacy != null) {
      val intent = when (legacy) {
        "app_settings" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.fromParts("package", ctx.packageName, null)
        }
        "location_settings" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        "wifi_settings" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        else -> null
      } ?: return PermissionsActionResponse(
        ok = false,
        message = "Unsupported permission action.",
        details = mapOf("action" to action, "context" to context)
      )
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      return runCatching {
        ctx.startActivity(intent)
        PermissionsActionResponse(ok = true, message = "Opened system settings.", details = mapOf("action" to legacy))
      }.getOrElse { e ->
        PermissionsActionResponse(ok = false, message = "Failed to open system settings.", details = mapOf("action" to legacy, "error" to e.message))
      }
    }

    val result = when (action) {
      "OPEN_SETTINGS" -> PermissionBridge.openAppSettings(ctx)
      "REQUEST_CAMERA" -> PermissionBridge.requestCamera()
      "REQUEST_MIC" -> PermissionBridge.requestMic()
      "REQUEST_NOTIFICATIONS" -> PermissionBridge.requestNotifications(ctx)

      // Allow server-style payloads: { "action": "OPEN_SETTINGS", "context": "CAMERA" } or { "action": "REQUEST", "context": "CAMERA" }
      "REQUEST" -> when (context) {
        "CAMERA" -> PermissionBridge.requestCamera()
        "MIC" -> PermissionBridge.requestMic()
        "NOTIFICATIONS" -> PermissionBridge.requestNotifications(ctx)
        else -> PermissionActionResult(ok = false, message = "Unsupported context '$contextRaw'.", status = "unsupported")
      }

      else -> PermissionActionResult(ok = false, message = "Unsupported action '$actionRaw'.", status = "unsupported")
    }

    return PermissionsActionResponse(
      ok = result.ok,
      message = result.message,
      details = mapOf(
        "action" to action,
        "context" to context,
        "status" to result.status,
        "error" to result.error
      )
    )
  }

  private fun cachedResults(): List<Device> {
    val current = devices.values.toList()
    return if (current.isNotEmpty()) current else lastScanResults.get()
  }

  private fun updateScanProgress(phase: String, message: String? = null) {
    val now = System.currentTimeMillis()
    val current = scanProgress.get()
    val nextProgress = if (phase == "PERMISSION_BLOCKED" || phase == "RATE_LIMITED") 0 else current.progress
    scanProgress.set(
      current.copy(
        progress = nextProgress,
        phase = phase,
        updatedAt = now
      )
    )
    if (message != null) {
      logEvent("scan_status", mapOf("phase" to phase, "message" to message))
    }
  }

  private fun scheduleScan(subnet: String, reason: String) {
    val preconditions = scanPreconditions(subnet)
    if (!preconditions.ready) {
      val msg = preconditions.reason ?: "scan blocked: prerequisites not met"
      logEvent(
        "SCAN_START_BLOCKED",
        mapOf(
          "reason" to preconditions.blocker,
          "androidVersion" to Build.VERSION.SDK_INT,
          "subnet" to subnet,
          "source" to reason
        )
      )
      updateScanProgress("PRECONDITION_BLOCKED", message = msg)
      return
    }
    val now = System.currentTimeMillis()
    val lastReq = lastScanRequestedAt.get() ?: 0L
    if (now - lastReq < minScanIntervalMs) {
      logEvent("scan_rate_limited", mapOf("sinceMs" to (now - lastReq), "subnet" to subnet))
      updateScanProgress("RATE_LIMITED", message = "Scan rate-limited to protect battery.")
      return
    }
    lastScanRequestedAt.set(now)
    if (scanJob?.isActive == true) {
      val previousScanId = activeScanId.get()
      scanCancel.set(true)
      scanJob?.cancel()
      emitScanCancelled("stale_job", mapOf("subnet" to subnet, "source" to reason))
      logEvent(
        "scan_cancelled",
        mapOf(
          "reason" to "stale_job",
          "subnet" to subnet,
          "source" to reason,
          "scanId" to previousScanId
        )
      )
    }
    val scanId = generateScanId()
    activeScanId.set(scanId)
    logEvent("scan_request", mapOf("subnet" to subnet, "scanId" to scanId, "source" to reason))
    scanJob = scanScope.launch {
      performScan(subnet)
    }
  }

  private fun resolveHostname(ip: String): String? =
    hostnameOverride?.invoke(ip) ?: runCatching {
      val host = InetAddress.getByName(ip).canonicalHostName
      if (host == ip) null else host
    }.getOrNull()

  private fun lookupVendor(mac: String?): String? {
    vendorLookupOverride?.let { return it(mac) }
    if (mac.isNullOrBlank() || mac.length < 8) return null
    val key = mac.uppercase().replace("-", ":").substring(0, 8)
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

  private fun guessDeviceType(os: String?, vendor: String?, hostname: String?): String? {
    val vend = vendor?.lowercase().orEmpty()
    val host = hostname?.lowercase().orEmpty()
    return when {
      vend.contains("raspberry") || host.contains("pi") -> "IoT"
      vend.contains("apple") || host.contains("iphone") || host.contains("ipad") -> "Mobile"
      vend.contains("samsung") || host.contains("android") -> "Mobile"
      vend.contains("microsoft") || os == "Windows" -> "PC"
      vend.contains("cisco") || vend.contains("netgear") || vend.contains("linksys") -> "Gateway"
      else -> os
    }
  }

  private fun sendMagicPacket(mac: String) {
    val normalized = mac.trim().replace("-", ":").lowercase()
    val macBytes = normalized.split(":").mapNotNull { if (it.length == 2) it.toIntOrNull(16)?.toByte() else null }
    if (macBytes.size != 6) return

    val packet = ByteArray(6 + 16 * 6)
    java.util.Arrays.fill(packet, 0, 6, 0xFF.toByte())
    for (i in 6 until packet.size) packet[i] = macBytes[(i - 6) % 6]

    val address = InetAddress.getByName("255.255.255.255")
    val dp = java.net.DatagramPacket(packet, packet.size, address, 9)
    java.net.DatagramSocket().use { s ->
      s.broadcast = true
      s.send(dp)
    }
  }

  internal data class InterfaceInfo(val name: String, val ip: String, val prefix: Int)

  private fun selectInterfaceInfo(): InterfaceInfo? {
    interfaceInfoOverride?.let { return it() }
    return runCatching {
      val candidates = NetworkInterface.getNetworkInterfaces().toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { iface ->
          iface.interfaceAddresses.asSequence()
            .filter { it.address is Inet4Address }
            .map { addr ->
              InterfaceInfo(
                name = iface.displayName ?: iface.name,
                ip = addr.address.hostAddress ?: return@map null,
                prefix = addr.networkPrefixLength.toInt()
              )
            }
            .filterNotNull()
        }
        .toList()

      val preferred = candidates.firstOrNull { info ->
        val name = info.name.lowercase()
        name.contains("wlan") || name.contains("wifi") || name.contains("wl") || name.contains("ap")
      }
      preferred ?: candidates.firstOrNull()
    }.getOrNull()
  }

  private fun deriveSubnetFromInterfaces(): String? {
    val iface = selectInterfaceInfo() ?: return null
    val base = ipToInt(iface.ip) and if (iface.prefix == 0) 0 else -1 shl (32 - iface.prefix)
    return "${intToIp(base)}/${iface.prefix}"
  }

  private suspend fun warmUpArp(ips: List<String>, timeoutMs: Int, netInfo: Map<String, Any?>, subnet: String) {
    if (ips.isEmpty()) return
    val payload = byteArrayOf(0x1)
    val total = ips.size
    val socket = runCatching { java.net.DatagramSocket() }.getOrNull() ?: return
    socket.use { s ->
      s.broadcast = true
      s.soTimeout = timeoutMs.coerceIn(80, 800)
      ips.forEachIndexed { idx, ip ->
        if (scanCancel.get()) return
        runCatching {
          val packet = java.net.DatagramPacket(payload, payload.size, InetAddress.getByName(ip), 9)
          s.send(packet)
        }
        if (idx % 64 == 0 || idx == total - 1) {
          val pct = ((idx + 1).toDouble() / total.toDouble() * 10.0).toInt().coerceIn(0, 10)
          scanProgress.set(
            ScanProgress(
              progress = pct,
              phase = "ARP_WARMUP",
              networks = 1,
              devices = 0,
              rssiDbm = null,
              ssid = netInfo["name"]?.toString(),
              bssid = null,
              subnet = subnet,
              gateway = netInfo["gateway"]?.toString(),
              linkUp = netInfo["linkUp"] as? Boolean ?: true,
              updatedAt = System.currentTimeMillis()
            )
          )
        }
      }
    }
  }
}
