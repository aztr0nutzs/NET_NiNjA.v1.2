package server.openclaw

import java.sql.Connection
import java.time.Instant
import java.util.ArrayDeque
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class GatewayStatus(
  val key: String,
  val name: String,
  val status: String,
  val sessions: Int,
  val lastPingMs: Long? = null
)

@Serializable
data class InstanceSnapshot(
  val name: String,
  val profile: String,
  val workspace: String,
  val sandbox: Boolean,
  val access: String,
  val state: String,
  val active: Boolean
)

@Serializable
data class SessionSnapshot(
  val id: String,
  val type: String,
  val target: String,
  val state: String,
  val payload: String? = null,
  val createdAt: Long,
  val updatedAt: Long
)

@Serializable
data class SkillSnapshot(
  val name: String,
  val description: String,
  val status: String
)

@Serializable
data class MessageSnapshot(
  val id: String,
  val gateway: String,
  val channel: String,
  val body: String,
  val sentAt: Long
)

@Serializable
data class DashboardSnapshot(
  val connected: Boolean,
  val host: String?,
  val profile: String,
  val workspace: String,
  val lastSync: Long?,
  val activeInstance: String?,
  val memoryIndexed: Boolean,
  val memoryItems: Int,
  val gateways: List<GatewayStatus>,
  val instances: List<InstanceSnapshot>,
  val sessions: List<SessionSnapshot>,
  val skills: List<SkillSnapshot>,
  val mode: String
)

@Serializable
data class CommandResult(
  val command: String,
  val output: String,
  val executedAt: Long = System.currentTimeMillis()
)

@Serializable
data class GatewayPingResult(
  val key: String,
  val status: String,
  val latencyMs: Long
)

@Serializable
data class CronJobSnapshot(
  val id: String,
  val schedule: String,
  val command: String,
  val enabled: Boolean,
  val lastRunAt: Long? = null,
  val lastResult: String? = null
)

@Serializable
data class CronJobRequest(
  val schedule: String? = null,
  val command: String? = null,
  val enabled: Boolean? = null
)

object OpenClawDashboardState {
  private val lock = Any()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val gateways = LinkedHashMap<String, GatewayStatus>()
  private val instances = LinkedHashMap<String, InstanceSnapshot>()
  private val sessions = LinkedHashMap<String, SessionSnapshot>()
  private val skills = LinkedHashMap<String, SkillSnapshot>()
  private val cronJobs = LinkedHashMap<String, CronJobSnapshot>()
  private val logs = ArrayDeque<String>()
  private val messages = ArrayDeque<MessageSnapshot>()
  private val sessionCounter = AtomicLong(1026)
  private val messageCounter = AtomicLong(1)
  private val cronCounter = AtomicLong(1)
  private val maxLogEntries = 200
  private val maxMessages = 200
  private val maxSessions = 200
  private val maxCronJobs = 100

