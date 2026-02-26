package com.netninja.openclaw

import kotlinx.serialization.Serializable
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

class SkillExecutor(private val handler: (String) -> String?) {
  fun execute(name: String): String? = handler(name)
  operator fun invoke(name: String): String? = handler(name)
}

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
data class CronJobSnapshot(
  val id: String,
  val schedule: String,
  val command: String,
  val enabled: Boolean,
  val lastRunAt: Long? = null,
  val lastResult: String? = null
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
data class ConfigSnapshot(
  val host: String?,
  val profile: String,
  val workspace: String,
  val mode: String
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

class OpenClawDashboardState(@Suppress("UNUSED_PARAMETER") db: Any? = null) {
  private val lock = Any()
  private val logs = ArrayDeque<String>()
  private val messages = ArrayDeque<MessageSnapshot>()
  private val gateways = LinkedHashMap<String, GatewayStatus>()
  private val instances = LinkedHashMap<String, InstanceSnapshot>()
  private val sessions = LinkedHashMap<String, SessionSnapshot>()
  private val skills = LinkedHashMap<String, SkillSnapshot>()
  private val cronJobs = LinkedHashMap<String, CronJobSnapshot>()
  private val messageCounter = AtomicLong(1)
  private val sessionCounter = AtomicLong(1000)
  private val cronCounter = AtomicLong(1)

  private var skillExecutor: SkillExecutor? = null
  private var connected = true
  private var host: String? = null
  private var profile = "default"
  private var workspace = "~/.openclaw/workspace"
  private var lastSync: Long? = System.currentTimeMillis()
  private var activeInstance: String? = "default"
  private var mode = "safe"

  init {
    gateways["whatsapp"] = GatewayStatus("whatsapp", "WhatsApp", "down", 0)
    gateways["telegram"] = GatewayStatus("telegram", "Telegram", "down", 0)
    gateways["discord"] = GatewayStatus("discord", "Discord", "down", 0)
    gateways["imessage"] = GatewayStatus("imessage", "iMessage", "down", 0)

    instances["default"] = InstanceSnapshot("default", "default", workspace, sandbox = false, access = "rw", state = "stopped", active = true)

    skills["scan_network"] = SkillSnapshot("scan_network", "Scan subnet for OpenClaw nodes.", "idle")
    skills["onvif_discovery"] = SkillSnapshot("onvif_discovery", "Discover ONVIF cameras on the LAN.", "idle")
    skills["rtsp_probe"] = SkillSnapshot("rtsp_probe", "Probe RTSP endpoints for availability.", "idle")
    skills["port_scan"] = SkillSnapshot("port_scan", "Deep port scan on a target host.", "idle")
    skills["wol_broadcast"] = SkillSnapshot("wol_broadcast", "Send Wake-on-LAN magic packets.", "idle")
  }

  fun initialize() = Unit

  fun setSkillExecutor(executor: SkillExecutor) {
    synchronized(lock) { skillExecutor = executor }
  }

  fun snapshot(): DashboardSnapshot = synchronized(lock) {
    DashboardSnapshot(
      connected = connected,
      host = host,
      profile = profile,
      workspace = workspace,
      lastSync = lastSync,
      activeInstance = activeInstance,
      memoryIndexed = false,
      memoryItems = 0,
      gateways = gateways.values.toList(),
      instances = instances.values.toList(),
      sessions = sessions.values.toList(),
      skills = skills.values.toList(),
      mode = mode
    )
  }

  fun connect(newHost: String?): DashboardSnapshot = synchronized(lock) {
    connected = true
    host = newHost?.trim()?.ifBlank { host } ?: host
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
    sessions.replaceAll { _, s -> s.copy(state = "canceled", updatedAt = System.currentTimeMillis()) }
    appendLog("Panic triggered.")
    snapshot()
  }

  fun listGateways(): List<GatewayStatus> = synchronized(lock) { gateways.values.toList() }

  fun restartGateway(key: String): GatewayStatus? = synchronized(lock) {
    val current = gateways[key] ?: return null
    val updated = current.copy(status = "up", sessions = 0, lastPingMs = null)
    gateways[key] = updated
    appendLog("Gateway restarted: $key")
    updated
  }

  fun pingGateway(key: String): GatewayPingResult? = synchronized(lock) {
    val current = gateways[key] ?: return null
    val latency = (20L..150L).random()
    gateways[key] = current.copy(lastPingMs = latency)
    GatewayPingResult(key = key, status = gateways[key]?.status ?: "unknown", latencyMs = latency)
  }

  fun listInstances(): List<InstanceSnapshot> = synchronized(lock) { instances.values.toList() }

  fun addInstance(name: String, profile: String?, workspace: String?, sandbox: Boolean?, access: String?): InstanceSnapshot? =
    synchronized(lock) {
      if (name.isBlank() || instances.containsKey(name)) return null
      val i = InstanceSnapshot(
        name = name,
        profile = profile?.ifBlank { this.profile } ?: this.profile,
        workspace = workspace?.ifBlank { this.workspace } ?: this.workspace,
        sandbox = sandbox ?: false,
        access = access?.ifBlank { "rw" } ?: "rw",
        state = "stopped",
        active = false
      )
      instances[name] = i
      appendLog("Instance added: $name")
      i
    }

  fun selectInstance(name: String): InstanceSnapshot? = synchronized(lock) {
    val existing = instances[name] ?: return null
    instances.replaceAll { _, entry -> entry.copy(active = entry.name == name) }
    activeInstance = name
    existing.copy(active = true).also { instances[name] = it }
  }

  fun activateInstance(name: String): InstanceSnapshot? = synchronized(lock) {
    val existing = instances[name] ?: return null
    val updated = existing.copy(state = "active", active = true)
    instances[name] = updated
    instances.replaceAll { key, entry -> if (key == name) instances[key]!! else entry.copy(active = false) }
    activeInstance = name
    updated
  }

  fun stopInstance(name: String): InstanceSnapshot? = synchronized(lock) {
    val existing = instances[name] ?: return null
    val updated = existing.copy(state = "stopped")
    instances[name] = updated
    updated
  }

  fun listSessions(): List<SessionSnapshot> = synchronized(lock) { sessions.values.toList() }

  fun createSession(type: String, target: String, payload: String?): SessionSnapshot = synchronized(lock) {
    val now = System.currentTimeMillis()
    val id = "session-${sessionCounter.incrementAndGet()}"
    val session = SessionSnapshot(id = id, type = type, target = target, state = "queued", payload = payload, createdAt = now, updatedAt = now)
    sessions[id] = session
    session
  }

  fun cancelSession(id: String): SessionSnapshot? = synchronized(lock) {
    val existing = sessions[id] ?: return null
    val updated = existing.copy(state = "canceled", updatedAt = System.currentTimeMillis())
    sessions[id] = updated
    updated
  }

  fun listSkills(): List<SkillSnapshot> = synchronized(lock) { skills.values.toList() }

  fun refreshSkills(): List<SkillSnapshot> = synchronized(lock) { skills.values.toList() }

  fun invokeSkill(name: String, executor: SkillExecutor = skillExecutor ?: SkillExecutor { null }): SkillSnapshot? =
    synchronized(lock) {
      val existing = skills[name] ?: return null
      val result = executor.execute(name)
      val updated = existing.copy(status = if (result.isNullOrBlank()) "completed" else "completed: $result")
      skills[name] = updated
      appendLog("Skill invoked: $name")
      updated
    }

  fun setMode(newMode: String): String = synchronized(lock) {
    mode = newMode.trim().ifBlank { "safe" }
    mode
  }

  fun configSnapshot(): ConfigSnapshot = synchronized(lock) {
    ConfigSnapshot(host = host, profile = profile, workspace = workspace, mode = mode)
  }

  fun updateConfig(host: String?, profile: String?, workspace: String?): ConfigSnapshot = synchronized(lock) {
    if (!host.isNullOrBlank()) this.host = host.trim()
    if (!profile.isNullOrBlank()) this.profile = profile.trim()
    if (!workspace.isNullOrBlank()) this.workspace = workspace.trim()
    configSnapshot()
  }

  fun debugSnapshot(): Map<String, Any?> = synchronized(lock) {
    mapOf(
      "connected" to connected,
      "host" to host,
      "mode" to mode,
      "gateways" to gateways.size,
      "instances" to instances.size,
      "sessions" to sessions.size,
      "skills" to skills.size,
      "messages" to messages.size
    )
  }

  fun listLogs(): List<String> = synchronized(lock) { logs.toList() }

  fun clearLogs() {
    synchronized(lock) { logs.clear() }
  }

  fun listMessages(): List<MessageSnapshot> = synchronized(lock) { messages.toList() }

  fun addMessage(gateway: String, channel: String, body: String): MessageSnapshot = synchronized(lock) {
    val msg = MessageSnapshot(
      id = "msg-${messageCounter.incrementAndGet()}",
      gateway = gateway,
      channel = channel,
      body = body,
      sentAt = System.currentTimeMillis()
    )
    messages.addFirst(msg)
    while (messages.size > 200) messages.removeLast()
    msg
  }

  fun runCommand(command: String, @Suppress("UNUSED_PARAMETER") server: Any): CommandResult =
    CommandResult(command = command, output = "Executed: $command")

  fun addChatMessage(body: String, channel: String): MessageSnapshot =
    addMessage(gateway = "local", channel = channel, body = body)

  fun listCronJobs(): List<CronJobSnapshot> = synchronized(lock) { cronJobs.values.toList() }

  fun addCronJob(schedule: String, command: String, enabled: Boolean): CronJobSnapshot = synchronized(lock) {
    val id = "cron-${cronCounter.incrementAndGet()}"
    val job = CronJobSnapshot(id = id, schedule = schedule, command = command, enabled = enabled)
    cronJobs[id] = job
    job
  }

  fun toggleCronJob(id: String): CronJobSnapshot? = synchronized(lock) {
    val existing = cronJobs[id] ?: return null
    val updated = existing.copy(enabled = !existing.enabled)
    cronJobs[id] = updated
    updated
  }

  fun removeCronJob(id: String): Boolean = synchronized(lock) { cronJobs.remove(id) != null }

  private fun appendLog(line: String) {
    logs.addFirst("${System.currentTimeMillis()}: $line")
    while (logs.size > 200) logs.removeLast()
  }
}

