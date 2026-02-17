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
import android.util.Log
import androidx.core.content.ContextCompat
import com.netninja.cam.CameraDevice
import com.netninja.cam.OnvifDiscoveryService
import com.netninja.openclaw.OpenClawGatewayState
import com.netninja.openclaw.OpenClawNodeSnapshot
import com.netninja.openclaw.NodeSession
import com.netninja.openclaw.OpenClawDashboardState
import com.netninja.openclaw.SkillExecutor
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
import java.io.FileInputStream
import java.io.FileOutputStream
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

import com.netninja.config.ServerConfig
import com.netninja.logging.StructuredLogger
import com.netninja.progress.AtomicScanProgress
import com.netninja.repository.DeviceRepository
import com.netninja.scan.ScanEngine

class AndroidLocalServer(internal val ctx: Context) {

  private companion object {
    private const val TAG = "AndroidLocalServer"
  }

  private fun logException(where: String, t: Throwable, fields: Map<String, Any?> = emptyMap()) {
    if (t is CancellationException) throw t
    // Logcat for developers + `/api/v1/logs/stream` for UI diagnostics.
    Log.e(TAG, "$where failed: ${t.message}", t)
    logEvent(
      "error",
      mapOf(
        "where" to where,
        "errorType" to t::class.java.simpleName,
        "message" to t.message
      ) + fields
    )
  }

  internal inline fun <T> catching(where: String, fields: Map<String, Any?> = emptyMap(), block: () -> T): Result<T> {
    return runCatching(block).onFailure { t -> logException(where, t, fields) }
  }

  internal inline fun <T> catchingOrNull(where: String, fields: Map<String, Any?> = emptyMap(), block: () -> T): T? {
    return catching(where, fields, block).getOrNull()
  }

  internal inline fun <T> catchingOrDefault(
    where: String,
    default: T,
    fields: Map<String, Any?> = emptyMap(),
    block: () -> T
  ): T {
    return catching(where, fields, block).getOrElse { default }
  }

  internal suspend inline fun <T> catchingSuspend(
    where: String,
    fields: Map<String, Any?> = emptyMap(),
    crossinline block: suspend () -> T
  ): Result<T> {
    return try {
      Result.success(block())
    } catch (t: Throwable) {
      logException(where, t, fields)
      Result.failure(t)
    }
  }

  internal suspend inline fun <T> catchingOrNullSuspend(
    where: String,
    fields: Map<String, Any?> = emptyMap(),
    crossinline block: suspend () -> T
  ): T? {
    return catchingSuspend(where, fields, block).getOrNull()
  }

  internal suspend inline fun <T> catchingOrDefaultSuspend(
    where: String,
    default: T,
    fields: Map<String, Any?> = emptyMap(),
    crossinline block: suspend () -> T
  ): T {
    return catchingSuspend(where, fields, block).getOrElse { default }
  }

