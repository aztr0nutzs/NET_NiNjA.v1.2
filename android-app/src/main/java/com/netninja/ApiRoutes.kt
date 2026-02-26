@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.netninja

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.netninja.gateway.g5ar.G5arApiImpl
import com.netninja.network.SpeedTestEngine
import com.netninja.gateway.g5ar.G5arCapabilities
import com.netninja.gateway.g5ar.G5arCredentialStore
import com.netninja.gateway.g5ar.G5arSession
import com.netninja.gateway.g5ar.WifiApConfig
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

@Serializable
private data class SpeedtestServer(
  val id: String,
  val name: String,
  val location: String
)

@Serializable
private data class SpeedtestConfig(
  val serverId: String = "neo-1",
  val realMode: Boolean = true,
  val precisionRange: Boolean = false
)

@Serializable
private data class SpeedtestConfigPatch(
  val serverId: String? = null,
  val realMode: Boolean? = null,
  val precisionRange: Boolean? = null
)

@Serializable
private data class SpeedtestControlRequest(
  val serverId: String? = null,
  val realMode: Boolean? = null,
  val precisionRange: Boolean? = null
)

@Serializable
private data class SpeedtestLiveState(
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
private data class SpeedtestResultRequest(
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

internal fun Application.installApiRoutes(server: AndroidLocalServer, uiDir: File) {
  with(server) {
    val g5arApi = G5arApiImpl()
    val g5arCredentials = G5arCredentialStore(ctx)
    var g5arSession: G5arSession? = null
    val speedtestConfig = AtomicReference(SpeedtestConfig())
    val speedtestLive = AtomicReference(SpeedtestLiveState())
    val speedtestAbort = AtomicBoolean(false)
    val speedtestEngine = SpeedTestEngine()
    val speedtestScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var speedtestJob: Job? = null
    val speedtestServers = listOf(
      SpeedtestServer(id = "neo-1", name = "NEO-1", location = "Downtown Relay"),
      SpeedtestServer(id = "arc-7", name = "ARC-7", location = "Subnet Spire"),
      SpeedtestServer(id = "hex-3", name = "HEX-3", location = "Industrial Backbone"),
      SpeedtestServer(id = "luna-9", name = "LUNA-9", location = "Edge Satellite")
    )
    routing {
      staticFiles("/ui", uiDir, index = "ninja_hud.html")
      get("/") { call.respondRedirect("/ui/ninja_hud.html") }

      // Public probe
      get("/api/v1/system/info") {
        @Serializable
        data class SystemInfo(val platform: String, val timeMs: Long)
        call.respond(SystemInfo(platform = "android", timeMs = System.currentTimeMillis()))
      }

      post("/api/v1/system/token/rotate") {
        val rotated = LocalApiAuth.rotate(ctx, graceMs = 5 * 60_000L)
        call.respond(mapOf("ok" to true, "token" to rotated.token, "previousValidUntilMs" to rotated.previousValidUntilMs))
      }

      // Auth is enforced globally for `/api/v1/*` via the shared-secret token interceptor.
      get("/api/v1/network/info") {
        call.respond(localNetworkInfo())
      }

      get("/api/v1/speedtest/servers") {
        call.respond(speedtestServers)
      }

      get("/api/v1/speedtest/config") {
        call.respond(speedtestConfig.get())
      }

      post("/api/v1/speedtest/config") {
        val req = catchingOrDefaultSuspend("speedtest:config.receive", SpeedtestConfigPatch()) { call.receive<SpeedtestConfigPatch>() }
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
        val req = catchingOrDefaultSuspend("speedtest:start.receive", SpeedtestControlRequest()) { call.receive<SpeedtestControlRequest>() }
        val current = speedtestConfig.get()
        val updatedConfig = current.copy(
          serverId = req.serverId?.trim()?.takeIf { it.isNotBlank() } ?: current.serverId,
          realMode = req.realMode ?: current.realMode,
          precisionRange = req.precisionRange ?: current.precisionRange
        )
        speedtestConfig.set(updatedConfig)
        speedtestAbort.set(false)

        // Cancel any in-flight engine run
        speedtestJob?.cancel()

        val live = speedtestLive.get().copy(
          running = true,
          phase = "PING",
          progress = 0,
          serverId = updatedConfig.serverId,
          error = null,
          updatedAt = System.currentTimeMillis()
        )
        speedtestLive.set(live)

        // When realMode is enabled, launch the OkHttp-based SpeedTestEngine
        if (updatedConfig.realMode) {
          speedtestJob = speedtestScope.launch {
            try {
              val result = speedtestEngine.execute(
                targets = SpeedTestEngine.Targets(),
                abortFlag = { speedtestAbort.get() },
                onProgress = SpeedTestEngine.ProgressCallback { phase, progress, pingMs, jitterMs, downloadMbps, uploadMbps ->
                  speedtestLive.set(
                    speedtestLive.get().copy(
                      running = true,
                      phase = phase,
                      progress = progress,
                      pingMs = pingMs ?: speedtestLive.get().pingMs,
                      jitterMs = jitterMs ?: speedtestLive.get().jitterMs,
                      downloadMbps = downloadMbps ?: speedtestLive.get().downloadMbps,
                      uploadMbps = uploadMbps ?: speedtestLive.get().uploadMbps,
                      updatedAt = System.currentTimeMillis()
                    )
                  )
                }
              )
              // Finalize live state with complete results
              speedtestLive.set(
                speedtestLive.get().copy(
                  running = false,
                  phase = "COMPLETE",
                  progress = 100,
                  pingMs = result.pingMs,
                  jitterMs = result.jitterMs,
                  downloadMbps = result.downloadMbps,
                  uploadMbps = result.uploadMbps,
                  lossPct = result.lossPct,
                  bufferbloat = result.bufferbloat,
                  error = null,
                  updatedAt = System.currentTimeMillis()
                )
              )
            } catch (e: SpeedTestEngine.SpeedTestAbortedException) {
              speedtestLive.set(
                speedtestLive.get().copy(
                  running = false,
                  phase = "ABORTED",
                  error = "aborted",
                  updatedAt = System.currentTimeMillis()
                )
              )
            } catch (e: Exception) {
              logEvent("error", mapOf("where" to "speedtest:engine", "message" to e.message))
              speedtestLive.set(
                speedtestLive.get().copy(
                  running = false,
                  phase = "ERROR",
                  error = e.message ?: "speedtest engine failed",
                  updatedAt = System.currentTimeMillis()
                )
              )
            }
          }
        }

        call.respond(mapOf("ok" to true, "config" to updatedConfig, "state" to live, "engineDriven" to updatedConfig.realMode))
      }

      post("/api/v1/speedtest/abort") {
        speedtestAbort.set(true)
        speedtestJob?.cancel()
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
        val req = catchingOrDefaultSuspend("speedtest:result.receive", SpeedtestResultRequest()) { call.receive<SpeedtestResultRequest>() }
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
        val prev = speedtestLive.get()
        speedtestLive.set(
          prev.copy(
            phase = if (prev.running) "PING" else prev.phase,
            updatedAt = now
          )
        )
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respond(mapOf("ok" to true, "serverTimeMs" to now))
      }

      get("/api/v1/speedtest/download") {
        val bytes = call.request.queryParameters["bytes"]?.toLongOrNull()?.coerceIn(1_024L, 250_000_000L) ?: 25_000_000L
        speedtestLive.set(
          speedtestLive.get().copy(
            running = true,
            phase = "DOWNLOAD",
            progress = 0,
            error = null,
            updatedAt = System.currentTimeMillis()
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
        val prev = speedtestLive.get()
        speedtestLive.set(
          prev.copy(
            running = false,
            phase = if (speedtestAbort.get()) "ABORTED" else "COMPLETE",
            progress = if (speedtestAbort.get()) prev.progress else 100,
            uploadMbps = mbps,
            updatedAt = System.currentTimeMillis()
          )
        )
        call.respond(mapOf("ok" to true, "bytesReceived" to total, "elapsedMs" to elapsedMs, "uploadMbps" to mbps))
      }

      get("/api/v1/router/info") {
        val net = localNetworkInfo()
        val gatewayIp = net["gateway"]?.toString()?.trim()
          ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
        if (gatewayIp == null) {
          call.respond(RouterInfo(ok = false, gatewayIp = null, note = "Gateway unavailable"))
          return@get
        }

        val routerMac = catchingOrNull("routerInfo:readArpTable", fields = mapOf("gatewayIp" to gatewayIp)) {
          readArpTable().firstOrNull { it.ip == gatewayIp }?.mac
        }
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

      @Serializable
      data class G5arLoginRequest(
        val username: String = "admin",
        val password: String,
        val remember: Boolean = false
      )

      @Serializable
      data class G5arScreenData(
        val reachable: Boolean,
        val loggedIn: Boolean,
        val capabilities: G5arCapabilities,
        val gatewayInfo: com.netninja.gateway.g5ar.GatewayInfo? = null,
        val clients: List<com.netninja.gateway.g5ar.ClientDevice> = emptyList(),
        val cellTelemetry: com.netninja.gateway.g5ar.CellTelemetry? = null,
        val simInfo: com.netninja.gateway.g5ar.SimInfo? = null,
        val wifiConfig: WifiApConfig? = null,
        val error: String? = null
      )

      get("/api/v1/g5ar/discover") {
        val reachable = withContext(Dispatchers.IO) { g5arApi.discover() }
        call.respond(mapOf("reachable" to reachable, "baseUrl" to G5arApiImpl.DEFAULT_BASE_URL))
      }

      post("/api/v1/g5ar/login") {
        val req = call.receive<G5arLoginRequest>()
        val user = req.username.trim().ifBlank { "admin" }
        if (req.password.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Password is required"))
          return@post
        }
        g5arSession = withContext(Dispatchers.IO) { g5arApi.login(user, req.password) }
        if (req.remember) {
          g5arCredentials.save(user, req.password)
        } else {
          g5arCredentials.clear()
        }
        call.respond(mapOf("ok" to true))
      }

      post("/api/v1/g5ar/logout") {
        g5arSession = null
        g5arCredentials.clear()
        call.respond(mapOf("ok" to true))
      }

      get("/api/v1/g5ar/screen") {
        val reachable = withContext(Dispatchers.IO) { g5arApi.discover() }
        if (!reachable) {
          call.respond(G5arScreenData(reachable = false, loggedIn = false, capabilities = G5arCapabilities(reachable = false), error = "Gateway unreachable"))
          return@get
        }

        val activeSession = g5arSession ?: g5arCredentials.load()?.let { (u, p) -> withContext(Dispatchers.IO) { g5arApi.login(u, p) } }?.also { g5arSession = it }
        if (activeSession == null) {
          call.respond(G5arScreenData(reachable = true, loggedIn = false, capabilities = G5arCapabilities(reachable = true), error = "Login required"))
          return@get
        }

        var info: com.netninja.gateway.g5ar.GatewayInfo? = null
        var clients: List<com.netninja.gateway.g5ar.ClientDevice> = emptyList()
        var cell: com.netninja.gateway.g5ar.CellTelemetry? = null
        var sim: com.netninja.gateway.g5ar.SimInfo? = null
        var wifi: WifiApConfig? = null

        val canInfo = runCatching { withContext(Dispatchers.IO) { g5arApi.getGatewayInfo(activeSession) } }.onSuccess { info = it }.isSuccess
        val canClients = runCatching { withContext(Dispatchers.IO) { g5arApi.getClients(activeSession) } }.onSuccess { clients = it }.isSuccess
        val canCell = runCatching { withContext(Dispatchers.IO) { g5arApi.getCellTelemetry(activeSession) } }.onSuccess { cell = it }.isSuccess
        val canSim = runCatching { withContext(Dispatchers.IO) { g5arApi.getSimInfo(activeSession) } }.onSuccess { sim = it }.isSuccess
        val canWifiRead = runCatching { withContext(Dispatchers.IO) { g5arApi.getWifiConfig(activeSession) } }.onSuccess { wifi = it }.isSuccess

        call.respond(
          G5arScreenData(
            reachable = true,
            loggedIn = true,
            capabilities = G5arCapabilities(
              reachable = true,
              canViewGatewayInfo = canInfo,
              canViewClients = canClients,
              canViewCellTelemetry = canCell,
              canViewSimInfo = canSim,
              canViewWifiConfig = canWifiRead,
              canSetWifiConfig = canWifiRead,
              canReboot = canInfo
            ),
            gatewayInfo = info,
            clients = clients,
            cellTelemetry = cell,
            simInfo = sim,
            wifiConfig = wifi
          )
        )
      }

      post("/api/v1/g5ar/wifi") {
        val activeSession = g5arSession
        if (activeSession == null) {
          call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Login required"))
          return@post
        }
        val req = call.receive<WifiApConfig>()
        val updated = withContext(Dispatchers.IO) { g5arApi.setWifiConfig(activeSession, req) }
        call.respond(updated)
      }

      post("/api/v1/g5ar/reboot") {
        val activeSession = g5arSession
        if (activeSession == null) {
          call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Login required"))
          return@post
        }
        withContext(Dispatchers.IO) { g5arApi.reboot(activeSession) }
        call.respond(mapOf("ok" to true))
      }

      get("/api/v1/discovery/preconditions") {
        call.respond(scanPreconditions())
      }

      post("/api/v1/discovery/scan") {
        val req = catchingOrDefaultSuspend("scan:receive", ScanRequest()) { call.receive<ScanRequest>() }
        val subnet = req.subnet?.trim().takeUnless { it.isNullOrBlank() } ?: deriveSubnetCidr()
        val preconditions = scanPreconditions(subnet)
        if (!preconditions.ready) {
          val msg = preconditions.reason ?: "scan blocked: prerequisites not met"
          lastScanError.set(msg)
          logEvent(
            "SCAN_START_BLOCKED",
            mapOf(
              "reason" to preconditions.blocker,
              "androidVersion" to Build.VERSION.SDK_INT,
              "subnet" to subnet
            )
          )
          updateScanProgress("PRECONDITION_BLOCKED", message = msg, fixAction = preconditions.fixAction)
          call.respond(cachedResults())
          return@post
        }
        if (subnet == null) {
          val msg = "scan blocked: missing subnet (permission or network unavailable)"
          lastScanError.set(msg)
          logEvent(
            "SCAN_START_BLOCKED",
            mapOf("reason" to "missing_subnet", "androidVersion" to Build.VERSION.SDK_INT)
          )
          updateScanProgress("PERMISSION_BLOCKED", message = msg, fixAction = preconditions.fixAction ?: "app_settings")
          call.respond(cachedResults())
          return@post
        }
        // Clear any previous blocker message once we accept a scan request.
        updateScanProgress("QUEUED", message = null, fixAction = null)
        scheduleScan(subnet, reason = "manual")
        call.respond(cachedResults())
      }

      get("/api/v1/discovery/results") {
        call.respond(cachedResults())
      }

      get("/api/v1/discovery/progress") {
        call.respond(scanProgress.get())
      }

      post("/api/v1/discovery/stop") {
        scanCancel.set(true)
        scanJob?.cancel()
        val scanId = activeScanId.get()
        if (!scanId.isNullOrBlank()) {
          emitScanCancelled(scanId, "user_stop")
        }
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
        val patch = catchingOrDefaultSuspend("deviceMeta:receive", DeviceMetaUpdate(), fields = mapOf("id" to id)) { call.receive<DeviceMetaUpdate>() }
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
        val req = catchingOrDefaultSuspend("deviceAction:receive", DeviceActionRequest(), fields = mapOf("id" to id)) { call.receive<DeviceActionRequest>() }
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
        val req = catchingOrDefaultSuspend("schedules:receive", ScheduleRequest()) { call.receive<ScheduleRequest>() }
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
        val req = catchingOrDefaultSuspend("rules:receive", RuleRequest()) { call.receive<RuleRequest>() }
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
        val req = catchingOrDefaultSuspend("action:ping.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val start = System.currentTimeMillis()
        val ok = isLikelyReachable(ip, 350, retries = 2)
        val rtt = if (ok) (System.currentTimeMillis() - start) else null
        call.respond(mapOf("ok" to ok, "ip" to ip, "rttMs" to rtt))
      }

      post("/api/v1/actions/http") {
        val req = catchingOrDefaultSuspend("action:http.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val urlStr = req.url?.trim().orEmpty()
        if (urlStr.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing url"))

        val url = catchingOrNull("action:http.parseUrl", fields = mapOf("url" to urlStr.take(256))) { URL(urlStr) }
        if (url == null || (url.protocol != "http" && url.protocol != "https")) {
          return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "invalid url"))
        }
        val host = url.host.lowercase()
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") {
          return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "localhost target blocked"))
        }

        val status = catching("action:http.head", fields = mapOf("host" to host)) {
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
        val req = catchingOrDefaultSuspend("action:portscan.receive", PortScanRequest()) { call.receive<PortScanRequest>() }
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val timeout = req.timeoutMs ?: 250
        val openPorts = scanPorts(ip, timeout)
        call.respond(mapOf("ok" to true, "ip" to ip, "openPorts" to openPorts))
      }

      post("/api/v1/actions/wol") {
        val req = catchingOrDefaultSuspend("action:wol.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val mac = req.mac?.trim()
        if (mac.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing mac"))
        val ok = catching("action:wol.send", fields = mapOf("mac" to mac)) { sendMagicPacket(mac) }.isSuccess
        call.respond(mapOf("ok" to ok, "mac" to mac))
      }

      post("/api/v1/actions/snmp") {
        val req = catchingOrDefaultSuspend("action:snmp.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val ok = catchingOrDefault("action:snmp.portProbe", false, fields = mapOf("ip" to ip)) { scanPorts(ip, 350).contains(161) }
        call.respond(mapOf("ok" to ok, "ip" to ip))
      }

      post("/api/v1/actions/ssh") {
        val req = catchingOrDefaultSuspend("action:ssh.receive", ActionRequest()) { call.receive<ActionRequest>() }
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val ok = catchingOrDefault("action:ssh.portProbe", false, fields = mapOf("ip" to ip)) { scanPorts(ip, 350).contains(22) }
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
            uptimeMs = catchingOrDefault("metrics:elapsedRealtime", System.nanoTime() / 1_000_000L) { SystemClock.elapsedRealtime() },
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
        val req = catchingOrNullSuspend("permissionsAction:receive") { call.receive<PermissionsActionRequest>() }
        val resp = catching("permissionsAction:handle") { handlePermissionAction(req?.action, req?.context) }
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
        val notice = dbNotice.get()
        call.respond(
          mapOf(
            "scanInProgress" to (scanJob?.isActive == true),
            "lastScanAt" to lastScanAt.get(),
            "lastScanRequestedAt" to lastScanRequestedAt.get(),
            "lastScanError" to lastScanError.get(),
            "cachedResults" to cachedResults().size,
            "rateLimitMs" to minScanIntervalMs(),
            "permissions" to permissionSummary(),
            "dbNotice" to notice?.let {
              mapOf(
                "atMs" to it.atMs,
                "level" to it.level,
                "action" to it.action,
                "message" to it.message,
                "backupPath" to it.backupPath
              )
            }
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
}



