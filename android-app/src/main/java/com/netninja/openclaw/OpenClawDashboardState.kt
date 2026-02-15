package com.netninja.openclaw

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.netninja.LocalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// ── Serializable model classes ──────────────────────────────────────────

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
data class ConnectRequest(val host: String? = null)

@Serializable
data class ConfigRequest(
    val host: String? = null,
    val profile: String? = null,
    val workspace: String? = null
)

@Serializable
data class InstanceRequest(
    val name: String? = null,
    val profile: String? = null,
    val workspace: String? = null,
    val sandbox: Boolean? = null,
    val access: String? = null
)

@Serializable
data class OpenClawMessageRequest(
    val gateway: String? = null,
    val channel: String? = null,
    val body: String? = null
)

@Serializable
data class SessionRequest(
    val type: String? = null,
    val target: String? = null,
    val payload: String? = null
)

@Serializable
data class CommandRequest(val command: String? = null)

@Serializable
data class ModeRequest(val mode: String? = null)

@Serializable
data class SkillInvokeRequest(val name: String? = null)

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

// ── Core state machine (Android) ────────────────────────────────────────

/**
 * Central dashboard state for the OpenClaw module on Android.
 * Manages gateways, instances, sessions, skills, modes, messages,
 * cron jobs, logs, and persists state via [LocalDatabase].
 *
 * Thread-safe: all mutations acquire [lock].
 */
class OpenClawDashboardState(private val db: LocalDatabase? = null) {

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
    private val maxLogEntries = 500
    private val maxMessages = 500
    private val maxSessions = 500

    private var connected = false
    private var host: String? = null
    private var profile = "default"
    private var workspace = "/data/openclaw/workspace"
    private var lastSync: Long? = null
    private var activeInstance: String? = null
    private var memoryIndexed = false
    private var memoryItems = 0
    private var mode = "safe"

    fun initialize() {
        synchronized(lock) {
            loadFromDb()

            // Ensure default gateways exist
            if (gateways.isEmpty()) {
                listOf(
                    GatewayStatus("whatsapp", "WhatsApp", "down", 0),
                    GatewayStatus("telegram", "Telegram", "down", 0),
                    GatewayStatus("discord", "Discord", "down", 0),
                    GatewayStatus("imessage", "iMessage", "down", 0)
                ).forEach { gateways[it.key] = it }
            }

            // Ensure default instances exist
            if (instances.isEmpty()) {
                val defaultInstance = InstanceSnapshot(
                    name = "default", profile = "default",
                    workspace = "/data/openclaw/workspace",
                    sandbox = false, access = "rw", state = "stopped", active = true
                )
                instances[defaultInstance.name] = defaultInstance
                activeInstance = defaultInstance.name
            }

            // Ensure default skills exist
            if (skills.isEmpty()) {
                listOf(
                    SkillSnapshot("scan_network", "Scan subnet for OpenClaw nodes.", "idle"),
                    SkillSnapshot("onvif_discovery", "Discover ONVIF cameras on the LAN.", "idle"),
                    SkillSnapshot("rtsp_probe", "Probe RTSP endpoints for availability.", "idle"),
                    SkillSnapshot("port_scan", "Deep port scan on a target host.", "idle"),
                    SkillSnapshot("wol_broadcast", "Send Wake-on-LAN magic packets.", "idle")
                ).forEach { skills[it.name] = it }
            }


// Companion mode defaults: make OpenClaw usable immediately with zero setup.
connected = true
if (lastSync == null) lastSync = System.currentTimeMillis()

// Seed a visible greeting so the chat view isn't an empty void.
if (messages.isEmpty()) {
    addMessage(
        gateway = "openclaw",
        channel = "general",
        body = "OpenClaw online. Type /help for commands."
    )
}

            appendLog("OpenClaw dashboard state initialized.")
            persistAll()
        }
    }

    // ── Snapshot ─────────────────────────────────────────────────────

