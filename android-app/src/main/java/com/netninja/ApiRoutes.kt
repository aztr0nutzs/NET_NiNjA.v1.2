package com.netninja

import android.os.Build
import android.os.SystemClock
import com.netninja.cam.OnvifDiscoveryService
import com.netninja.gateway.g5ar.G5arApiImpl
import com.netninja.gateway.g5ar.G5arCapabilities
import com.netninja.gateway.g5ar.G5arCredentialStore
import com.netninja.gateway.g5ar.G5arSession
import com.netninja.gateway.g5ar.WifiApConfig
import com.netninja.openclaw.NodeSession
import com.netninja.openclaw.OpenClawDashboardState
import com.netninja.openclaw.OpenClawGatewayState
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URL
import kotlin.coroutines.coroutineContext

internal fun Application.installApiRoutes(server: AndroidLocalServer, uiDir: File) {
  with(server) {
    val g5arApi = G5arApiImpl()
    val g5arCredentials = G5arCredentialStore(ctx)
    var g5arSession: G5arSession? = null

    routing {
      staticFiles("/ui", uiDir, index = "ninja_mobile_new.html")
      get("/") { call.respondRedirect("/ui/ninja_mobile_new.html") }

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
        val gatewaySignal: com.netninja.gateway.g5ar.GatewaySignal? = null,
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
        var signal: com.netninja.gateway.g5ar.GatewaySignal? = null
        var clients: List<com.netninja.gateway.g5ar.ClientDevice> = emptyList()
        var cell: com.netninja.gateway.g5ar.CellTelemetry? = null
        var sim: com.netninja.gateway.g5ar.SimInfo? = null
        var wifi: WifiApConfig? = null

        val canInfo = runCatching { withContext(Dispatchers.IO) { g5arApi.getGatewayInfo(activeSession) } }.onSuccess { info = it }.isSuccess
        val canSignal = runCatching { withContext(Dispatchers.IO) { g5arApi.getGatewaySignal(activeSession) } }.onSuccess { signal = it }.isSuccess
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
              canViewGatewayInfo = (canInfo || canSignal),
              canViewClients = canClients,
              canViewCellTelemetry = canCell,
              canViewSimInfo = canSim,
              canViewWifiConfig = canWifiRead,
              canSetWifiConfig = canWifiRead,
              canReboot = (canInfo || canSignal)
            ),
            gatewayInfo = info,
            gatewaySignal = signal,
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

      get("/api/v1/onvif/discover") {
        val devices = catching("onvif:discover") {
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

      // ── OpenClaw Dashboard routes ──────────────────────────────────────

      get("/api/openclaw/dashboard") {
        call.respond(openClawDashboard.snapshot())
      }

      post("/api/openclaw/connect") {
        @Serializable data class Req(val host: String? = null)
        val req = call.receive<Req>()
        call.respond(openClawDashboard.connect(req.host))
      }

      post("/api/openclaw/disconnect") {
        call.respond(openClawDashboard.disconnect())
      }

      post("/api/openclaw/refresh") {
        call.respond(openClawDashboard.refresh())
      }

      post("/api/openclaw/panic") {
        call.respond(openClawDashboard.panic())
      }

      get("/api/openclaw/gateways") {
        call.respond(openClawDashboard.listGateways())
      }

      post("/api/openclaw/gateways/{key}/restart") {
        val key = call.parameters["key"]?.trim().orEmpty()
        val updated = openClawDashboard.restartGateway(key)
        if (updated == null) {
          call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown gateway"))
        } else {
          call.respond(updated)
        }
      }

      post("/api/openclaw/gateways/{key}/ping") {
        val key = call.parameters["key"]?.trim().orEmpty()
        val result = openClawDashboard.pingGateway(key)
        if (result == null) {
          call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown gateway"))
        } else {
          call.respond(result)
        }
      }

      get("/api/openclaw/instances") {
        call.respond(openClawDashboard.listInstances())
      }

      post("/api/openclaw/instances") {
        @Serializable data class Req(
          val name: String? = null,
          val profile: String? = null,
          val workspace: String? = null,
          val sandbox: Boolean? = null,
          val access: String? = null
        )
        val req = call.receive<Req>()
        val name = req.name?.trim().orEmpty()
        if (name.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Instance name required"))
          return@post
        }
        val instance = openClawDashboard.addInstance(name, req.profile, req.workspace, req.sandbox, req.access)
        if (instance == null) {
          call.respond(HttpStatusCode.Conflict, mapOf("error" to "Instance already exists"))
        } else {
          call.respond(instance)
        }
      }

      post("/api/openclaw/instances/{name}/select") {
        val name = call.parameters["name"]?.trim().orEmpty()
        val instance = openClawDashboard.selectInstance(name)
        if (instance == null) {
          call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown instance"))
        } else {
          call.respond(instance)
        }
      }

      post("/api/openclaw/instances/{name}/activate") {
        val name = call.parameters["name"]?.trim().orEmpty()
        val instance = openClawDashboard.activateInstance(name)
        if (instance == null) {
          call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown instance"))
        } else {
          call.respond(instance)
        }
      }

      post("/api/openclaw/instances/{name}/stop") {
        val name = call.parameters["name"]?.trim().orEmpty()
        val instance = openClawDashboard.stopInstance(name)
        if (instance == null) {
          call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown instance"))
        } else {
          call.respond(instance)
        }
      }

      get("/api/openclaw/sessions") {
        call.respond(openClawDashboard.listSessions())
      }

      post("/api/openclaw/sessions") {
        @Serializable data class Req(
          val type: String? = null,
          val target: String? = null,
          val payload: String? = null
        )
        val req = call.receive<Req>()
        val type = req.type?.trim().orEmpty().ifBlank { "session" }
        val target = req.target?.trim().orEmpty().ifBlank { "default" }
        call.respond(openClawDashboard.createSession(type, target, req.payload))
      }

      post("/api/openclaw/sessions/{id}/cancel") {
        val id = call.parameters["id"]?.trim().orEmpty()
        val session = openClawDashboard.cancelSession(id)
        if (session == null) {
          call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown session"))
        } else {
          call.respond(session)
        }
      }

      get("/api/openclaw/skills") {
        call.respond(openClawDashboard.listSkills())
      }

      post("/api/openclaw/skills/refresh") {
        call.respond(openClawDashboard.refreshSkills())
      }

      post("/api/openclaw/skills/invoke") {
        @Serializable data class Req(val name: String? = null)
        val req = call.receive<Req>()
        val name = req.name?.trim().orEmpty()
        if (name.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Skill name required"))
          return@post
        }
        val skill = openClawDashboard.invokeSkill(name, skillExecutor)
        if (skill == null) {
          call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown skill"))
        } else {
          call.respond(skill)
        }
      }

      get("/api/openclaw/mode") {
        call.respond(mapOf("mode" to openClawDashboard.snapshot().mode))
      }

      post("/api/openclaw/mode") {
        @Serializable data class Req(val mode: String? = null)
        val req = call.receive<Req>()
        val mode = req.mode?.trim().orEmpty().ifBlank { "safe" }
        call.respond(mapOf("mode" to openClawDashboard.setMode(mode)))
      }

      get("/api/openclaw/config") {
        call.respond(openClawDashboard.configSnapshot())
      }

      post("/api/openclaw/config") {
        @Serializable data class Req(
          val host: String? = null,
          val profile: String? = null,
          val workspace: String? = null
        )
        val req = call.receive<Req>()
        call.respond(openClawDashboard.updateConfig(req.host, req.profile, req.workspace))
      }

      get("/api/openclaw/debug") {
        call.respond(openClawDashboard.debugSnapshot())
      }

      post("/api/openclaw/debug/dump") {
        call.respond(openClawDashboard.debugSnapshot())
      }

      get("/api/openclaw/logs") {
        call.respond(openClawDashboard.listLogs())
      }

      post("/api/openclaw/logs/clear") {
        openClawDashboard.clearLogs()
        call.respond(mapOf("ok" to true))
      }

      get("/api/openclaw/messages") {
        call.respond(openClawDashboard.listMessages())
      }

      post("/api/openclaw/messages") {
        @Serializable data class Req(
          val gateway: String? = null,
          val channel: String? = null,
          val body: String? = null
        )
        val req = call.receive<Req>()
        val gateway = req.gateway?.trim().orEmpty()
        val channel = req.channel?.trim().orEmpty()
        val body = req.body?.trim().orEmpty()
        if (gateway.isBlank() || channel.isBlank() || body.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "gateway, channel, and body required"))
          return@post
        }
        call.respond(openClawDashboard.addMessage(gateway, channel, body))
      }

      post("/api/openclaw/command") {
        @Serializable data class Req(val command: String? = null)
        val req = call.receive<Req>()
        val command = req.command?.trim().orEmpty()
        if (command.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Command required"))
          return@post
        }
        call.respond(openClawDashboard.runCommand(command, server))
      }

      post("/api/openclaw/chat/send") {
        @Serializable data class Req(
          val gateway: String? = null,
          val channel: String? = null,
          val body: String? = null
        )
        val req = call.receive<Req>()
        val gw = req.gateway?.trim().orEmpty().ifBlank { "local" }
        val ch = req.channel?.trim().orEmpty().ifBlank { "general" }
        val body = req.body?.trim().orEmpty()
        if (body.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message body required"))
          return@post
        }
        call.respond(openClawDashboard.addMessage(gw, ch, body))
      }

      get("/api/openclaw/cron") {
        call.respond(openClawDashboard.listCronJobs())
      }

      post("/api/openclaw/cron") {
        @Serializable data class Req(
          val schedule: String? = null,
          val command: String? = null,
          val enabled: Boolean? = null
        )
        val req = call.receive<Req>()
        val schedule = req.schedule?.trim().orEmpty()
        val command = req.command?.trim().orEmpty()
        if (schedule.isBlank() || command.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "schedule and command required"))
          return@post
        }
        call.respond(openClawDashboard.addCronJob(schedule, command, req.enabled ?: true))
      }

      post("/api/openclaw/cron/{id}/toggle") {
        val id = call.parameters["id"]?.trim().orEmpty()
        val job = openClawDashboard.toggleCronJob(id)
        if (job == null) {
          call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown cron job"))
        } else {
          call.respond(job)
        }
      }

      delete("/api/openclaw/cron/{id}") {
        val id = call.parameters["id"]?.trim().orEmpty()
        if (openClawDashboard.removeCronJob(id)) {
          call.respond(mapOf("ok" to true))
        } else {
          call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown cron job"))
        }
      }

      // ── End OpenClaw Dashboard routes ──────────────────────────────────

      webSocket("/openclaw/ws") {
        var nodeId: String? = null
        var isObserver = false
        val observerKey = "obs_${System.nanoTime()}"
        var parseErrors = 0
        try {
          for (frame in incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            val msg = catchingOrNull("openclaw:parseMessage", fields = mapOf("payloadLen" to text.length)) {
              openClawJson.decodeFromString(OpenClawWsMessage.serializer(), text)
            }
            if (msg == null) {
              // Avoid log spam if a client sends garbage in a loop.
              parseErrors++
              if (parseErrors <= 3) {
                logEvent("openclaw_parse_failed", mapOf("payloadLen" to text.length, "count" to parseErrors))
              }
              continue
            }

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
                  catching("openclaw:outgoing.trySend", fields = mapOf("nodeId" to resolvedId)) {
                    outgoing.trySend(Frame.Text(payload))
                  }
                  Unit
                }
                OpenClawGatewayState.register(resolvedId, msg.capabilities, session)
                broadcastOpenClawSnapshot()
              }

              // Dashboard observer: no nodeId required, just receives broadcast snapshots.
              "OBSERVE" -> {
                isObserver = true
                openClawWsSessions[observerKey] = this
                // Send an immediate snapshot so the dashboard has data right away.
                val snap = openClawJson.encodeToString(
                  OpenClawGatewaySnapshot.serializer(),
                  OpenClawGatewaySnapshot(
                    nodes = OpenClawGatewayState.listNodes(),
                    uptimeMs = OpenClawGatewayState.uptimeMs()
                  )
                )
                outgoing.trySend(Frame.Text(snap))
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
          if (isObserver) {
            openClawWsSessions.remove(observerKey)
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

