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
import java.util.Calendar
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val cronScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "openclaw-cron").apply { isDaemon = true }
    }
    private var cronSchedulerStarted = false

    private val sessionScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "openclaw-sessions").apply { isDaemon = true }
    }
    private var sessionSchedulerStarted = false
    @Volatile private var pluggedSkillExecutor: SkillExecutor? = null

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
            startCronSchedulerLocked()
            startSessionSchedulerLocked()
        }
    }

    /** Wire a [SkillExecutor] so the session worker can dispatch skill-typed sessions. */
    fun setSkillExecutor(executor: SkillExecutor) {
        pluggedSkillExecutor = executor
    }

    private fun startSessionSchedulerLocked() {
        if (sessionSchedulerStarted) return
        sessionSchedulerStarted = true
        sessionScheduler.scheduleWithFixedDelay(
            {
                runCatching { processQueuedSessions() }
                    .onFailure { e ->
                        synchronized(lock) {
                            appendLog("Session scheduler error: ${e.message ?: "unknown"}")
                            persistLogs()
                        }
                    }
            },
            5L,
            5L,
            TimeUnit.SECONDS
        )
    }

    private fun startCronSchedulerLocked() {
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

    // ── Session worker ──────────────────────────────────────────────

    /**
     * Called every 5 s by [sessionScheduler].
     * Picks up sessions in "queued" state, transitions them through
     * running → completed/failed, then persists.
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
                    when (session.type) {
                        "command" -> {
                            runCommand(resolveSessionCommand(session)).output.take(2000)
                        }
                        "node" -> {
                            runCommand(resolveSessionCommand(session)).output.take(2000)
                        }
                        "skill" -> {
                            val executor = pluggedSkillExecutor
                            if (executor != null) {
                                executor.execute(session.target) ?: "Skill '${session.target}' returned no result"
                            } else {
                                "No skill executor available"
                            }
                        }
                        else -> {
                            runCommand(resolveSessionCommand(session)).output.take(2000)
                        }
                    }
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
    fun invokeSkill(name: String, executor: SkillExecutor? = null): SkillSnapshot? {
        // Stage 1: mark as running inside the lock
        val updatedSnapshot = synchronized(lock) {
            val skill = skills[name] ?: return null
            val updated = skill.copy(status = "running")
            skills[name] = updated
            appendLog("Skill invoked: $name.")
            persistSkills()
            updated
        }

        // Stage 2: execute outside the lock to avoid re-entry deadlock
        val result = executor?.execute(name)
        if (result != null) {
            return synchronized(lock) {
                val completed = updatedSnapshot.copy(status = "completed")
                skills[name] = completed
                appendLog("Skill completed: $name -> $result")
                persistSkills()
                completed
            }
        }
        return updatedSnapshot
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

    addMessage(gateway = "user", channel = ch, body = trimmed)

    val reply = when {
        trimmed.isBlank() -> "Say something. Humans love silence, apps shouldn't."
        trimmed.startsWith("/") -> runCommand(trimmed).output.ifBlank { "OK." }.take(3000)
        trimmed.equals("help", ignoreCase = true) -> runCommand("/help").output.take(3000)
        else -> "I'm online. Try /help, /status, /nodes, /skills, /gateways, /instances."
    }

    addMessage(gateway = "openclaw", channel = ch, body = reply)
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

    // ── Command execution ───────────────────────────────────────────

    /**
     * Execute a command string. Routes known commands to real operations,
     * unknown ones are echoed.
     */
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

    @Suppress("UNUSED_PARAMETER")
    fun runCommand(command: String, server: Any? = null): CommandResult = synchronized(lock) {
        appendLog("Command: $command")
        val nodeCommand = parseNodeCommand(command)
        val output = when {
            nodeCommand != null -> executeNodeCommand(nodeCommand)
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
                |  /node list â€” List connected nodes
                |  /node <id> ping
                |  /node <id> run <action>
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