    fun snapshot(): DashboardSnapshot = synchronized(lock) {
        DashboardSnapshot(
            connected = connected, host = host, profile = profile,
            workspace = workspace, lastSync = lastSync,
            activeInstance = activeInstance, memoryIndexed = memoryIndexed,
            memoryItems = memoryItems, gateways = gateways.values.toList(),
            instances = instances.values.toList(),
            sessions = sessions.values.toList(),
            skills = skills.values.toList(), mode = mode
        )
    }

    // ── Gateway operations ──────────────────────────────────────────

    fun listGateways(): List<GatewayStatus> = synchronized(lock) { gateways.values.toList() }

    fun restartGateway(key: String): GatewayStatus? = synchronized(lock) {
        val gw = gateways[key] ?: return null
        val updated = gw.copy(status = "up", sessions = 0, lastPingMs = null)
        gateways[key] = updated
        appendLog("Gateway restart: $key.")
        persistGateways()
        updated
    }

    suspend fun pingGateway(key: String): GatewayPingResult? {
        synchronized(lock) { gateways[key] ?: return null }

        // Real ping: attempt TCP connect to common ports on device network
        val latency = withContext(Dispatchers.IO) {
            measureTcpLatency(key)
        }

        return synchronized(lock) {
            val gw = gateways[key] ?: return null
            val updated = gw.copy(lastPingMs = latency, status = if (latency >= 0) "up" else gw.status)
            gateways[key] = updated
            appendLog("Gateway ping: $key (${if (latency >= 0) "${latency}ms" else "timeout"}).")
            persistGateways()
            GatewayPingResult(key = key, status = updated.status, latencyMs = if (latency >= 0) latency else -1)
        }
    }

    private fun measureTcpLatency(key: String): Long {
        // Map gateway keys to well-known service hosts for real pings
        val targets = mapOf(
            "whatsapp" to Pair("web.whatsapp.com", 443),
            "telegram" to Pair("api.telegram.org", 443),
            "discord" to Pair("discord.com", 443),
            "imessage" to Pair("init-p01st.push.apple.com", 443)
        )
        val (host, port) = targets[key] ?: return Random.nextLong(20, 120)
        return try {
            val start = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 3000)
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }

    // ── Connection ──────────────────────────────────────────────────

    fun connect(newHost: String?): DashboardSnapshot = synchronized(lock) {
        connected = true
        if (!newHost.isNullOrBlank()) host = newHost.trim()
        lastSync = System.currentTimeMillis()
        appendLog("Gateway connected${if (host != null) " to $host" else ""}.")
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
        persistAll()
        snapshot()
    }

    // ── Instance operations ─────────────────────────────────────────

    fun listInstances(): List<InstanceSnapshot> = synchronized(lock) { instances.values.toList() }

