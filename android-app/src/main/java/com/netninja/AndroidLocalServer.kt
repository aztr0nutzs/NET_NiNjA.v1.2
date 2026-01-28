package com.netninja

import android.content.ContentValues
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.text.format.Formatter
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import android.database.sqlite.SQLiteDatabase

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

@Serializable data class DeviceEvent(val deviceId: String, val ts: Long, val event: String)
@Serializable data class ScanRequest(val subnet: String? = null, val timeoutMs: Int? = 300)
@Serializable data class LoginRequest(val username: String? = null, val password: String? = null)
@Serializable data class ActionRequest(val ip: String? = null, val mac: String? = null, val url: String? = null)
@Serializable data class ScheduleRequest(val subnet: String? = null, val freq: String? = null)
@Serializable data class RuleRequest(val match: String? = null, val action: String? = null)
@Serializable data class RuleEntry(val match: String, val action: String)

class AndroidLocalServer(private val ctx: Context) {

  private val db = LocalDatabase(ctx)

  private val devices = ConcurrentHashMap<String, Device>()
  private val deviceEvents = ConcurrentHashMap<String, MutableList<DeviceEvent>>()
  private val maxEventsPerDevice = 4000

  private val schedules = CopyOnWriteArrayList<String>()
  private val rules = CopyOnWriteArrayList<RuleEntry>()

  private val logs = ConcurrentLinkedQueue<String>()
  private val lastScanAt = AtomicReference<Long?>(null)

  // Auth sessions
  private val sessions = ConcurrentHashMap<String, Long>()
  private val sessionTtlMs = 60 * 60 * 1000L

  // Automation + resilience
  private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var schedulerJob: Job? = null
  private var schedulerPaused = false
  private val minScanIntervalMs = 60_000L

  private val watchdogScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var engine: ApplicationEngine? = null

  private val scanMutex = Mutex()

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

            // Localhost-only
            install(CORS) {
              allowMethod(HttpMethod.Get)
              allowMethod(HttpMethod.Post)
              allowHeader(HttpHeaders.ContentType)
              allowHeader(HttpHeaders.Authorization)
              allowHost("127.0.0.1")
              allowHost("localhost")
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
    runCatching { watchdogScope.cancel() }
    runCatching { engine?.stop(500, 1000) }
  }