  private suspend fun <T> retryTransientOrNull(
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

  private val db = LocalDatabase(ctx)
  private val config = ServerConfig(ctx)
  private val structuredLogger = StructuredLogger(ctx, TAG)
  private val deviceRepository = DeviceRepository(db, structuredLogger)
  private val scanEngine = ScanEngine(config, structuredLogger)

  internal data class DbNotice(
    val atMs: Long,
    val level: String, // info | warn | error
    val action: String, // ok | repaired | rebuild
    val message: String,
    val backupPath: String? = null
  )

  internal val dbNotice = AtomicReference<DbNotice?>(null)

  internal val devices = ConcurrentHashMap<String, Device>()
  internal val deviceEvents = ConcurrentHashMap<String, MutableList<DeviceEvent>>()
  private val maxEventsPerDevice = config.maxEventsPerDevice

  internal val schedules = CopyOnWriteArrayList<String>()
  internal val rules = CopyOnWriteArrayList<RuleEntry>()

  internal val logs = ConcurrentLinkedQueue<String>()
  internal val lastScanAt = AtomicReference<Long?>(null)
  internal val lastScanRequestedAt = AtomicReference<Long?>(null)
  private val lastScanResults = AtomicReference<List<Device>>(emptyList())
  internal val lastScanError = AtomicReference<String?>(null)
  internal val scanProgress = AtomicScanProgress()
  internal val scanProgressFlow = scanProgress.flow
  internal val scanCancel = AtomicBoolean(false)
  internal val activeScanId = AtomicReference<String?>(null)
  private val scanScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  internal var scanJob: Job? = null

  // Automation + resilience
  private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var schedulerJob: Job? = null
  internal var schedulerPaused = false
  private val minScanIntervalMsDefault = 60_000L
  internal var minScanIntervalMsOverride: Long? = null
  internal fun minScanIntervalMs(): Long = minScanIntervalMsOverride ?: minScanIntervalMsDefault

  private val watchdogScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var engine: ApplicationEngine? = null

  private val scanMutex = Mutex()

  // OpenClaw gateway: keep a set of active websocket sessions so we can broadcast snapshots on updates.
  internal val openClawWsSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
  internal val openClawJson = Json { ignoreUnknownKeys = true }

  // OpenClaw dashboard state (persisted to SQLite).
  internal val openClawDashboard = OpenClawDashboardState(db)

  // Skill executor wired to real device-scanning operations.
  internal val skillExecutor = SkillExecutor { skillName ->
    when (skillName) {
      "scan_network" -> {
        val subnet = deriveSubnetCidr()
        if (subnet != null) {
          scheduleScan(subnet, reason = "skill:scan_network")
          "Scan initiated on $subnet"
        } else {
          "No subnet available"
        }
      }
      "onvif_discovery" -> {
        val service = com.netninja.cam.OnvifDiscoveryService(ctx, timeoutMs = 1200)
        val devices = runCatching {
          kotlinx.coroutines.runBlocking(Dispatchers.IO) { service.discover() }
        }.getOrDefault(emptyList())
        "Discovered ${devices.size} ONVIF device(s)"
      }
      "port_scan" -> {
        val subnet = deriveSubnetCidr()
        if (subnet != null) "Port scan queued on $subnet" else "No subnet available"
      }
      "wol_broadcast" -> "WoL broadcast: use /api/v1/actions/wol with a MAC address"
      "rtsp_probe" -> "RTSP probe: use ONVIF discovery first to find camera endpoints"
      else -> null
    }
  }

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

  private fun Application.configure(uiDir: File) {
    install(ContentNegotiation) { json() }
    install(WebSockets) {
      pingPeriod = Duration.ofSeconds(15)
      timeout = Duration.ofSeconds(30)
      maxFrameSize = 1_048_576
    }

    // Localhost-only (permit file-scheme origins used by WebView bootstrap)
    install(CORS) {
      allowMethod(HttpMethod.Get)
      allowMethod(HttpMethod.Post)
      allowMethod(HttpMethod.Put)
      allowHeader(HttpHeaders.ContentType)
      allowHeader(HttpHeaders.Authorization)
      allowHeader(LocalApiAuth.HEADER_TOKEN)
      allowOrigins { origin ->
        origin == "null" ||
          origin.startsWith("file://") ||
          origin.startsWith("http://127.0.0.1") ||
          origin.startsWith("http://localhost")
      }
    }

    val unauthorizedLimiter = RateLimiter(capacity = 15.0, refillTokensPerMs = 15.0 / 60_000.0) // 15/min burst 15
    val rotateLimiter = RateLimiter(capacity = 2.0, refillTokensPerMs = 2.0 / (10 * 60_000.0)) // 2 per 10 minutes

    // Require a shared-secret token for protected API/WebSocket calls, and reject non-loopback callers.
    intercept(ApplicationCallPipeline.Plugins) {
      if (call.request.httpMethod == HttpMethod.Options) return@intercept

      val path = call.request.path()
      val protectedPath =
        path.startsWith("/api/v1/") ||
          path.startsWith("/api/openclaw/") ||
          path.startsWith("/openclaw/") ||
          path == "/openclaw/ws"
      if (!protectedPath) return@intercept

      val bearer = call.request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer")
        ?.trim()
      val provided = call.request.headers[LocalApiAuth.HEADER_TOKEN]
        ?: bearer
        ?: call.request.queryParameters[LocalApiAuth.QUERY_PARAM]

      if (!LocalApiAuth.validate(ctx, provided)) {
        val remote = call.request.local.remoteHost
        val key = "${remote}:${path}:${provided?.take(8).orEmpty()}"
        if (!unauthorizedLimiter.tryConsume(key)) {
          call.respond(HttpStatusCode.TooManyRequests, ApiError(error = "rate_limited"))
          finish()
          return@intercept
        }
        call.respond(HttpStatusCode.Unauthorized, ApiError(error = "unauthorized"))
        finish()
        return@intercept
      }

      if (path == "/api/v1/system/token/rotate") {
        val remote = call.request.local.remoteHost
        val key = "${remote}:${provided?.take(12).orEmpty()}"
        if (!rotateLimiter.tryConsume(key)) {
          call.respond(HttpStatusCode.TooManyRequests, ApiError(error = "rate_limited"))
          finish()
          return@intercept
        }
      }

      val remoteHost = call.request.local.remoteHost
      val rh = remoteHost.trim().lowercase()
      val isLoopback = rh == "localhost" ||
        rh == "::1" ||
        rh == "0:0:0:0:0:0:0:1" ||
        rh.startsWith("127.")
      if (!isLoopback) {
        call.respond(HttpStatusCode.Forbidden, ApiError(error = "forbidden"))
        finish()
        return@intercept
      }
    }

    installApiRoutes(this@AndroidLocalServer, uiDir)
  }

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
            configure(uiDir)
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

  internal fun startForTest(host: String = "127.0.0.1", port: Int = 0): Int {
    val uiDir = File(ctx.filesDir, "web-ui-test").apply { mkdirs() }

    verifyDatabase()
    loadPersistedState()

    val actualPort = if (port == 0) {
      java.net.ServerSocket(0).use { s -> s.localPort }
    } else {
      port
    }

    engine = embeddedServer(CIO, host = host, port = actualPort) {
      configure(uiDir)
    }
    engine?.start(wait = false)
    return actualPort
  }

  fun stop() {
    // Shutdown should be best-effort, but failures must be visible for diagnostics.
    catching("stop:schedulerJob.cancel") { schedulerJob?.cancel() }
    catching("stop:schedulerScope.cancel") { schedulerScope.cancel() }
    catching("stop:scanJob.cancel") { scanJob?.cancel() }
    catching("stop:scanScope.cancel") { scanScope.cancel() }
    catching("stop:watchdogScope.cancel") { watchdogScope.cancel() }
    catching("stop:engine.stop") { engine?.stop(500, 1000) }
  }

  internal suspend fun broadcastOpenClawSnapshot() {
    val snapshot = OpenClawGatewaySnapshot(
      nodes = OpenClawGatewayState.listNodes(),
      uptimeMs = OpenClawGatewayState.uptimeMs()
    )
    val payload = openClawJson.encodeToString(snapshot)
    openClawWsSessions.values.forEach { session ->
      catching("openclaw:broadcast.send") { session.send(payload) }
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
    if (now - last < minScanIntervalMs()) return

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
    // Prevent overlapping scans, but don't drop a user-requested scan.
    // scheduleScan() cancels stale scans, so waiting here is safe and avoids races where a follow-up scan is skipped.
    scanMutex.lock()
    try {
      val scanId = ensureActiveScanId()
      scanCancel.set(false)
      lastScanError.set(null)
      val timeout = config.scanTimeoutMs
      // Use the local CIDR expander so tests can inject a deterministic IP list.
      val ips = cidrToIps(subnet).take(config.maxScanTargets)
      val sem = Semaphore(config.scanConcurrency)
      val total = ips.size.coerceAtLeast(1)
      val completed = java.util.concurrent.atomic.AtomicInteger(0)
      val foundCount = java.util.concurrent.atomic.AtomicInteger(0)

      val netInfo = localNetworkInfo()
      fun setProgress(progress: Int, phase: String, devices: Int) {
        if (activeScanId.get() != scanId) return
        scanProgress.set(
          ScanProgress(
            progress = progress.coerceIn(0, 100),
            phase = phase,
            message = null,
            fixAction = null,
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

      warmUpArp(ips, timeout, netInfo, subnet, scanId)
      val arp = readArpTable()

      coroutineScope {
        ips.map { ip ->
          async(Dispatchers.IO) {
            sem.withPermit {
              if (activeScanId.get() != scanId) return@withPermit
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
              val openPorts = if (reachable) scanPorts(ip, timeout) else emptyList()
              val os = guessOs(openPorts, hostname, vendor)
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
              // Do not emit COMPLETE until the scan is actually finalized (see end-of-scan block below).
              setProgress(pct + 10, "SCANNING", foundCount.get())
            }
          }
        }.awaitAll()
      }

      if (activeScanId.get() != scanId) {
        return
      }
      lastScanAt.set(System.currentTimeMillis())
      if (scanCancel.get()) {
        scanProgress.set(
          scanProgress.get().copy(
            phase = "CANCELLED",
            message = "Scan cancelled.",
            fixAction = null,
            updatedAt = System.currentTimeMillis()
          )
        )
        emitScanCancelled(scanId, "cancel_flag", mapOf("subnet" to subnet))
        return
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
      lastScanResults.set(devices.values.toList())
      logEvent("scan_complete", mapOf("devices" to devices.size, "subnet" to subnet, "scanId" to scanId))
      emitScanCompleted(scanId, mapOf("devices" to devices.size, "subnet" to subnet))
    } catch (t: Throwable) {
      lastScanError.set(t.message)
      logEvent("scan_failed", mapOf("error" to t.message, "subnet" to subnet))
      updateScanProgress("ERROR", message = t.message ?: "Scan failed.")
    } finally {
      scanMutex.unlock()
    }
  }

  internal fun applyDeviceAction(device: Device, action: String): Device? {
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
      "wol" -> device.mac?.let { mac -> catching("rule:wol.send", fields = mapOf("deviceId" to device.id, "mac" to mac)) { sendMagicPacket(mac) } }
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

  internal fun recordDeviceEvent(id: String, event: String) {
    val list = deviceEvents.getOrPut(id) { mutableListOf() }
    val e = DeviceEvent(deviceId = id, ts = System.currentTimeMillis(), event = event)
    list.add(e)
    saveEvent(e)
    if (list.size > maxEventsPerDevice) {
      list.subList(0, list.size - maxEventsPerDevice).clear()
    }
  }

  internal fun uptimePct(events: List<DeviceEvent>, windowMs: Long, nowMs: Long = System.currentTimeMillis()): Double {
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

  private fun integrityCheck(db: SQLiteDatabase): String? {
    return db.rawQuery("PRAGMA integrity_check;", null).use { c ->
      if (!c.moveToFirst()) return@use null
      c.getString(0)
    }
  }

  private fun attemptDatabaseRepair(db: SQLiteDatabase) {
    // Best-effort only; SQLite doesn't provide a guaranteed in-place repair API.
    // These can fix some issues (stale WAL, index corruption) when the file isn't fully malformed.
    catching("db:repair:wal_checkpoint") { db.execSQL("PRAGMA wal_checkpoint(TRUNCATE);") }
    catching("db:repair:optimize") { db.execSQL("PRAGMA optimize;") }
    catching("db:repair:reindex") { db.execSQL("REINDEX;") }
    catching("db:repair:vacuum") { db.execSQL("VACUUM;") }
  }

  private fun backupDatabaseFiles(reason: String): File? {
    val dbFile = ctx.getDatabasePath("netninja.db")
    val walFile = File(dbFile.absolutePath + "-wal")
    val shmFile = File(dbFile.absolutePath + "-shm")

    if (!dbFile.exists() && !walFile.exists() && !shmFile.exists()) return null

    val outRoot = File(ctx.filesDir, "db-backups").apply { mkdirs() }
    val stamp = System.currentTimeMillis().toString()
    val outDir = File(outRoot, "netninja.db.$stamp").apply { mkdirs() }

    fun copyIfExists(src: File, destName: String) {
      if (!src.exists()) return
      val dest = File(outDir, destName)
      FileInputStream(src).use { input ->
        FileOutputStream(dest).use { output ->
          input.copyTo(output)
        }
      }
    }

    copyIfExists(dbFile, "netninja.db")
    copyIfExists(walFile, "netninja.db-wal")
    copyIfExists(shmFile, "netninja.db-shm")

    logEvent("db_backup_created", mapOf("reason" to reason, "path" to outDir.absolutePath))
    return outDir
  }

  private fun verifyDatabase() {
    try {
      val w = db.writableDatabase
      val first = integrityCheck(w)
      if (first != null && first != "ok") {
        log("DB corruption detected (integrity_check='$first'), attempting repair")
        logEvent("db_integrity_failed", mapOf("result" to first))

        attemptDatabaseRepair(w)
        val second = integrityCheck(w)
        if (second == "ok") {
          log("DB repair succeeded")
          dbNotice.set(
            DbNotice(
              atMs = System.currentTimeMillis(),
              level = "warn",
              action = "repaired",
              message = "Database issues were detected and repaired. If you notice missing history, export your data."
            )
          )
          return
        }

        log("DB repair failed (integrity_check='$second'), backing up and rebuilding")
        val backupDir = catchingOrNull("db:backup", fields = mapOf("reason" to "integrity_check_failed")) {
          backupDatabaseFiles("integrity_check_failed")
        }
        dbNotice.set(
          DbNotice(
            atMs = System.currentTimeMillis(),
            level = "error",
            action = "rebuild",
            message = "Database corruption detected. Local history/schedules/rules were reset. A backup was created before rebuild.",
            backupPath = backupDir?.absolutePath
          )
        )
        logEvent("db_rebuild", mapOf("reason" to "integrity_check_failed", "backupPath" to backupDir?.absolutePath))

        catching("db:close.before_rebuild") { db.close() }
        ctx.deleteDatabase("netninja.db")
      }
    } catch (e: Exception) {
      log("DB failure, recreating: ${e.message}")
      val backupDir = catchingOrNull("db:backup", fields = mapOf("reason" to "open_failed")) {
        backupDatabaseFiles("open_failed")
      }
      dbNotice.set(
        DbNotice(
          atMs = System.currentTimeMillis(),
          level = "error",
          action = "rebuild",
          message = "Database could not be opened. Local history/schedules/rules were reset. A backup was created before rebuild.",
          backupPath = backupDir?.absolutePath
        )
      )
      logEvent("db_rebuild", mapOf("reason" to "open_failed", "backupPath" to backupDir?.absolutePath, "error" to e.message))
      catching("db:close.after_open_failed") { db.close() }
      ctx.deleteDatabase("netninja.db")
    }
  }

  private fun loadPersistedState() {
    val r = db.readableDatabase

    catching("db:load:devices") {
      devices.clear()
      deviceRepository.loadDevices().forEach { d -> devices[d.id] = d }
    }

    catching("db:load:rules") {
      r.rawQuery("SELECT \"match\", \"action\" FROM rules", null).use { c ->
        while (c.moveToNext()) {
          rules += RuleEntry(c.getString(0), c.getString(1))
        }
      }
    }

    catching("db:load:schedules") {
      r.rawQuery("SELECT entry FROM schedules", null).use { c ->
        while (c.moveToNext()) {
          schedules += c.getString(0)
        }
      }
    }

    catching("db:load:events") {
      deviceEvents.clear()
      deviceRepository.loadEvents().forEach { (id, evs) -> deviceEvents[id] = evs.toMutableList() }
    }

    lastScanResults.set(devices.values.toList())

    catching("db:load:openclaw") {
      openClawDashboard.initialize()
      openClawDashboard.setSkillExecutor(skillExecutor)
    }
  }

  internal fun saveDevice(d: Device) {
    deviceRepository.saveDevice(d)
  }

  private fun saveEvent(e: DeviceEvent) {
    catching("db:saveEvent", fields = mapOf("deviceId" to e.deviceId, "event" to e.event)) {
      deviceRepository.saveEvent(e)
    }
  }

  internal fun saveRule(match: String, action: String) {
    val w = db.writableDatabase
    val cv = ContentValues().apply {
      put("match", match)
      put("action", action)
    }
    w.insert("rules", null, cv)
  }

  internal fun saveSchedule(entry: String) {
    val w = db.writableDatabase
    val cv = ContentValues().apply {
      put("entry", entry)
    }
    w.insert("schedules", null, cv)
  }

  internal fun log(s: String) {
    val line = "${System.currentTimeMillis()}: $s"
    logs.add(line)
    saveLog(line)
  }

  internal fun logEvent(event: String, fields: Map<String, Any?> = emptyMap()) {
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

  internal fun emitScanCancelled(scanId: String, reason: String, extra: Map<String, Any?> = emptyMap()) {
    logEvent("SCAN_CANCELLED", mapOf("scanId" to scanId, "reason" to reason) + extra)
    // Do not clear a newer scanId if a stale scan finishes/cancels late.
    activeScanId.compareAndSet(scanId, null)
  }

  private fun emitScanCompleted(scanId: String, extra: Map<String, Any?> = emptyMap()) {
    logEvent("SCAN_COMPLETED", mapOf("scanId" to scanId) + extra)
    // Do not clear a newer scanId if a stale scan completes late.
    activeScanId.compareAndSet(scanId, null)
  }

  private fun saveLog(msg: String) {
    try {
      val w = db.writableDatabase
      val cv = ContentValues().apply {
        put("ts", System.currentTimeMillis())
        put("msg", msg)
      }
      w.insert("logs", null, cv)
    } catch (e: Exception) {
      // Logging must never crash the engine.
      Log.e(TAG, "saveLog failed: ${e.message}", e)
    }
  }

  // ----------------- Network helpers -----------------

  internal fun deriveSubnetCidr(): String? {
    if (canAccessWifiDetails()) {
      val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      val dhcp = catchingOrNull("wifi:dhcpInfo") { wm.dhcpInfo }
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

  internal fun localNetworkInfo(): Map<String, Any?> {
    localNetworkInfoOverride?.let { return it() }
    if (canAccessWifiDetails()) {
      val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      val dhcp = catchingOrNull("wifi:dhcpInfo") { wm.dhcpInfo }
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

  internal fun readArpTable(): List<Device> {
    arpTableOverride?.let { return it() }
    val arpFile = File("/proc/net/arp")
    if (!arpFile.exists() || !arpFile.canRead()) return emptyList()
    val lines = catchingOrDefault("arp:readLines", emptyList(), fields = mapOf("path" to arpFile.absolutePath)) { arpFile.readLines() }
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

  internal fun cidrToIps(cidr: String): List<String> {
    ipListOverride?.let { return it(cidr) }
    return scanEngine.cidrToIps(cidr)
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

  internal fun scanPorts(ip: String, timeoutMs: Int): List<Int> {
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

  internal fun scanTcpPorts(ip: String, timeoutMs: Int, ports: List<Int>): List<Int> {
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

  internal fun isLikelyReachable(ip: String, timeoutMs: Int, retries: Int = 1): Boolean {
    reachabilityOverride?.let { return it(ip, timeoutMs) }
    val timeout = timeoutMs.coerceIn(80, 1_000)
    val probePorts = intArrayOf(80, 443, 22)
    repeat(retries.coerceAtLeast(1)) { attempt ->
      val pingOk = catchingOrDefault("reachability:isReachable", false, fields = mapOf("ip" to ip, "timeoutMs" to timeout)) {
        InetAddress.getByName(ip).isReachable(timeout)
      }
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

  internal fun permissionSummary(): Map<String, Any?> {
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
    return catchingOrDefault("wifi:isWifiEnabled", false) {
      val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      wm.isWifiEnabled
    }
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

  internal fun scanPreconditions(subnet: String? = null): ScanPreconditions {
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
        "Scan blocked: Wi-Fi scan permission missing."
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
        reason = "Scan blocked: Wi-Fi is disabled.",
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

  internal fun handlePermissionAction(actionRaw: String?, contextRaw: String?): PermissionsActionResponse {
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
      return catching("permissions:openSettings", fields = mapOf("action" to legacy, "context" to context)) {
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

  internal fun cachedResults(): List<Device> {
    val current = devices.values.toList()
    return if (current.isNotEmpty()) current else lastScanResults.get()
  }

  internal fun updateScanProgress(phase: String, message: String? = null, fixAction: String? = null) {
    val now = System.currentTimeMillis()
    val current = scanProgress.get()
    val nextProgress = if (phase == "PERMISSION_BLOCKED" || phase == "RATE_LIMITED") 0 else current.progress
    scanProgress.set(
      current.copy(
        progress = nextProgress,
        phase = phase,
        message = message,
        fixAction = fixAction,
        updatedAt = now
      )
    )
    if (message != null) {
      logEvent("scan_status", mapOf("phase" to phase, "message" to message))
    }
  }

  internal fun scheduleScan(subnet: String, reason: String) {
    val preconditions = scanPreconditions(subnet)
    if (!preconditions.ready) {
      val msg = preconditions.reason ?: "scan blocked: prerequisites not met"
      lastScanError.set(msg)
      logEvent(
        "SCAN_START_BLOCKED",
        mapOf(
          "reason" to preconditions.blocker,
          "androidVersion" to Build.VERSION.SDK_INT,
          "subnet" to subnet,
          "source" to reason
        )
      )
      updateScanProgress("PRECONDITION_BLOCKED", message = msg, fixAction = preconditions.fixAction)
      return
    }
    val now = System.currentTimeMillis()
    val lastReq = lastScanRequestedAt.get() ?: 0L
    if (now - lastReq < minScanIntervalMs()) {
      logEvent("scan_rate_limited", mapOf("sinceMs" to (now - lastReq), "subnet" to subnet))
      lastScanError.set("Scan rate-limited to protect battery.")
      updateScanProgress("RATE_LIMITED", message = "Scan rate-limited to protect battery.", fixAction = null)
      return
    }
    lastScanRequestedAt.set(now)
    if (scanJob?.isActive == true) {
      val previousScanId = activeScanId.get()
      scanCancel.set(true)
      scanJob?.cancel()
      if (!previousScanId.isNullOrBlank()) {
        emitScanCancelled(previousScanId, "stale_job", mapOf("subnet" to subnet, "source" to reason))
      }
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

  private suspend fun resolveHostname(ip: String): String? {
    hostnameOverride?.let { return it(ip) }
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

  internal fun lookupVendor(mac: String?): String? {
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

  internal fun sendMagicPacket(mac: String) {
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
    return catching("iface:selectInterfaceInfo") {
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

  private suspend fun warmUpArp(
    ips: List<String>,
    timeoutMs: Int,
    netInfo: Map<String, Any?>,
    subnet: String,
    scanId: String
  ) {
    if (ips.isEmpty()) return
    val payload = byteArrayOf(0x1)
    val total = ips.size
    val socket = catchingOrNull("arpWarmup:DatagramSocket") { java.net.DatagramSocket() } ?: return
    socket.use { s ->
      s.broadcast = true
      s.soTimeout = timeoutMs.coerceIn(80, 800)
      var sendErrors = 0
      ips.forEachIndexed { idx, ip ->
        if (!coroutineContext.isActive) return
        if (activeScanId.get() != scanId) return
        if (scanCancel.get()) return
        catching("arpWarmup:send", fields = mapOf("ip" to ip)) {
          val packet = java.net.DatagramPacket(payload, payload.size, InetAddress.getByName(ip), 9)
          s.send(packet)
        }.onFailure { if (sendErrors < 3) sendErrors++ }
        if (idx % 64 == 0 || idx == total - 1) {
          val pct = ((idx + 1).toDouble() / total.toDouble() * 10.0).toInt().coerceIn(0, 10)
          scanProgress.set(
            ScanProgress(
              progress = pct,
              phase = "ARP_WARMUP",
              message = null,
              fixAction = null,
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
      if (sendErrors > 0) {
        // Summary entry to avoid per-IP spam (errors themselves are already logged with context).
        logEvent("arp_warmup_send_errors", mapOf("count" to sendErrors))
      }
    }
  }
}