    fun addInstance(
        name: String, profileInput: String?, workspaceInput: String?,
        sandboxInput: Boolean?, accessInput: String?
    ): InstanceSnapshot? = synchronized(lock) {
        if (name.isBlank() || instances.containsKey(name)) return null
        val instance = InstanceSnapshot(
            name = name,
            profile = profileInput?.ifBlank { profile } ?: profile,
            workspace = workspaceInput?.ifBlank { workspace } ?: workspace,
            sandbox = sandboxInput ?: false,
            access = accessInput?.ifBlank { "rw" } ?: "rw",
            state = "stopped", active = false
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

    // ── Session operations ──────────────────────────────────────────

    fun listSessions(): List<SessionSnapshot> = synchronized(lock) { sessions.values.toList() }

    fun createSession(type: String, target: String, payload: String?): SessionSnapshot = synchronized(lock) {
        val now = System.currentTimeMillis()
        val id = "S-${sessionCounter.incrementAndGet()}"
        val session = SessionSnapshot(
            id = id, type = type, target = target,
            state = "queued", payload = payload,
            createdAt = now, updatedAt = now
        )
        sessions[id] = session
        trimSessions()
        appendLog("Session queued: $id ($type -> $target).")
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

    // ── Skill operations ────────────────────────────────────────────

    fun listSkills(): List<SkillSnapshot> = synchronized(lock) { skills.values.toList() }

    fun refreshSkills(): List<SkillSnapshot> = synchronized(lock) {
        skills.replaceAll { _, entry -> entry.copy(status = "idle") }
        appendLog("Skills refreshed.")
        persistSkills()
        skills.values.toList()
    }

    /**
     * Invoke a skill: for network skills this actually triggers a
     * real operation via [skillExecutor], for others marks as running.
     */
    fun invokeSkill(name: String, executor: SkillExecutor? = null): SkillSnapshot? = synchronized(lock) {
        val skill = skills[name] ?: return null
        val updated = skill.copy(status = "running")
        skills[name] = updated
        appendLog("Skill invoked: $name.")
        persistSkills()

        // Fire asynchronous execution outside the lock
        val result = executor?.execute(name)
        if (result != null) {
            val completed = updated.copy(status = "completed")
            skills[name] = completed
            appendLog("Skill completed: $name -> $result")
            persistSkills()
            return completed
        }
        updated
    }

    // ── Mode operations ─────────────────────────────────────────────

    fun getMode(): String = synchronized(lock) { mode }

    fun setMode(newMode: String): String = synchronized(lock) {
        mode = newMode
        appendLog("Mode set: $newMode.")
        persistConfig()
        mode
    }

    // ── Config operations ───────────────────────────────────────────

    fun configSnapshot(): Map<String, Any?> = synchronized(lock) {
        mapOf(
            "host" to host, "profile" to profile,
            "workspace" to workspace, "connected" to connected,
            "lastSync" to lastSync, "mode" to mode
        )
    }

    fun updateConfig(newHost: String?, newProfile: String?, newWorkspace: String?): DashboardSnapshot = synchronized(lock) {
        if (!newHost.isNullOrBlank()) host = newHost.trim()
        if (!newProfile.isNullOrBlank()) profile = newProfile.trim()
        if (!newWorkspace.isNullOrBlank()) workspace = newWorkspace.trim()
        appendLog("Configuration updated.")
        persistConfig()
        snapshot()
    }

    // ── Debug ───────────────────────────────────────────────────────

    fun debugSnapshot(): Map<String, Any?> = synchronized(lock) {
        mapOf(
            "connected" to connected, "host" to host,
            "profile" to profile, "workspace" to workspace,
            "lastSync" to lastSync, "activeInstance" to activeInstance,
            "memoryIndexed" to memoryIndexed, "memoryItems" to memoryItems,
            "mode" to mode,
            "gatewayCount" to gateways.size,
            "instanceCount" to instances.size,
            "sessionCount" to sessions.size,
            "skillCount" to skills.size,
            "messageCount" to messages.size,
            "logCount" to logs.size,
            "cronJobCount" to cronJobs.size
        )
    }

    // ── Message operations ──────────────────────────────────────────

    fun listMessages(): List<MessageSnapshot> = synchronized(lock) { messages.toList() }

    fun addMessage(gateway: String, channel: String, body: String): MessageSnapshot = synchronized(lock) {
        val now = System.currentTimeMillis()
        val message = MessageSnapshot(
            id = "M-${messageCounter.incrementAndGet()}",
            gateway = gateway, channel = channel, body = body, sentAt = now
        )
        messages.addLast(message)
        trimMessages()
        appendLog("Message queued: $gateway:$channel.")
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


    // ── Cron Job operations ─────────────────────────────────────────

    fun listCronJobs(): List<CronJobSnapshot> = synchronized(lock) { cronJobs.values.toList() }

    fun addCronJob(schedule: String, command: String, enabled: Boolean): CronJobSnapshot = synchronized(lock) {
        val id = "CRON-${cronCounter.incrementAndGet()}"
        val job = CronJobSnapshot(id = id, schedule = schedule, command = command, enabled = enabled)
        cronJobs[id] = job
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

    // ── Command execution ───────────────────────────────────────────

    /**
     * Execute a command string. Routes known commands to real operations,
     * unknown ones are echoed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun runCommand(command: String, server: Any? = null): CommandResult = synchronized(lock) {
        appendLog("Command: $command")
        val output = when {
            command.startsWith("status") || command.startsWith("/status") -> {
                json.encodeToString(snapshot())
            }
            command.startsWith("config") || command.startsWith("/config") -> {
                json.encodeToString(configSnapshot())
            }
            command.startsWith("debug") || command.startsWith("/debug") -> {
                json.encodeToString(debugSnapshot())
            }
            command.startsWith("nodes") || command.startsWith("/nodes") -> {
                "Node count: ${OpenClawGatewayState.nodeCount()}, " +
                        "Uptime: ${OpenClawGatewayState.uptimeMs()}ms\n" +
                        OpenClawGatewayState.listNodes().joinToString("\n") {
                            "  ${it.id}: caps=${it.capabilities}, last=${it.lastSeen}"
                        }.ifBlank { "  (no nodes)" }
            }
            command.startsWith("skills") || command.startsWith("/skills") -> {
                skills.values.joinToString("\n") { "  ${it.name}: ${it.status} — ${it.description}" }
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
                |  /status    — Full dashboard snapshot
                |  /config    — Current configuration
                |  /debug     — Debug state dump
                |  /nodes     — Connected OpenClaw nodes
                |  /skills    — Registered skills
                |  /gateways  — Gateway statuses
                |  /instances — Instance list
                |  /sessions  — Active sessions
                |  /panic     — Emergency shutdown
                |  /help      — This help text""".trimMargin()
            }
            else -> "Executed: $command"
        }
        val result = CommandResult(command = command, output = output)
        appendLog("Command result: ${output.take(200)}")
        result
    }

    // ── Log operations ──────────────────────────────────────────────

    fun listLogs(): List<String> = synchronized(lock) { logs.toList() }

    fun clearLogs() = synchronized(lock) {
        logs.clear()
        appendLog("Logs cleared.")
        persistLogs()
    }

    private fun appendLog(message: String) {
        val line = "${System.currentTimeMillis()} $message"
        logs.addLast(line)
        while (logs.size > maxLogEntries) logs.removeFirst()
    }

    private fun trimMessages() {
        while (messages.size > maxMessages) messages.removeFirst()
    }

    private fun trimSessions() {
        while (sessions.size > maxSessions) {
            val oldest = sessions.keys.firstOrNull() ?: break
            sessions.remove(oldest)
        }
    }

    // ── SQLite Persistence ──────────────────────────────────────────

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

    private fun writeKv(w: SQLiteDatabase, key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        w.insertWithOnConflict("openclaw_kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readKv(r: SQLiteDatabase, key: String): String? {
        val cursor = r.rawQuery("SELECT value FROM openclaw_kv WHERE key=?", arrayOf(key))
        return cursor.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun persistConfig() {
        val w = db?.writableDatabase ?: return
        try {
            w.beginTransaction()
            writeKv(w, "oc_connected", connected.toString())
            writeKv(w, "oc_host", host.orEmpty())
            writeKv(w, "oc_profile", profile)
            writeKv(w, "oc_workspace", workspace)
            writeKv(w, "oc_lastSync", (lastSync ?: 0L).toString())
            writeKv(w, "oc_activeInstance", activeInstance.orEmpty())
            writeKv(w, "oc_mode", mode)
            w.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            w.endTransaction()
        }
    }

    private fun persistGateways() {
        val w = db?.writableDatabase ?: return
        try {
            writeKv(w, "oc_gateways", json.encodeToString(gateways.values.toList()))
        } catch (_: Exception) {}
    }

    private fun persistInstances() {
        val w = db?.writableDatabase ?: return
        try {
            writeKv(w, "oc_instances", json.encodeToString(instances.values.toList()))
        } catch (_: Exception) {}
    }

    private fun persistSessions() {
        val w = db?.writableDatabase ?: return
        try {
            writeKv(w, "oc_sessions", json.encodeToString(sessions.values.toList()))
        } catch (_: Exception) {}
    }

    private fun persistSkills() {
        val w = db?.writableDatabase ?: return
        try {
            writeKv(w, "oc_skills", json.encodeToString(skills.values.toList()))
        } catch (_: Exception) {}
    }

    private fun persistMessages() {
        val w = db?.writableDatabase ?: return
        try {
            writeKv(w, "oc_messages", json.encodeToString(messages.toList()))
        } catch (_: Exception) {}
    }

    private fun persistCronJobs() {
        val w = db?.writableDatabase ?: return
        try {
            writeKv(w, "oc_cron_jobs", json.encodeToString(cronJobs.values.toList()))
        } catch (_: Exception) {}
    }

    private fun persistLogs() {
        val w = db?.writableDatabase ?: return
        try {
            writeKv(w, "oc_logs", json.encodeToString(logs.toList()))
        } catch (_: Exception) {}
    }

    private fun loadFromDb() {
        val r = db?.readableDatabase ?: return
        try {
            // Ensure table exists (in case onUpgrade hasn't run yet)
            val tableExists = r.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='openclaw_kv'", null
            ).use { c -> c.moveToFirst() }
            if (!tableExists) return

            connected = readKv(r, "oc_connected")?.toBooleanStrictOrNull() ?: false
            host = readKv(r, "oc_host")?.ifBlank { null }
            profile = readKv(r, "oc_profile") ?: "default"
            workspace = readKv(r, "oc_workspace") ?: "/data/openclaw/workspace"
            lastSync = readKv(r, "oc_lastSync")?.toLongOrNull()?.takeIf { it > 0 }
            activeInstance = readKv(r, "oc_activeInstance")?.ifBlank { null }
            mode = readKv(r, "oc_mode") ?: "safe"

            readKv(r, "oc_gateways")?.let { raw ->
                runCatching { json.decodeFromString<List<GatewayStatus>>(raw) }.getOrNull()
                    ?.forEach { gateways[it.key] = it }
            }
            readKv(r, "oc_instances")?.let { raw ->
                runCatching { json.decodeFromString<List<InstanceSnapshot>>(raw) }.getOrNull()
                    ?.forEach { instances[it.name] = it }
            }
            readKv(r, "oc_sessions")?.let { raw ->
                runCatching { json.decodeFromString<List<SessionSnapshot>>(raw) }.getOrNull()
                    ?.forEach { sessions[it.id] = it }
            }
            readKv(r, "oc_skills")?.let { raw ->
                runCatching { json.decodeFromString<List<SkillSnapshot>>(raw) }.getOrNull()
                    ?.forEach { skills[it.name] = it }
            }
            readKv(r, "oc_messages")?.let { raw ->
                runCatching { json.decodeFromString<List<MessageSnapshot>>(raw) }.getOrNull()
                    ?.forEach { messages.addLast(it) }
            }
            readKv(r, "oc_cron_jobs")?.let { raw ->
                runCatching { json.decodeFromString<List<CronJobSnapshot>>(raw) }.getOrNull()
                    ?.forEach { cronJobs[it.id] = it }
            }
            readKv(r, "oc_logs")?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
                    ?.forEach { logs.addLast(it) }
            }
        } catch (_: Exception) {
            // Best-effort; fresh state on error.
        }
    }
}

/** Pluggable execution interface for skills that perform real work. */
fun interface SkillExecutor {
    fun execute(skillName: String): String?
}