  private fun Application.setupRoutes(uiDir: File) {
    routing {
      staticFiles("/ui", uiDir, index = "ninja_mobile_new.html")
      get("/") { call.respondRedirect("/ui/ninja_mobile_new.html") }

      // Public probe
      get("/api/v1/system/info") {
        call.respond(mapOf("platform" to "android", "time" to System.currentTimeMillis()))
      }

      // Login (public)
      post("/api/v1/auth/login") {
        val req = runCatching { call.receive<LoginRequest>() }.getOrNull() ?: LoginRequest()
        val user = req.username?.trim().orEmpty()
        val pass = req.password?.trim().orEmpty()

        // TODO: real credential store (keystore + PBKDF2).
        val ok = (user == "ninja" && pass == "neon")
        if (!ok) {
          call.respond(HttpStatusCode.Unauthorized, mapOf("ok" to false))
          return@post
        }

        val token = UUID.randomUUID().toString()
        sessions[token] = System.currentTimeMillis() + sessionTtlMs
        call.respond(mapOf("ok" to true, "token" to token, "expiresInMs" to sessionTtlMs))
      }

      fun ApplicationCall.extractToken(): String? {
        val auth = request.headers[HttpHeaders.Authorization]?.trim().orEmpty()
        if (auth.startsWith("Bearer ", ignoreCase = true)) {
          val tok = auth.substringAfter("Bearer ").trim()
          if (tok.isNotBlank()) return tok
        }
        // SSE can pass ?token=
        return request.queryParameters["token"]?.trim()?.takeIf { it.isNotBlank() }
      }

      fun ApplicationCall.isAuthorized(): Boolean {
        val token = extractToken() ?: return false
        val exp = sessions[token] ?: return false
        if (exp <= System.currentTimeMillis()) {
          sessions.remove(token)
          return false
        }
        return true
      }

      suspend fun ApplicationCall.requireAuth(): Boolean {
        if (isAuthorized()) return true
        respond(HttpStatusCode.Unauthorized, mapOf("ok" to false, "error" to "unauthorized"))
        return false
      }

      // --- AUTH REQUIRED BEYOND THIS POINT ---

      get("/api/v1/network/info") {
        if (!call.requireAuth()) return@get
        call.respond(localNetworkInfo())
      }

      post("/api/v1/discovery/scan") {
        if (!call.requireAuth()) return@post
        val req = runCatching { call.receive<ScanRequest>() }.getOrNull() ?: ScanRequest()
        val subnet = req.subnet?.trim().takeUnless { it.isNullOrBlank() } ?: deriveSubnetCidr()
        log("scan request subnet=$subnet")
        performScan(subnet)
        call.respond(devices.values.toList())
      }

      get("/api/v1/discovery/results") {
        if (!call.requireAuth()) return@get
        call.respond(devices.values.toList())
      }

      get("/api/v1/devices/{id}") {
        if (!call.requireAuth()) return@get
        val id = call.parameters["id"]?.lowercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val d = devices[id] ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(d)
      }

      get("/api/v1/devices/{id}/history") {
        if (!call.requireAuth()) return@get
        val id = call.parameters["id"]?.lowercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(deviceEvents[id].orEmpty())
      }

      get("/api/v1/devices/{id}/uptime") {
        if (!call.requireAuth()) return@get
        val id = call.parameters["id"]?.lowercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val hist = deviceEvents[id].orEmpty()
        call.respond(mapOf("uptimePct24h" to uptimePct(hist, 86_400_000L)))
      }

      get("/api/v1/export/devices") {
        if (!call.requireAuth()) return@get
        call.respond(devices.values.toList())
      }

      // Schedules
      get("/api/v1/schedules") {
        if (!call.requireAuth()) return@get
        call.respond(schedules.toList())
      }
      post("/api/v1/schedules") {
        if (!call.requireAuth()) return@post
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
        if (!call.requireAuth()) return@post
        schedulerPaused = !schedulerPaused
        call.respond(mapOf("ok" to true, "paused" to schedulerPaused))
      }

      // Rules
      get("/api/v1/rules") {
        if (!call.requireAuth()) return@get
        call.respond(rules.toList())
      }
      post("/api/v1/rules") {
        if (!call.requireAuth()) return@post
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
        if (!call.requireAuth()) return@post
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val start = System.currentTimeMillis()
        val ok = isLikelyReachable(ip, 350)
        val rtt = if (ok) (System.currentTimeMillis() - start) else null
        call.respond(mapOf("ok" to ok, "ip" to ip, "rttMs" to rtt))
      }

      post("/api/v1/actions/http") {
        if (!call.requireAuth()) return@post
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

      post("/api/v1/actions/wol") {
        if (!call.requireAuth()) return@post
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val mac = req.mac?.trim()
        if (mac.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing mac"))
        val ok = runCatching { sendMagicPacket(mac) }.isSuccess
        call.respond(mapOf("ok" to ok, "mac" to mac))
      }

      post("/api/v1/actions/snmp") {
        if (!call.requireAuth()) return@post
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val ok = runCatching { scanPorts(ip, 350).contains(161) }.getOrDefault(false)
        call.respond(mapOf("ok" to ok, "ip" to ip))
      }

      post("/api/v1/actions/ssh") {
        if (!call.requireAuth()) return@post
        val req = runCatching { call.receive<ActionRequest>() }.getOrNull() ?: ActionRequest()
        val ip = req.ip?.trim()
        if (ip.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "missing ip"))
        val ok = runCatching { scanPorts(ip, 350).contains(22) }.getOrDefault(false)
        call.respond(mapOf("ok" to ok, "ip" to ip, "note" to "SSH port probe only"))
      }

      get("/api/v1/metrics") {
        if (!call.requireAuth()) return@get
        val memTotal = Runtime.getRuntime().totalMemory()
        val memFree = Runtime.getRuntime().freeMemory()
        val memUsed = memTotal - memFree
        val all = devices.values.toList()
        call.respond(
          mapOf(
            "uptimeMs" to SystemClock.elapsedRealtime(),
            "memTotal" to memTotal,
            "memUsed" to memUsed,
            "devicesTotal" to all.size,
            "devicesOnline" to all.count { it.online },
            "lastScanAt" to lastScanAt.get()
          )
        )
      }

      get("/api/v1/logs/stream") {
        if (!call.requireAuth()) return@get
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
      log("scheduled scan triggered: $subnet")
      performScan(subnet)
    }
  }

  private suspend fun performScan(subnet: String) {
    // Prevent overlapping scans
    if (!scanMutex.tryLock()) {
      log("scan skipped: already running")
      return
    }
    try {
      val timeout = 300
      val arp = readArpTable()
      val ips = cidrToIps(subnet).take(4096)
      val sem = Semaphore(48)

      coroutineScope {
        ips.map { ip ->
          async(Dispatchers.IO) {
            sem.withPermit {
              val arpDev = arp.firstOrNull { it.ip == ip }
              val mac = arpDev?.mac
              val reachable = isLikelyReachable(ip, timeout)

              if (!reachable && mac == null) return@withPermit

              val hostname = if (reachable) resolveHostname(ip) else null
              val vendor = lookupVendor(mac)
              val os = guessOs(ip, timeout, hostname, vendor)

              val id = (mac ?: ip).lowercase()
              val now = System.currentTimeMillis()

              val newDev = Device(
                id = id,
                ip = ip,
                online = reachable,
                lastSeen = now,
                mac = mac?.lowercase(),
                hostname = hostname,
                vendor = vendor,
                os = os
              )

              val old = devices.put(id, newDev)
              saveDevice(newDev)
              emitEvents(old, newDev)
              evaluateRules(old, newDev)
            }
          }
        }.awaitAll()
      }

      lastScanAt.set(System.currentTimeMillis())
      log("scan complete: devices=${devices.size}")
    } catch (t: Throwable) {
      log("scan crash: ${t.message}")
    } finally {
      scanMutex.unlock()
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
      else -> log("unknown rule action: ${rule.action}")
    }
  }

  // ----------------- Event history + uptime -----------------

  private fun emitEvents(old: Device?, now: Device) {
    val id = now.id.lowercase()
    val list = deviceEvents.getOrPut(id) { mutableListOf() }

    fun add(event: String) {
      val e = DeviceEvent(deviceId = id, ts = System.currentTimeMillis(), event = event)
      list.add(e)
      saveEvent(e)
      if (list.size > maxEventsPerDevice) {
        list.subList(0, list.size - maxEventsPerDevice).clear()
      }
    }

    if (old == null) {
      add("NEW_DEVICE")
      if (now.online) add("DEVICE_ONLINE")
      return
    }
    if (old.online != now.online) add(if (now.online) "DEVICE_ONLINE" else "DEVICE_OFFLINE")
    if (old.ip != now.ip) add("IP_CHANGED")
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

  private fun loadPersistedState() {
    val r = db.readableDatabase

    runCatching {
      r.rawQuery("SELECT id, ip, online, lastSeen, mac, hostname, vendor, os FROM devices", null).use { c ->
        while (c.moveToNext()) {
          val id = c.getString(0)
          devices[id] = Device(
            id = id,
            ip = c.getString(1),
            online = c.getInt(2) == 1,
            lastSeen = c.getLong(3),
            mac = c.getString(4),
            hostname = c.getString(5),
            vendor = c.getString(6),
            os = c.getString(7)
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
  }

  private fun saveDevice(d: Device) {
    val w = db.writableDatabase
    val cv = ContentValues().apply {
      put("id", d.id)
      put("ip", d.ip)
      put("online", if (d.online) 1 else 0)
      put("lastSeen", d.lastSeen)
      put("mac", d.mac)
      put("hostname", d.hostname)
      put("vendor", d.vendor)
      put("os", d.os)
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

  private fun deriveSubnetCidr(): String {
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val dhcp = wm.dhcpInfo
    val ip = Formatter.formatIpAddress(dhcp.ipAddress)
    val mask = Formatter.formatIpAddress(dhcp.netmask)
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
    if (!arpFile.exists() || !arpFile.canRead()) return emptyList()
    val lines = runCatching { arpFile.readLines() }.getOrDefault(emptyList())
    if (lines.size <= 1) return emptyList()

    val out = mutableListOf<Device>()
    for (line in lines.drop(1)) {
      val parts = line.trim().split(Regex("\s+")).filter { it.isNotBlank() }
      if (parts.size < 4) continue
      val ip = parts[0]
      val flags = parts[2]
      val mac = parts[3]
      if (mac == "00:00:00:00:00:00") continue
      val online = flags != "0x0"
      out += Device(
        id = mac.lowercase(),
        ip = ip,
        online = online,
        lastSeen = System.currentTimeMillis(),
        mac = mac.lowercase()
      )
    }
    return out
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
    val ports = listOf(22, 80, 443, 445, 3389, 5555, 161)
    val open = ArrayList<Int>(4)
    val timeout = timeoutMs.coerceIn(80, 2_000)
    for (p in ports) {
      try {
        Socket().use { s ->
          s.connect(InetSocketAddress(ip, p), timeout)
          open += p
        }
      } catch (_: Exception) {
        // ignore
      }
    }
    return open
  }

  private fun isLikelyReachable(ip: String, timeoutMs: Int): Boolean {
    val timeout = timeoutMs.coerceIn(80, 1_000)
    val probePorts = intArrayOf(80, 443, 22)
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
    return false
  }

  private fun resolveHostname(ip: String): String? =
    runCatching {
      val host = InetAddress.getByName(ip).canonicalHostName
      if (host == ip) null else host
    }.getOrNull()

  private fun lookupVendor(mac: String?): String? {
    if (mac.isNullOrBlank() || mac.length < 8) return null
    val key = mac.uppercase().replace("-", ":").substring(0, 8)
    val vendors = mapOf(
      "B8:27:EB" to "Raspberry Pi",
      "00:1A:2B" to "Cisco",
      "FC:FB:FB" to "Google"
    )
    return vendors[key]
  }

  private fun guessOs(ip: String, timeoutMs: Int, hostname: String?, vendor: String?): String? {
    val host = hostname?.lowercase().orEmpty()
    val vend = vendor?.lowercase().orEmpty()
    val ports = scanPorts(ip, timeoutMs.coerceAtMost(450))
    return when {
      ports.contains(445) || ports.contains(3389) -> "Windows"
      ports.contains(5555) || host.contains("android") -> "Android"
      ports.contains(22) -> "Linux"
      vend.contains("apple") || host.contains("mac") -> "macOS"
      else -> null
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
}
