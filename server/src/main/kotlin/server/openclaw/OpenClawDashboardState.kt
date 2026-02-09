package server.openclaw

import java.time.Instant
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.serialization.Serializable

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

  fun runCommand(command: String): CommandResult = synchronized(lock) {
    appendLog("Command executed: $command.")
    CommandResult(command = command, output = "Queued command: $command")
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
