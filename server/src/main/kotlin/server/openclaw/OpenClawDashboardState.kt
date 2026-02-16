package server.openclaw

import java.time.Instant
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.serialization.Serializable
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

object OpenClawDashboardState {
  private val lock = Any()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val gateways = LinkedHashMap<String, GatewayStatus>()
  private val instances = LinkedHashMap<String, InstanceSnapshot>()
  private val sessions = LinkedHashMap<String, SessionSnapshot>()
  private val skills = LinkedHashMap<String, SkillSnapshot>()
  private val logs = ArrayDeque<String>()
  private val messages = ArrayDeque<MessageSnapshot>()
  private val sessionCounter = AtomicLong(1026)
  private val messageCounter = AtomicLong(1)
  private val maxLogEntries = 200
  private val maxMessages = 200
  private val maxSessions = 200

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
      SkillSnapshot("rtsp_probe", "Probe RTSP endpoints for availability.", "idle")
    ).forEach { skills[it.name] = it }

    appendLog("OpenClaw dashboard state initialized.")
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
    snapshot()
  }

  fun disconnect(): DashboardSnapshot = synchronized(lock) {
    connected = false
    appendLog("Gateway disconnected.")
    snapshot()
  }

  fun refresh(): DashboardSnapshot = synchronized(lock) {
    lastSync = System.currentTimeMillis()
    appendLog("Refresh requested.")
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
    snapshot()
  }

  fun restartGateway(key: String): GatewayStatus? = synchronized(lock) {
    val gateway = gateways[key] ?: return null
    val updated = gateway.copy(status = "up", sessions = 0, lastPingMs = null)
    gateways[key] = updated
    appendLog("Gateway restart requested: $key.")
    updated
  }

  fun pingGateway(key: String): GatewayPingResult? = synchronized(lock) {
    val gateway = gateways[key] ?: return null
    val latency = Random.nextLong(18, 140)
    val updated = gateway.copy(lastPingMs = latency)
    gateways[key] = updated
    appendLog("Gateway ping: $key (${latency}ms).")
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
    instance
  }

  fun selectInstance(name: String): InstanceSnapshot? = synchronized(lock) {
    val instance = instances[name] ?: return null
    instances.replaceAll { _, entry -> entry.copy(active = entry.name == name) }
    activeInstance = name
    appendLog("Instance selected: $name.")
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
    updated
  }

  fun stopInstance(name: String): InstanceSnapshot? = synchronized(lock) {
    val instance = instances[name] ?: return null
    val updated = instance.copy(state = "stopped")
    instances[name] = updated
    appendLog("Instance stopped: $name.")
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
    session
  }

  fun cancelSession(id: String): SessionSnapshot? = synchronized(lock) {
    val session = sessions[id] ?: return null
    val updated = session.copy(state = "canceled", updatedAt = System.currentTimeMillis())
    sessions[id] = updated
    appendLog("Session canceled: $id.")
    updated
  }

  fun refreshSkills(): List<SkillSnapshot> = synchronized(lock) {
    skills.replaceAll { _, entry -> entry.copy(status = "idle") }
    appendLog("Skills refreshed.")
    skills.values.toList()
  }

  fun invokeSkill(name: String): SkillSnapshot? = synchronized(lock) {
    val skill = skills[name] ?: return null
    val updated = skill.copy(status = "running")
    skills[name] = updated
    appendLog("Skill invoked: $name.")
    updated
  }

  fun setMode(newMode: String): String = synchronized(lock) {
    mode = newMode
    appendLog("Mode set: $newMode.")
    mode
  }

  fun updateConfig(newHost: String?, newProfile: String?, newWorkspace: String?): DashboardSnapshot = synchronized(lock) {
    if (!newHost.isNullOrBlank()) host = newHost.trim()
    if (!newProfile.isNullOrBlank()) profile = newProfile.trim()
    if (!newWorkspace.isNullOrBlank()) workspace = newWorkspace.trim()
    appendLog("Configuration updated.")
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

  fun runCommand(command: String): CommandResult = synchronized(lock) {
    appendLog("Command: $command")
    val output = when {
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
      command.startsWith("panic") || command.startsWith("/panic") -> {
        panic()
        "PANIC executed. All sessions canceled, gateways down."
      }
      command.startsWith("help") || command.startsWith("/help") -> {
        """Available commands:
          |  /status    \u2014 Full dashboard snapshot
          |  /config    \u2014 Current configuration
          |  /debug     \u2014 Debug state dump
          |  /nodes     \u2014 Connected OpenClaw nodes
          |  /skills    \u2014 Registered skills
          |  /gateways  \u2014 Gateway statuses
          |  /instances \u2014 Instance list
          |  /sessions  \u2014 Active sessions
          |  /panic     \u2014 Emergency shutdown
          |  /help      \u2014 This help text""".trimMargin()
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
      "gateways" to gateways.values.toList(),
      "instances" to instances.values.toList(),
      "sessions" to sessions.values.toList(),
      "skills" to skills.values.toList(),
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
}