  private val sessionScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "openclaw-sessions").apply { isDaemon = true }
  }

  private val cronScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "openclaw-cron").apply { isDaemon = true }
  }
  private var cronSchedulerStarted = false

  @Volatile private var db: Connection? = null

  private var connected = false
  private var host: String? = null
  private var profile = "default"
  private var workspace = "~/.openclaw/workspace"
  private var lastSync: Long? = null
  private var activeInstance: String? = null
  private var memoryIndexed = false
  private var memoryItems = 0
  private var mode = "safe"

  init {
    listOf(
      GatewayStatus("whatsapp", "WhatsApp", "down", 0),
      GatewayStatus("telegram", "Telegram", "down", 0),
      GatewayStatus("discord", "Discord", "down", 0),
      GatewayStatus("imessage", "iMessage", "down", 0)
    ).forEach { gateways[it.key] = it }

    val defaultInstance = InstanceSnapshot(
      name = "default",
      profile = "default",
      workspace = "~/.openclaw/workspace",
      sandbox = false,
      access = "rw",
      state = "stopped",
      active = true
    )
    val workInstance = InstanceSnapshot(
      name = "work",
      profile = "work",
      workspace = "~/.openclaw/workspace-work",
      sandbox = true,
      access = "ro",
      state = "stopped",
      active = false
    )
    instances[defaultInstance.name] = defaultInstance
    instances[workInstance.name] = workInstance
    activeInstance = defaultInstance.name

    listOf(
      SkillSnapshot("scan_network", "Scan subnet for OpenClaw nodes.", "idle"),
      SkillSnapshot("onvif_discovery", "Discover ONVIF cameras on the LAN.", "idle"),
      SkillSnapshot("rtsp_probe", "Probe RTSP endpoints for availability.", "idle"),
      SkillSnapshot("port_scan", "Deep port scan on a target host.", "idle"),
      SkillSnapshot("wol_broadcast", "Send Wake-on-LAN magic packets.", "idle")
    ).forEach { skills[it.name] = it }

    appendLog("OpenClaw dashboard state initialized.")
    startSessionScheduler()
  }

  private fun startSessionScheduler() {
    sessionScheduler.scheduleWithFixedDelay(
      {
        runCatching { processQueuedSessions() }
          .onFailure { e ->
            synchronized(lock) {
              appendLog("Session scheduler error: ${e.message ?: "unknown"}")
            }
          }
      },
      5L,
      5L,
      TimeUnit.SECONDS
    )
  }

  fun snapshot(): DashboardSnapshot = synchronized(lock) {
    DashboardSnapshot(
      connected = connected,
      host = host,
      profile = profile,
      workspace = workspace,
      lastSync = lastSync,
      activeInstance = activeInstance,
      memoryIndexed = memoryIndexed,
      memoryItems = memoryItems,
      gateways = gateways.values.toList(),
      instances = instances.values.toList(),
      sessions = sessions.values.toList(),
      skills = skills.values.toList(),
      mode = mode
    )
  }

  fun listGateways(): List<GatewayStatus> = synchronized(lock) { gateways.values.toList() }

  fun listInstances(): List<InstanceSnapshot> = synchronized(lock) { instances.values.toList() }

  fun listSessions(): List<SessionSnapshot> = synchronized(lock) { sessions.values.toList() }

  fun listSkills(): List<SkillSnapshot> = synchronized(lock) { skills.values.toList() }

  fun listLogs(): List<String> = synchronized(lock) { logs.toList() }

  fun listMessages(): List<MessageSnapshot> = synchronized(lock) { messages.toList() }

  fun connect(newHost: String?): DashboardSnapshot = synchronized(lock) {
    connected = true
    if (!newHost.isNullOrBlank()) {
      host = newHost.trim()
    }
    lastSync = System.currentTimeMillis()
    appendLog("Gateway connected.")
    persistConfig()
    snapshot()
  }

  fun disconnect(): DashboardSnapshot = synchronized(lock) {
    connected = false
    appendLog("Gateway disconnected.")
    persistConfig()
    snapshot()
  }

  fun refresh(): DashboardSnapshot = synchronized(lock) {
    lastSync = System.currentTimeMillis()
    appendLog("Refresh requested.")
    persistConfig()
    snapshot()
  }

  fun panic(): DashboardSnapshot = synchronized(lock) {
    sessions.replaceAll { _, entry ->
      entry.copy(state = "canceled", updatedAt = System.currentTimeMillis())
    }
    gateways.replaceAll { _, entry ->
      entry.copy(status = "down", sessions = 0)
    }
    appendLog("PANIC triggered: sessions canceled, gateways down.")
    persistSessions()
    persistGateways()
    snapshot()
  }

  fun restartGateway(key: String): GatewayStatus? = synchronized(lock) {
    val gateway = gateways[key] ?: return null
    val updated = gateway.copy(status = "up", sessions = 0, lastPingMs = null)
    gateways[key] = updated
    appendLog("Gateway restart requested: $key.")
    persistGateways()
    updated
  }

  fun pingGateway(key: String): GatewayPingResult? = synchronized(lock) {
    val gateway = gateways[key] ?: return null
    val latency = Random.nextLong(18, 140)
    val updated = gateway.copy(lastPingMs = latency)
    gateways[key] = updated
    appendLog("Gateway ping: $key (${latency}ms).")
    persistGateways()
    GatewayPingResult(key = key, status = updated.status, latencyMs = latency)
  }

  fun addInstance(
    name: String,
    profileInput: String?,
    workspaceInput: String?,
    sandboxInput: Boolean?,
    accessInput: String?
  ): InstanceSnapshot? = synchronized(lock) {
    if (name.isBlank() || instances.containsKey(name)) return null
    val instance = InstanceSnapshot(
      name = name,
      profile = profileInput?.ifBlank { profile } ?: profile,
      workspace = workspaceInput?.ifBlank { workspace } ?: workspace,
      sandbox = sandboxInput ?: false,
      access = accessInput?.ifBlank { "rw" } ?: "rw",
      state = "stopped",
      active = false
    )
    instances[name] = instance
    appendLog("Instance added: $name.")
    persistInstances()
    instance
  }

  fun selectInstance(name: String): InstanceSnapshot? = synchronized(lock) {
    instances[name] ?: return null
    instances.replaceAll { _, entry -> entry.copy(active = entry.name == name) }
    activeInstance = name
    appendLog("Instance selected: $name.")
    persistInstances()
    persistConfig()
    instances[name]
  }

  fun activateInstance(name: String): InstanceSnapshot? = synchronized(lock) {
    val instance = instances[name] ?: return null
    val updated = instance.copy(state = "active", active = true)
    instances[name] = updated
    instances.replaceAll { key, entry ->
      if (key == name) updated else entry.copy(active = false)
    }
    activeInstance = name
    appendLog("Instance activated: $name.")
    persistInstances()
    persistConfig()
    updated
  }

  fun stopInstance(name: String): InstanceSnapshot? = synchronized(lock) {
    val instance = instances[name] ?: return null
    val updated = instance.copy(state = "stopped")
    instances[name] = updated
    appendLog("Instance stopped: $name.")
    persistInstances()
    updated
  }

  fun createSession(type: String, target: String, payload: String?): SessionSnapshot = synchronized(lock) {
    val now = System.currentTimeMillis()
    val id = "S-${sessionCounter.incrementAndGet()}"
    val session = SessionSnapshot(
      id = id,
      type = type,
      target = target,
      state = "queued",
      payload = payload,
      createdAt = now,
      updatedAt = now
    )
    sessions[id] = session
    trimSessions()
    appendLog("Session queued: $id ($type).")
    persistSessions()
    session
  }

  fun cancelSession(id: String): SessionSnapshot? = synchronized(lock) {
    val session = sessions[id] ?: return null
    val updated = session.copy(state = "canceled", updatedAt = System.currentTimeMillis())
    sessions[id] = updated
    appendLog("Session canceled: $id.")
    persistSessions()
    updated
  }

  // ── Session worker ──────────────────────────────────────────────

  /**
   * Called every 5 s by [sessionScheduler].
   * Picks up sessions in "queued" state, transitions them through
   * running → completed/failed.
   */
  private fun processQueuedSessions() {
    val queued = synchronized(lock) {
      sessions.values
        .filter { it.state == "queued" }
        .map { it.id to it }
    }
    if (queued.isEmpty()) return

    for ((id, session) in queued) {
      // Mark running — flag avoids continue/break inside inline lambdas (needs Kotlin 2.2)
      var shouldRun = false
      synchronized(lock) {
        val current = sessions[id]
        if (current != null && current.state == "queued") {
          sessions[id] = current.copy(state = "running", updatedAt = System.currentTimeMillis())
          appendLog("Session running: $id (${session.type}).")
          persistSessions()
          shouldRun = true
        }
      }

      if (shouldRun) {
        // Execute work outside the lock
        val result = runCatching {
          val cmd = resolveSessionCommand(session)
          runCommand(cmd).output.take(2000)
        }

        // Transition to completed or failed
        synchronized(lock) {
          val current = sessions[id]
          if (current != null && current.state == "running") {
            val now = System.currentTimeMillis()
            val finalState = if (result.isSuccess) "completed" else "failed"
            val output = result.getOrElse { it.message ?: "unknown error" }
            sessions[id] = current.copy(
              state = finalState,
              payload = output.take(2000),
              updatedAt = now
            )
            appendLog("Session $finalState: $id -> ${output.take(200)}")
            persistSessions()
          }
        }
      }
    }
  }

  fun refreshSkills(): List<SkillSnapshot> = synchronized(lock) {
    skills.replaceAll { _, entry -> entry.copy(status = "idle") }
    appendLog("Skills refreshed.")
    persistSkills()
    skills.values.toList()
  }

  fun invokeSkill(name: String): SkillSnapshot? = synchronized(lock) {
    val skill = skills[name] ?: return null
    val updated = skill.copy(status = "running")
    skills[name] = updated
    appendLog("Skill invoked: $name.")
    persistSkills()
    updated
  }

  fun setMode(newMode: String): String = synchronized(lock) {
    mode = newMode
    appendLog("Mode set: $newMode.")
    persistConfig()
    mode
  }

  fun updateConfig(newHost: String?, newProfile: String?, newWorkspace: String?): DashboardSnapshot = synchronized(lock) {
    if (!newHost.isNullOrBlank()) host = newHost.trim()
    if (!newProfile.isNullOrBlank()) profile = newProfile.trim()
    if (!newWorkspace.isNullOrBlank()) workspace = newWorkspace.trim()
    appendLog("Configuration updated.")
    persistConfig()
    snapshot()
  }

  fun addMessage(gateway: String, channel: String, body: String): MessageSnapshot = synchronized(lock) {
    val now = System.currentTimeMillis()
    val message = MessageSnapshot(
      id = "M-${messageCounter.incrementAndGet()}",
      gateway = gateway,
      channel = channel,
      body = body,
      sentAt = now
    )
    messages.addLast(message)
    trimMessages()
    appendLog("Message queued: ${gateway}:${channel}.")
    persistMessages()
    message
  }

  fun addChatMessage(body: String, channel: String = "general"): MessageSnapshot = synchronized(lock) {
    val trimmed = body.trim()
    val ch = channel.trim().ifBlank { "general" }

    val userMsg = addMessage(gateway = "user", channel = ch, body = trimmed)

    val reply = when {
      trimmed.isBlank() -> "Say something. Humans love silence, apps shouldn't."
      trimmed.startsWith("/") -> runCommand(trimmed).output.ifBlank { "OK." }.take(3000)
      trimmed.equals("help", ignoreCase = true) -> runCommand("/help").output.take(3000)
      else -> "I'm online. Try /help, /status, /nodes, /skills, /gateways, /instances."
    }

    addMessage(gateway = "openclaw", channel = ch, body = reply)
    userMsg
  }

  private sealed interface ParsedNodeCommand {
    object ListNodes : ParsedNodeCommand
    data class Ping(val nodeId: String) : ParsedNodeCommand
    data class Run(val nodeId: String, val action: String) : ParsedNodeCommand
    data class Invalid(val error: String) : ParsedNodeCommand
  }

  private fun resolveSessionCommand(session: SessionSnapshot): String {
    val type = session.type.trim().lowercase()
    val target = session.target.trim()
    val payload = session.payload?.trim().orEmpty()
    return when {
      type == "node" && target.isNotBlank() -> normalizeNodeSessionCommand(target, payload)
      target.startsWith("node:", ignoreCase = true) -> {
        val nodeId = target.substringAfter(":", "").trim()
        if (nodeId.isBlank()) "node target required" else normalizeNodeSessionCommand(nodeId, payload)
      }
      else -> session.payload ?: session.target
    }
  }

  private fun normalizeNodeSessionCommand(nodeId: String, payload: String): String {
    return when {
      payload.isBlank() || payload.equals("ping", ignoreCase = true) -> "/node $nodeId ping"
      payload.startsWith("run ", ignoreCase = true) -> "/node $nodeId ${payload.replaceFirst(Regex("(?i)^run\\s+"), "run ")}"
      else -> "/node $nodeId run $payload"
    }
  }

  private fun parseNodeCommand(rawCommand: String): ParsedNodeCommand? {
    val trimmed = rawCommand.trim()
    if (!(trimmed.startsWith("/node") || trimmed.startsWith("node"))) return null
    val normalized = if (trimmed.startsWith("/")) trimmed.substring(1) else trimmed
    val parts = normalized.split(Regex("\\s+"), limit = 4)
    if (parts.isEmpty() || !parts[0].equals("node", ignoreCase = true)) return null
    if (parts.size < 2) return ParsedNodeCommand.Invalid("Usage: /node list | /node <id> ping | /node <id> run <action>")
    if (parts[1].equals("list", ignoreCase = true)) return ParsedNodeCommand.ListNodes

    val nodeId = parts[1].trim()
    if (nodeId.isBlank()) return ParsedNodeCommand.Invalid("Node id is required")
    if (parts.size < 3) return ParsedNodeCommand.Invalid("Usage: /node <id> ping | /node <id> run <action>")

    return when {
      parts[2].equals("ping", ignoreCase = true) -> ParsedNodeCommand.Ping(nodeId)
      parts[2].equals("run", ignoreCase = true) -> {
        val action = if (parts.size >= 4) parts[3].trim() else ""
        if (action.isBlank()) ParsedNodeCommand.Invalid("Usage: /node <id> run <action>")
        else ParsedNodeCommand.Run(nodeId, action)
      }
      else -> ParsedNodeCommand.Invalid("Unknown node verb '${parts[2]}'. Use ping or run")
    }
  }

  private fun nodeListOutput(): String =
    "Node count: ${OpenClawGatewayState.nodeCount()}, " +
      "Uptime: ${OpenClawGatewayState.uptimeMs()}ms\n" +
      OpenClawGatewayState.listNodes().joinToString("\n") {
        "  ${it.id}: caps=${it.capabilities}, last=${it.lastSeen}"
      }.ifBlank { "  (no nodes)" }

  private fun executeNodeCommand(parsed: ParsedNodeCommand): String {
    return when (parsed) {
      ParsedNodeCommand.ListNodes -> nodeListOutput()
      is ParsedNodeCommand.Invalid -> "ERROR: ${parsed.error}"
      is ParsedNodeCommand.Ping -> {
        val outcome = OpenClawGatewayState.dispatchNodeAction(parsed.nodeId, "ping")
        if (outcome.ok) {
          "NODE ${parsed.nodeId} ping success (request=${outcome.requestId}, ${outcome.durationMs}ms): ${outcome.payload ?: "ok"}"
        } else {
          "NODE ${parsed.nodeId} ping ${outcome.status}: ${outcome.error ?: "unknown error"}"
        }
      }
      is ParsedNodeCommand.Run -> {
        val outcome = OpenClawGatewayState.dispatchNodeAction(parsed.nodeId, parsed.action)
        if (outcome.ok) {
          "NODE ${parsed.nodeId} run success (request=${outcome.requestId}, ${outcome.durationMs}ms): ${outcome.payload ?: "ok"}"
        } else {
          "NODE ${parsed.nodeId} run ${outcome.status}: ${outcome.error ?: "unknown error"}"
        }
      }
    }
  }

  fun runCommand(command: String): CommandResult = synchronized(lock) {
    appendLog("Command: $command")
    val nodeCommand = parseNodeCommand(command)
    val output = when {
      nodeCommand != null -> executeNodeCommand(nodeCommand)
      command.startsWith("status") || command.startsWith("/status") -> {
        json.encodeToString(snapshot())
      }
      command.startsWith("config") || command.startsWith("/config") -> {
        buildJsonObject {
          put("host", host)
          put("profile", profile)
          put("workspace", workspace)
          put("connected", connected)
          put("lastSync", lastSync ?: 0L)
          put("mode", mode)
        }.toString()
      }
      command.startsWith("debug") || command.startsWith("/debug") -> {
        buildJsonObject {
          put("connected", connected)
          put("host", host)
          put("profile", profile)
          put("workspace", workspace)
          put("lastSync", lastSync ?: 0L)
          put("activeInstance", activeInstance)
          put("memoryIndexed", memoryIndexed)
          put("memoryItems", memoryItems)
          put("mode", mode)
          put("gatewayCount", gateways.size)
          put("instanceCount", instances.size)
          put("sessionCount", sessions.size)
          put("skillCount", skills.size)
          put("cronJobCount", cronJobs.size)
          put("messageCount", messages.size)
          put("logCount", logs.size)
        }.toString()
      }
      command.startsWith("nodes") || command.startsWith("/nodes") -> {
        "Node count: ${OpenClawGatewayState.nodeCount()}, " +
                "Uptime: ${OpenClawGatewayState.uptimeMs()}ms\n" +
                OpenClawGatewayState.listNodes().joinToString("\n") {
                  "  ${it.id}: caps=${it.capabilities}, last=${it.lastSeen}"
                }.ifBlank { "  (no nodes)" }
      }
      command.startsWith("skills") || command.startsWith("/skills") -> {
        skills.values.joinToString("\n") { "  ${it.name}: ${it.status} \u2014 ${it.description}" }
      }
      command.startsWith("gateways") || command.startsWith("/gateways") -> {
        gateways.values.joinToString("\n") { "  ${it.key}: ${it.status} (${it.sessions} sessions)" }
      }
      command.startsWith("instances") || command.startsWith("/instances") -> {
        instances.values.joinToString("\n") { "  ${it.name}: ${it.state}${if (it.active) " [active]" else ""}" }
      }
      command.startsWith("sessions") || command.startsWith("/sessions") -> {
        sessions.values.joinToString("\n") { "  ${it.id}: ${it.type} -> ${it.target} (${it.state})" }.ifBlank { "  (no sessions)" }
      }
      command.startsWith("cron") || command.startsWith("/cron") -> {
        cronJobs.values.joinToString("\n") {
          "  ${it.id}: ${it.schedule} -> ${it.command} (${if (it.enabled) "enabled" else "disabled"})"
        }.ifBlank { "  (no cron jobs)" }
      }
      command.startsWith("providers") || command.startsWith("/providers") -> {
        gateways.values.joinToString("\n") {
          "  ${it.key}: ${it.name} [${it.status}] (${it.sessions} sessions)"
        }
      }
      command.startsWith("panic") || command.startsWith("/panic") -> {
        panic()
        "PANIC executed. All sessions canceled, gateways down."
      }
      command.startsWith("help") || command.startsWith("/help") -> {
        """Available commands:
          |  /status     \u2014 Full dashboard snapshot
          |  /config     \u2014 Current configuration
          |  /debug      \u2014 Debug state dump
          |  /nodes      \u2014 Connected OpenClaw nodes
          |  /node list  \u2014 List connected nodes
          |  /node <id> ping
          |  /node <id> run <action>
          |  /skills     \u2014 Registered skills
          |  /gateways   \u2014 Gateway statuses
          |  /instances  \u2014 Instance list
          |  /sessions   \u2014 Active sessions
          |  /cron       \u2014 Cron job list
          |  /providers  \u2014 Gateway providers
          |  /panic      \u2014 Emergency shutdown
          |  /help       \u2014 This help text""".trimMargin()
      }
      else -> "Executed: $command"
    }
    val result = CommandResult(command = command, output = output)
    appendLog("Command result: ${output.take(200)}")
    result
  }

  fun clearLogs() = synchronized(lock) {
    logs.clear()
    appendLog("Logs cleared.")
    persistLogs()
  }

  fun debugSnapshot(): Map<String, Any?> = synchronized(lock) {
    mapOf(
      "connected" to connected,
      "host" to host,
      "profile" to profile,
      "workspace" to workspace,
      "lastSync" to lastSync,
      "activeInstance" to activeInstance,
      "memoryIndexed" to memoryIndexed,
      "memoryItems" to memoryItems,
      "mode" to mode,
      "gatewayCount" to gateways.size,
      "instanceCount" to instances.size,
      "sessionCount" to sessions.size,
      "skillCount" to skills.size,
      "cronJobCount" to cronJobs.size,
      "messageCount" to messages.size,
      "logCount" to logs.size,
      "gateways" to gateways.values.toList(),
      "instances" to instances.values.toList(),
      "sessions" to sessions.values.toList(),
      "skills" to skills.values.toList(),
      "cronJobs" to cronJobs.values.toList(),
      "messages" to messages.toList(),
      "logs" to logs.toList()
    )
  }

  fun configSnapshot(): Map<String, Any?> = synchronized(lock) {
    mapOf(
      "host" to host,
      "profile" to profile,
      "workspace" to workspace,
      "connected" to connected,
      "lastSync" to lastSync
    )
  }

  private fun appendLog(message: String) {
    val line = "${Instant.now()} $message"
    logs.addLast(line)
    while (logs.size > maxLogEntries) {
      logs.removeFirst()
    }
  }

  private fun trimMessages() {
    while (messages.size > maxMessages) {
      messages.removeFirst()
    }
  }

  private fun trimSessions() {
    while (sessions.size > maxSessions) {
      val key = sessions.keys.firstOrNull() ?: break
      sessions.remove(key)
    }
  }

  private fun trimCronJobs() {
    while (cronJobs.size > maxCronJobs) {
      val key = cronJobs.keys.firstOrNull() ?: break
      cronJobs.remove(key)
    }
  }

  // ── Database binding (Gap #5) ─────────────────────────────────────

  /**
   * Wire a JDBC connection for persistent state.
   * Called from App.kt after [core.persistence.Db.open].
   */
  fun bindDb(conn: Connection) {
    synchronized(lock) {
      db = conn
      ensureTable(conn)
      loadFromDb(conn)
      startCronScheduler()
      persistAll()
      appendLog("Database bound — state loaded from disk.")
    }
  }

  private fun ensureTable(conn: Connection) {
    conn.createStatement().execute(
      """CREATE TABLE IF NOT EXISTS openclaw_kv(
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
      )"""
    )
  }

  // ── Cron scheduler (Gap #3) ───────────────────────────────────────

  private fun startCronScheduler() {
    if (cronSchedulerStarted) return
    cronSchedulerStarted = true
    cronScheduler.scheduleWithFixedDelay(
      {
        runCatching { executeDueCronJobs() }
          .onFailure { e ->
            synchronized(lock) {
              appendLog("Cron scheduler error: ${e.message ?: "unknown"}")
              persistLogs()
            }
          }
      },
      15L,
      15L,
      TimeUnit.SECONDS
    )
  }

  // ── Cron CRUD ─────────────────────────────────────────────────────

  fun listCronJobs(): List<CronJobSnapshot> = synchronized(lock) { cronJobs.values.toList() }

  fun addCronJob(schedule: String, command: String, enabled: Boolean): CronJobSnapshot? = synchronized(lock) {
    if (cronJobs.size >= maxCronJobs) return@synchronized null
    val id = "CRON-${cronCounter.incrementAndGet()}"
    val job = CronJobSnapshot(id = id, schedule = schedule, command = command, enabled = enabled)
    cronJobs[id] = job
    trimCronJobs()
    appendLog("Cron job added: $id ($schedule -> $command).")
    persistCronJobs()
    job
  }

  fun removeCronJob(id: String): Boolean = synchronized(lock) {
    val removed = cronJobs.remove(id) != null
    if (removed) {
      appendLog("Cron job removed: $id.")
      persistCronJobs()
    }
    removed
  }

  fun toggleCronJob(id: String): CronJobSnapshot? = synchronized(lock) {
    val job = cronJobs[id] ?: return null
    val updated = job.copy(enabled = !job.enabled)
    cronJobs[id] = updated
    appendLog("Cron job ${if (updated.enabled) "enabled" else "disabled"}: $id.")
    persistCronJobs()
    updated
  }

  // ── Cron executor ─────────────────────────────────────────────────

  private fun executeDueCronJobs() {
    val now = System.currentTimeMillis()
    val dueJobs = synchronized(lock) {
      cronJobs.values
        .filter { it.enabled && isCronDue(it.schedule, now, it.lastRunAt) }
        .map { it.id to it.command }
    }
    if (dueJobs.isEmpty()) return

    dueJobs.forEach { (id, command) ->
      val result = runCatching { runCommand(command).output.take(1000) }
        .getOrElse { "ERROR: ${it.message ?: "command failed"}" }
      synchronized(lock) {
        val current = cronJobs[id] ?: return@synchronized
        cronJobs[id] = current.copy(
          lastRunAt = now,
          lastResult = result
        )
        appendLog("Cron job executed: $id")
        persistCronJobs()
      }
    }
  }

  // ── Cron parser ───────────────────────────────────────────────────

  private fun isCronDue(schedule: String, nowMs: Long, lastRunAt: Long?): Boolean {
    val currentMinute = nowMs / 60_000L
    if (lastRunAt != null && (lastRunAt / 60_000L) == currentMinute) return false

    val trimmed = schedule.trim()
    if (trimmed.isBlank()) return false

    val everyPrefix = "@every "
    if (trimmed.startsWith(everyPrefix, ignoreCase = true)) {
      val durationMs = parseDurationMillis(trimmed.substring(everyPrefix.length)) ?: return false
      if (durationMs <= 0L) return false
      return lastRunAt == null || nowMs - lastRunAt >= durationMs
    }

    val parts = trimmed.split(Regex("\\s+"))
    if (parts.size != 5) return false

    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val minute = cal.get(Calendar.MINUTE)
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
    val month = cal.get(Calendar.MONTH) + 1
    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday ... 6=Saturday

    return cronFieldMatches(parts[0], minute, 0, 59) &&
      cronFieldMatches(parts[1], hour, 0, 23) &&
      cronFieldMatches(parts[2], dayOfMonth, 1, 31) &&
      cronFieldMatches(parts[3], month, 1, 12) &&
      cronFieldMatches(parts[4], dayOfWeek, 0, 6, allowSevenAsSunday = true)
  }

  private fun parseDurationMillis(value: String): Long? {
    val raw = value.trim().lowercase()
    if (raw.isBlank()) return null
    val match = Regex("^(\\d+)(ms|s|m|h|d)$").matchEntire(raw) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    val unit = match.groupValues[2]
    return when (unit) {
      "ms" -> amount
      "s" -> amount * 1_000L
      "m" -> amount * 60_000L
      "h" -> amount * 3_600_000L
      "d" -> amount * 86_400_000L
      else -> null
    }
  }

  private fun cronFieldMatches(
    field: String,
    value: Int,
    min: Int,
    max: Int,
    allowSevenAsSunday: Boolean = false
  ): Boolean {
    if (field == "*") return true
    val parts = field.split(",")
    return parts.any { token ->
      cronTokenMatches(token.trim(), value, min, max, allowSevenAsSunday)
    }
  }

  private fun cronTokenMatches(
    token: String,
    value: Int,
    min: Int,
    max: Int,
    allowSevenAsSunday: Boolean
  ): Boolean {
    if (token.isBlank()) return false

    if (token.contains("/")) {
      val segments = token.split("/", limit = 2)
      if (segments.size != 2) return false
      val step = segments[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
      val base = segments[0]
      val range = parseBaseRange(base, min, max, allowSevenAsSunday) ?: return false
      if (value !in range) return false
      return ((value - range.first) % step) == 0
    }

    if (token.contains("-")) {
      val range = parseRangeToken(token, min, max, allowSevenAsSunday) ?: return false
      return value in range
    }

    val parsed = parseFieldNumber(token, allowSevenAsSunday) ?: return false
    return parsed in min..max && parsed == value
  }

  private fun parseBaseRange(base: String, min: Int, max: Int, allowSevenAsSunday: Boolean): IntRange? {
    if (base == "*") return min..max
    if (base.contains("-")) return parseRangeToken(base, min, max, allowSevenAsSunday)
    val start = parseFieldNumber(base, allowSevenAsSunday) ?: return null
    if (start !in min..max) return null
    return start..max
  }

  private fun parseRangeToken(token: String, min: Int, max: Int, allowSevenAsSunday: Boolean): IntRange? {
    val segments = token.split("-", limit = 2)
    if (segments.size != 2) return null
    val start = parseFieldNumber(segments[0], allowSevenAsSunday) ?: return null
    val end = parseFieldNumber(segments[1], allowSevenAsSunday) ?: return null
    if (start !in min..max || end !in min..max || end < start) return null
    return start..end
  }

  private fun parseFieldNumber(token: String, allowSevenAsSunday: Boolean): Int? {
    val parsed = token.toIntOrNull() ?: return null
    if (allowSevenAsSunday && parsed == 7) return 0
    return parsed
  }

  // ── JDBC Persistence (Gap #5) ─────────────────────────────────────

  private fun writeKv(key: String, value: String) {
    val conn = db ?: return
    try {
      conn.prepareStatement(
        "INSERT OR REPLACE INTO openclaw_kv(key, value) VALUES (?, ?)"
      ).use { ps ->
        ps.setString(1, key)
        ps.setString(2, value)
        ps.executeUpdate()
      }
    } catch (_: Exception) {
      // Best-effort persistence
    }
  }

  private fun readKv(conn: Connection, key: String): String? {
    return try {
      conn.prepareStatement("SELECT value FROM openclaw_kv WHERE key=?").use { ps ->
        ps.setString(1, key)
        ps.executeQuery().use { rs ->
          if (rs.next()) rs.getString(1) else null
        }
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun persistAll() {
    persistConfig()
    persistGateways()
    persistInstances()
    persistSessions()
    persistSkills()
    persistMessages()
    persistCronJobs()
    persistLogs()
  }

  private fun persistConfig() {
    if (db == null) return
    try {
      writeKv("oc_connected", connected.toString())
      writeKv("oc_host", host.orEmpty())
      writeKv("oc_profile", profile)
      writeKv("oc_workspace", workspace)
      writeKv("oc_lastSync", (lastSync ?: 0L).toString())
      writeKv("oc_activeInstance", activeInstance.orEmpty())
      writeKv("oc_mode", mode)
    } catch (_: Exception) {}
  }

  private fun persistGateways() {
    if (db == null) return
    try {
      writeKv("oc_gateways", json.encodeToString<List<GatewayStatus>>(gateways.values.toList()))
    } catch (_: Exception) {}
  }

  private fun persistInstances() {
    if (db == null) return
    try {
      writeKv("oc_instances", json.encodeToString<List<InstanceSnapshot>>(instances.values.toList()))
    } catch (_: Exception) {}
  }

  private fun persistSessions() {
    if (db == null) return
    try {
      writeKv("oc_sessions", json.encodeToString<List<SessionSnapshot>>(sessions.values.toList()))
    } catch (_: Exception) {}
  }

  private fun persistSkills() {
    if (db == null) return
    try {
      writeKv("oc_skills", json.encodeToString<List<SkillSnapshot>>(skills.values.toList()))
    } catch (_: Exception) {}
  }

  private fun persistMessages() {
    if (db == null) return
    try {
      writeKv("oc_messages", json.encodeToString<List<MessageSnapshot>>(messages.toList()))
    } catch (_: Exception) {}
  }

  private fun persistCronJobs() {
    if (db == null) return
    try {
      writeKv("oc_cron_jobs", json.encodeToString<List<CronJobSnapshot>>(cronJobs.values.toList()))
    } catch (_: Exception) {}
  }

  private fun persistLogs() {
    if (db == null) return
    try {
      writeKv("oc_logs", json.encodeToString<List<String>>(logs.toList()))
    } catch (_: Exception) {}
  }

  private fun loadFromDb(conn: Connection) {
    try {
      val tableExists = conn.createStatement().executeQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='openclaw_kv'"
      ).use { rs -> rs.next() }
      if (!tableExists) return

      readKv(conn, "oc_connected")?.toBooleanStrictOrNull()?.let { connected = it }
      readKv(conn, "oc_host")?.ifBlank { null }?.let { host = it }
      readKv(conn, "oc_profile")?.let { profile = it }
      readKv(conn, "oc_workspace")?.let { workspace = it }
      readKv(conn, "oc_lastSync")?.toLongOrNull()?.takeIf { it > 0 }?.let { lastSync = it }
      readKv(conn, "oc_activeInstance")?.ifBlank { null }?.let { activeInstance = it }
      readKv(conn, "oc_mode")?.let { mode = it }

      readKv(conn, "oc_gateways")?.let { raw ->
        runCatching { json.decodeFromString<List<GatewayStatus>>(raw) }.getOrNull()
          ?.forEach { entry -> gateways[entry.key] = entry }
      }
      readKv(conn, "oc_instances")?.let { raw ->
        runCatching { json.decodeFromString<List<InstanceSnapshot>>(raw) }.getOrNull()
          ?.forEach { entry -> instances[entry.name] = entry }
      }
      readKv(conn, "oc_sessions")?.let { raw ->
        runCatching { json.decodeFromString<List<SessionSnapshot>>(raw) }.getOrNull()
          ?.forEach { entry -> sessions[entry.id] = entry }
      }
      readKv(conn, "oc_skills")?.let { raw ->
        runCatching { json.decodeFromString<List<SkillSnapshot>>(raw) }.getOrNull()
          ?.forEach { entry -> skills[entry.name] = entry }
      }
      readKv(conn, "oc_messages")?.let { raw ->
        runCatching { json.decodeFromString<List<MessageSnapshot>>(raw) }.getOrNull()
          ?.forEach { entry -> messages.addLast(entry) }
      }
      readKv(conn, "oc_cron_jobs")?.let { raw ->
        runCatching { json.decodeFromString<List<CronJobSnapshot>>(raw) }.getOrNull()
          ?.forEach { entry -> cronJobs[entry.id] = entry }
      }
      readKv(conn, "oc_logs")?.let { raw ->
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
          ?.forEach { entry -> logs.addLast(entry) }
      }
    } catch (_: Exception) {
      // Best-effort; fresh state on error.
    }
  }
}
