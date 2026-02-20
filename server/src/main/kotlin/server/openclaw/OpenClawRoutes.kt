package server.openclaw

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.*
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
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
data class MessageRequest(
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
data class ChatSendRequest(
  val body: String? = null,
  val channel: String? = null
)

@Serializable
data class SkillInvokeRequest(val name: String? = null)

@Serializable
data class CronJobRouteRequest(
  val schedule: String? = null,
  val command: String? = null,
  val enabled: Boolean? = null
)

@Serializable
data class OpenClawStatsResponse(
  val uptimeMs: Long,
  val nodeCount: Int
)

// ── Provider connector DTOs ──────────────────────────────────────────────

@Serializable
data class ProviderConnectorConfigRequest(
  val authMode: String? = null,
  val clientId: String? = null,
  val clientSecret: String? = null,
  val authUrl: String? = null,
  val tokenUrl: String? = null,
  val redirectUri: String? = null,
  val scope: String? = null,
  val apiBaseUrl: String? = null,
  val discoveryUrl: String? = null,
  val apiKey: String? = null,
  val webhookSecret: String? = null
)

@Serializable
data class ProviderAuthStartResponse(
  val provider: String,
  val authMode: String,
  val state: String? = null,
  val authUrl: String? = null,
  val message: String? = null
)

@Serializable
data class ProviderAuthCallbackRequest(
  val code: String? = null,
  val state: String? = null
)

@Serializable
data class ProviderApiKeyAuthRequest(
  val apiKey: String? = null,
  val apiBaseUrl: String? = null,
  val discoveryUrl: String? = null,
  val webhookSecret: String? = null
)

@Serializable
data class ProviderInboundWebhookResponse(
  val ok: Boolean,
  val provider: String,
  val ingested: Int
)

@Serializable
data class ProviderChannelSnapshot(
  val id: String,
  val name: String? = null,
  val kind: String = "channel"
)

@Serializable
data class ProviderChannelDiscoveryResponse(
  val provider: String,
  val source: String,
  val channels: List<ProviderChannelSnapshot>
)

@Serializable
data class ProviderConnectorSnapshot(
  val provider: String,
  val authMode: String,
  val configured: Boolean,
  val connected: Boolean,
  val hasApiKey: Boolean,
  val hasAccessToken: Boolean,
  val authUrl: String? = null,
  val tokenUrl: String? = null,
  val redirectUri: String? = null,
  val scope: String? = null,
  val apiBaseUrl: String? = null,
  val discoveryUrl: String? = null,
  val updatedAt: Long,
  val lastError: String? = null
)

data class ProviderConnectorState(
  val provider: String,
  var authMode: String = "api_key",
  var clientId: String? = null,
  var clientSecret: String? = null,
  var authUrl: String? = null,
  var tokenUrl: String? = null,
  var redirectUri: String? = null,
  var scope: String? = null,
  var apiBaseUrl: String? = null,
  var discoveryUrl: String? = null,
  var apiKey: String? = null,
  var accessToken: String? = null,
  var refreshToken: String? = null,
  var webhookSecret: String? = null,
  var pendingState: String? = null,
  var connected: Boolean = false,
  var updatedAt: Long = System.currentTimeMillis(),
  var lastError: String? = null
) {
  fun toSnapshot(): ProviderConnectorSnapshot = ProviderConnectorSnapshot(
    provider = provider,
    authMode = authMode,
    configured = !clientId.isNullOrBlank() || !apiKey.isNullOrBlank(),
    connected = connected,
    hasApiKey = !apiKey.isNullOrBlank(),
    hasAccessToken = !accessToken.isNullOrBlank(),
    authUrl = authUrl,
    tokenUrl = tokenUrl,
    redirectUri = redirectUri,
    scope = scope,
    apiBaseUrl = apiBaseUrl,
    discoveryUrl = discoveryUrl,
    updatedAt = updatedAt,
    lastError = lastError
  )
}

// ── Provider connector state + helpers ───────────────────────────────────

object ProviderConnectorStore {
  private val lock = Any()
  private val connectors = LinkedHashMap<String, ProviderConnectorState>()

  fun getOrCreate(provider: String): ProviderConnectorState = synchronized(lock) {
    val key = normalizeProviderKey(provider)
    connectors.getOrPut(key) { ProviderConnectorState(provider = key) }
  }

  fun snapshot(): List<ProviderConnectorSnapshot> = synchronized(lock) {
    connectors.values.map { it.toSnapshot() }
  }
}

private fun normalizeProviderKey(raw: String): String = raw.trim().lowercase()

private const val OPENCLAW_MAX_WEBHOOK_CHARS = 256_000
private const val OPENCLAW_MAX_CHAT_CHARS = 4096

private val providerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun isLoopbackHost(host: String): Boolean {
  val normalized = host.trim().trimEnd('.').lowercase()
  return normalized == "localhost" ||
    normalized == "::1" ||
    normalized == "0:0:0:0:0:0:0:1" ||
    normalized.startsWith("127.")
}

private fun validateProviderUrl(raw: String?, fieldName: String): String? {
  val value = raw?.trim().orEmpty()
  if (value.isBlank()) return null
  val uri = runCatching { URI(value) }.getOrNull()
    ?: return "$fieldName must be a valid absolute URL"
  val scheme = uri.scheme?.trim()?.lowercase()
  val host = uri.host?.trim().orEmpty()
  if (scheme.isNullOrBlank() || host.isBlank()) {
    return "$fieldName must be a valid absolute URL"
  }
  if (scheme != "http" && scheme != "https") {
    return "$fieldName must use http or https"
  }
  if (!uri.userInfo.isNullOrBlank()) {
    return "$fieldName must not include credentials in URL"
  }
  if (scheme == "http" && !isLoopbackHost(host)) {
    return "$fieldName must use https for non-loopback targets"
  }
  return null
}

private fun constantTimeSecretMatch(expectedSecret: String, providedSecret: String): Boolean {
  val md = MessageDigest.getInstance("SHA-256")
  val expected = md.digest(expectedSecret.toByteArray(StandardCharsets.UTF_8))
  val provided = md.digest(providedSecret.toByteArray(StandardCharsets.UTF_8))
  return MessageDigest.isEqual(expected, provided)
}

private fun httpPostForm(url: String, form: Map<String, String>): Pair<Int, String> {
  val body = form.entries.joinToString("&") { (k, v) ->
    "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(v, StandardCharsets.UTF_8.name())}"
  }
  val conn = (URL(url).openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    connectTimeout = 5000
    readTimeout = 5000
    doOutput = true
    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    setRequestProperty("Accept", "application/json")
  }
  return try {
    conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
  } finally {
    conn.disconnect()
  }
}

private fun httpGetJson(url: String, bearerToken: String? = null, apiKey: String? = null): Pair<Int, String> {
  val conn = (URL(url).openConnection() as HttpURLConnection).apply {
    requestMethod = "GET"
    connectTimeout = 5000
    readTimeout = 5000
    setRequestProperty("Accept", "application/json")
    if (!bearerToken.isNullOrBlank()) {
      setRequestProperty("Authorization", "Bearer $bearerToken")
    }
    if (!apiKey.isNullOrBlank()) {
      setRequestProperty("X-API-Key", apiKey)
    }
  }
  return try {
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
  } finally {
    conn.disconnect()
  }
}

private fun parseInboundMessages(provider: String, rawBody: String): List<Pair<String, String>> {
  val payload = runCatching { providerJson.parseToJsonElement(rawBody) }.getOrNull() ?: return emptyList()
  val entries = mutableListOf<Pair<String, String>>()

  fun appendMessage(channel: String?, body: String?) {
    val trimmedBody = body?.trim().orEmpty()
    if (trimmedBody.isBlank()) return
    if (trimmedBody.length > OPENCLAW_MAX_CHAT_CHARS) return
    val resolvedChannel = channel?.trim().orEmpty().ifBlank { "general" }
    entries += resolvedChannel to trimmedBody
  }

  when (provider) {
    "telegram" -> {
      val updates = payload.jsonObject["updates"]?.jsonArray ?: JsonArray(emptyList())
      updates.forEach { item ->
        val msg = item.jsonObject["message"]?.jsonObject ?: return@forEach
        val text = msg["text"]?.jsonPrimitive?.contentOrNull
        val chat = msg["chat"]?.jsonObject
        val channel = chat?.get("username")?.jsonPrimitive?.contentOrNull
          ?: chat?.get("id")?.jsonPrimitive?.contentOrNull
        appendMessage(channel, text)
      }
    }
    else -> {
      val messages = payload.jsonObject["messages"]?.jsonArray
      if (messages != null) {
        messages.forEach { item ->
          val obj = item.jsonObject
          appendMessage(
            channel = obj["channel"]?.jsonPrimitive?.contentOrNull ?: obj["chatId"]?.jsonPrimitive?.contentOrNull,
            body = obj["body"]?.jsonPrimitive?.contentOrNull ?: obj["text"]?.jsonPrimitive?.contentOrNull
          )
        }
      } else {
        appendMessage(
          channel = payload.jsonObject["channel"]?.jsonPrimitive?.contentOrNull,
          body = payload.jsonObject["body"]?.jsonPrimitive?.contentOrNull ?: payload.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        )
      }
    }
  }
  return entries
}

private fun parseChannels(provider: String, rawBody: String): List<ProviderChannelSnapshot> {
  val payload = runCatching { providerJson.parseToJsonElement(rawBody) }.getOrNull() ?: return emptyList()

  fun fromArray(items: JsonArray): List<ProviderChannelSnapshot> = items.mapNotNull { element ->
    val obj = element as? JsonObject ?: return@mapNotNull null
    val id = obj["id"]?.jsonPrimitive?.contentOrNull
      ?: obj["chat_id"]?.jsonPrimitive?.contentOrNull
      ?: obj["name"]?.jsonPrimitive?.contentOrNull
      ?: return@mapNotNull null
    val name = obj["name"]?.jsonPrimitive?.contentOrNull
      ?: obj["title"]?.jsonPrimitive?.contentOrNull
      ?: obj["username"]?.jsonPrimitive?.contentOrNull
    ProviderChannelSnapshot(id = id, name = name)
  }

  return when (provider) {
    "telegram" -> {
      val result = payload.jsonObject["result"]?.jsonArray ?: JsonArray(emptyList())
      val telegramChannels = result.mapNotNull { update ->
        val message = update.jsonObject["message"]?.jsonObject ?: return@mapNotNull null
        val chat = message["chat"]?.jsonObject ?: return@mapNotNull null
        val id = chat["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val name = chat["title"]?.jsonPrimitive?.contentOrNull
          ?: chat["username"]?.jsonPrimitive?.contentOrNull
          ?: chat["first_name"]?.jsonPrimitive?.contentOrNull
        ProviderChannelSnapshot(id = id, name = name)
      }.distinctBy { it.id }
      if (telegramChannels.isNotEmpty()) {
        telegramChannels
      } else {
        val fallback = payload.jsonObject["channels"]?.jsonArray
        if (fallback != null) fromArray(fallback) else emptyList()
      }
    }
    "discord" -> {
      val items = payload.jsonObject["channels"]?.jsonArray
      if (items != null) {
        fromArray(items)
      } else if (payload is JsonArray) {
        fromArray(payload)
      } else {
        emptyList()
      }
    }
    else -> {
      val items = payload.jsonObject["channels"]?.jsonArray
      if (items != null) fromArray(items) else if (payload is JsonArray) fromArray(payload) else emptyList()
    }
  }
}

fun Route.openClawRoutes() {
  get("/api/openclaw/nodes") {
    call.respond(OpenClawGatewayState.listNodes())
  }

  get("/api/openclaw/stats") {
    call.respond(OpenClawStatsResponse(uptimeMs = OpenClawGatewayState.uptimeMs(), nodeCount = OpenClawGatewayState.nodeCount()))
  }

  get("/api/openclaw/dashboard") {
    call.respond(OpenClawDashboardState.snapshot())
  }

  post("/api/openclaw/connect") {
    val req = call.receive<ConnectRequest>()
    call.respond(OpenClawDashboardState.connect(req.host))
  }

  post("/api/openclaw/disconnect") {
    call.respond(OpenClawDashboardState.disconnect())
  }

  post("/api/openclaw/refresh") {
    call.respond(OpenClawDashboardState.refresh())
  }

  post("/api/openclaw/panic") {
    call.respond(OpenClawDashboardState.panic())
  }

  get("/api/openclaw/gateways") {
    call.respond(OpenClawDashboardState.listGateways())
  }

  post("/api/openclaw/gateways/{key}/restart") {
    val key = call.parameters["key"]?.trim().orEmpty()
    val updated = OpenClawDashboardState.restartGateway(key)
    if (updated == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown gateway"))
    } else {
      call.respond(updated)
    }
  }

  post("/api/openclaw/gateways/{key}/ping") {
    val key = call.parameters["key"]?.trim().orEmpty()
    val result = OpenClawDashboardState.pingGateway(key)
    if (result == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown gateway"))
    } else {
      call.respond(result)
    }
  }

  get("/api/openclaw/instances") {
    call.respond(OpenClawDashboardState.listInstances())
  }

  post("/api/openclaw/instances") {
    val req = call.receive<InstanceRequest>()
    val name = req.name?.trim().orEmpty()
    if (name.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Instance name required"))
      return@post
    }
    val instance = OpenClawDashboardState.addInstance(name, req.profile, req.workspace, req.sandbox, req.access)
    if (instance == null) {
      call.respond(HttpStatusCode.Conflict, mapOf("error" to "Instance already exists"))
    } else {
      call.respond(instance)
    }
  }

  post("/api/openclaw/instances/{name}/select") {
    val name = call.parameters["name"]?.trim().orEmpty()
    val instance = OpenClawDashboardState.selectInstance(name)
    if (instance == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown instance"))
    } else {
      call.respond(instance)
    }
  }

  post("/api/openclaw/instances/{name}/activate") {
    val name = call.parameters["name"]?.trim().orEmpty()
    val instance = OpenClawDashboardState.activateInstance(name)
    if (instance == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown instance"))
    } else {
      call.respond(instance)
    }
  }

  post("/api/openclaw/instances/{name}/stop") {
    val name = call.parameters["name"]?.trim().orEmpty()
    val instance = OpenClawDashboardState.stopInstance(name)
    if (instance == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown instance"))
    } else {
      call.respond(instance)
    }
  }

  get("/api/openclaw/sessions") {
    call.respond(OpenClawDashboardState.listSessions())
  }

  post("/api/openclaw/sessions") {
    val req = call.receive<SessionRequest>()
    val type = req.type?.trim().orEmpty().ifBlank { "session" }
    val target = req.target?.trim().orEmpty().ifBlank { "default" }
    val session = OpenClawDashboardState.createSession(type, target, req.payload)
    call.respond(session)
  }

  post("/api/openclaw/sessions/{id}/cancel") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val session = OpenClawDashboardState.cancelSession(id)
    if (session == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown session"))
    } else {
      call.respond(session)
    }
  }

  get("/api/openclaw/skills") {
    call.respond(OpenClawDashboardState.listSkills())
  }

  post("/api/openclaw/skills/refresh") {
    call.respond(OpenClawDashboardState.refreshSkills())
  }

  post("/api/openclaw/skills/invoke") {
    val req = call.receive<SkillInvokeRequest>()
    val name = req.name?.trim().orEmpty()
    if (name.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Skill name required"))
      return@post
    }
    val skill = OpenClawDashboardState.invokeSkill(name)
    if (skill == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown skill"))
    } else {
      call.respond(skill)
    }
  }

  get("/api/openclaw/mode") {
    call.respond(mapOf("mode" to OpenClawDashboardState.snapshot().mode))
  }

  post("/api/openclaw/mode") {
    val req = call.receive<ModeRequest>()
    val mode = req.mode?.trim().orEmpty().ifBlank { "safe" }
    call.respond(mapOf("mode" to OpenClawDashboardState.setMode(mode)))
  }

  get("/api/openclaw/config") {
    call.respond(OpenClawDashboardState.configSnapshot())
  }

  post("/api/openclaw/config") {
    val req = call.receive<ConfigRequest>()
    call.respond(OpenClawDashboardState.updateConfig(req.host, req.profile, req.workspace))
  }

  get("/api/openclaw/debug") {
    call.respond(OpenClawDashboardState.debugSnapshot())
  }

  post("/api/openclaw/debug/dump") {
    call.respond(OpenClawDashboardState.debugSnapshot())
  }

  get("/api/openclaw/logs") {
    call.respond(OpenClawDashboardState.listLogs())
  }

  post("/api/openclaw/logs/clear") {
    OpenClawDashboardState.clearLogs()
    call.respond(mapOf("ok" to true))
  }

  get("/api/openclaw/messages") {
    call.respond(OpenClawDashboardState.listMessages())
  }

  post("/api/openclaw/messages") {
    val req = call.receive<MessageRequest>()
    val gateway = req.gateway?.trim().orEmpty()
    val channel = req.channel?.trim().orEmpty()
    val body = req.body?.trim().orEmpty()
    if (gateway.isBlank() || channel.isBlank() || body.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "gateway, channel, and body required"))
      return@post
    }
    call.respond(OpenClawDashboardState.addMessage(gateway, channel, body))
  }

  post("/api/openclaw/command") {
    val req = call.receive<CommandRequest>()
    val command = req.command?.trim().orEmpty()
    if (command.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Command required"))
      return@post
    }
    call.respond(OpenClawDashboardState.runCommand(command))
  }

  post("/api/openclaw/chat/send") {
    val req = call.receive<ChatSendRequest>()
    val body = req.body?.trim().orEmpty()
    if (body.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message body required"))
      return@post
    }
    val channel = req.channel?.trim().orEmpty().ifBlank { "general" }
    call.respond(OpenClawDashboardState.addChatMessage(body = body, channel = channel))
  }

  // ── Cron ──────────────────────────────────────────────────────────────

  get("/api/openclaw/cron") {
    call.respond(OpenClawDashboardState.listCronJobs())
  }

  post("/api/openclaw/cron") {
    val req = call.receive<CronJobRouteRequest>()
    val schedule = req.schedule?.trim().orEmpty()
    val command = req.command?.trim().orEmpty()
    if (schedule.isBlank() || command.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "schedule and command required"))
      return@post
    }
    val job = OpenClawDashboardState.addCronJob(schedule, command, req.enabled ?: true)
    if (job == null) {
      call.respond(HttpStatusCode.Conflict, mapOf("error" to "Max cron jobs reached"))
    } else {
      call.respond(job)
    }
  }

  post("/api/openclaw/cron/{id}/toggle") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val job = OpenClawDashboardState.toggleCronJob(id)
    if (job == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown cron job"))
    } else {
      call.respond(job)
    }
  }

  delete("/api/openclaw/cron/{id}") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val removed = OpenClawDashboardState.removeCronJob(id)
    if (removed) {
      call.respond(mapOf("ok" to true))
    } else {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown cron job"))
    }
  }

  // ── Provider connector routes ──────────────────────────────────────────

  get("/api/openclaw/providers") {
    call.respond(ProviderConnectorStore.snapshot())
  }

  get("/api/openclaw/providers/{provider}") {
    val provider = normalizeProviderKey(call.parameters["provider"]?.trim().orEmpty())
    if (provider.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider required"))
      return@get
    }
    call.respond(ProviderConnectorStore.getOrCreate(provider).toSnapshot())
  }

  post("/api/openclaw/providers/{provider}/auth/start") {
    val provider = normalizeProviderKey(call.parameters["provider"]?.trim().orEmpty())
    if (provider.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider required"))
      return@post
    }
    val req = call.receive<ProviderConnectorConfigRequest>()
    val authUrlInput = req.authUrl?.trim().orEmpty().ifBlank { null }
    val tokenUrlInput = req.tokenUrl?.trim().orEmpty().ifBlank { null }
    val apiBaseInput = req.apiBaseUrl?.trim().orEmpty().ifBlank { null }
    val discoveryInput = req.discoveryUrl?.trim().orEmpty().ifBlank { null }
    listOf(
      validateProviderUrl(authUrlInput, "authUrl"),
      validateProviderUrl(tokenUrlInput, "tokenUrl"),
      validateProviderUrl(apiBaseInput, "apiBaseUrl"),
      validateProviderUrl(discoveryInput, "discoveryUrl")
    ).firstOrNull { !it.isNullOrBlank() }?.let { error ->
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to error))
      return@post
    }
    val connector = ProviderConnectorStore.getOrCreate(provider)
    synchronized(connector) {
      connector.authMode = req.authMode?.trim().orEmpty().ifBlank { connector.authMode }.lowercase()
      connector.clientId = req.clientId?.trim().orEmpty().ifBlank { connector.clientId }
      connector.clientSecret = req.clientSecret?.trim().orEmpty().ifBlank { connector.clientSecret }
      connector.authUrl = req.authUrl?.trim().orEmpty().ifBlank { connector.authUrl }
      connector.tokenUrl = req.tokenUrl?.trim().orEmpty().ifBlank { connector.tokenUrl }
      connector.redirectUri = req.redirectUri?.trim().orEmpty().ifBlank { connector.redirectUri }
      connector.scope = req.scope?.trim().orEmpty().ifBlank { connector.scope }
      connector.apiBaseUrl = req.apiBaseUrl?.trim().orEmpty().ifBlank { connector.apiBaseUrl }
      connector.discoveryUrl = req.discoveryUrl?.trim().orEmpty().ifBlank { connector.discoveryUrl }
      connector.webhookSecret = req.webhookSecret?.trim().orEmpty().ifBlank { connector.webhookSecret }
      connector.lastError = null
      connector.updatedAt = System.currentTimeMillis()
    }

    if (connector.authMode != "oauth2") {
      call.respond(
        ProviderAuthStartResponse(
          provider = provider,
          authMode = connector.authMode,
          message = "Provider uses API key flow. Submit /auth/api-key."
        )
      )
      return@post
    }

    if (connector.authUrl.isNullOrBlank() || connector.clientId.isNullOrBlank() || connector.redirectUri.isNullOrBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "authUrl, clientId, and redirectUri are required for oauth2"))
      return@post
    }

    val state = java.util.UUID.randomUUID().toString()
    val params = linkedMapOf(
      "response_type" to "code",
      "client_id" to connector.clientId.orEmpty(),
      "redirect_uri" to connector.redirectUri.orEmpty(),
      "state" to state
    )
    connector.scope?.takeIf { it.isNotBlank() }?.let { params["scope"] = it }
    val query = params.entries.joinToString("&") { (k, v) ->
      "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(v, StandardCharsets.UTF_8.name())}"
    }
    val authUrl = "${connector.authUrl}?$query"
    synchronized(connector) {
      connector.pendingState = state
      connector.updatedAt = System.currentTimeMillis()
    }
    call.respond(ProviderAuthStartResponse(provider = provider, authMode = "oauth2", state = state, authUrl = authUrl))
  }

  post("/api/openclaw/providers/{provider}/auth/callback") {
    val provider = normalizeProviderKey(call.parameters["provider"]?.trim().orEmpty())
    if (provider.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider required"))
      return@post
    }
    val req = call.receive<ProviderAuthCallbackRequest>()
    val code = req.code?.trim().orEmpty()
    val state = req.state?.trim().orEmpty()
    if (code.isBlank() || state.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "code and state required"))
      return@post
    }

    val connector = ProviderConnectorStore.getOrCreate(provider)
    if (connector.authMode != "oauth2") {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider is not configured for oauth2"))
      return@post
    }
    if (connector.pendingState.isNullOrBlank() || connector.pendingState != state) {
      call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid oauth state"))
      return@post
    }
    if (connector.tokenUrl.isNullOrBlank() || connector.clientId.isNullOrBlank() || connector.clientSecret.isNullOrBlank() || connector.redirectUri.isNullOrBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tokenUrl, clientId, clientSecret, and redirectUri are required"))
      return@post
    }
    validateProviderUrl(connector.tokenUrl, "tokenUrl")?.let { error ->
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to error))
      return@post
    }

    val (status, body) = try {
      httpPostForm(
        connector.tokenUrl.orEmpty(),
        mapOf(
          "grant_type" to "authorization_code",
          "client_id" to connector.clientId.orEmpty(),
          "client_secret" to connector.clientSecret.orEmpty(),
          "code" to code,
          "redirect_uri" to connector.redirectUri.orEmpty()
        )
      )
    } catch (e: Exception) {
      synchronized(connector) {
        connector.lastError = "token_exchange_failed"
        connector.connected = false
        connector.updatedAt = System.currentTimeMillis()
      }
      call.respond(HttpStatusCode.BadGateway, mapOf("error" to "token exchange failed"))
      return@post
    }

    if (status !in 200..299) {
      synchronized(connector) {
        connector.lastError = "token_exchange_http_$status"
        connector.connected = false
        connector.updatedAt = System.currentTimeMillis()
      }
      call.respond(HttpStatusCode.BadGateway, mapOf("error" to "token exchange failed", "status" to status))
      return@post
    }

    val parsed = runCatching { providerJson.parseToJsonElement(body).jsonObject }.getOrNull()
    val accessToken = parsed?.get("access_token")?.jsonPrimitive?.contentOrNull
    val refreshToken = parsed?.get("refresh_token")?.jsonPrimitive?.contentOrNull
    if (accessToken.isNullOrBlank()) {
      synchronized(connector) {
        connector.lastError = "token_missing_access_token"
        connector.connected = false
        connector.updatedAt = System.currentTimeMillis()
      }
      call.respond(HttpStatusCode.BadGateway, mapOf("error" to "provider token response missing access_token"))
      return@post
    }

    synchronized(connector) {
      connector.accessToken = accessToken
      connector.refreshToken = refreshToken
      connector.connected = true
      connector.pendingState = null
      connector.lastError = null
      connector.updatedAt = System.currentTimeMillis()
    }
    OpenClawDashboardState.restartGateway(provider)
    call.respond(ProviderConnectorStore.getOrCreate(provider).toSnapshot())
  }

  post("/api/openclaw/providers/{provider}/auth/api-key") {
    val provider = normalizeProviderKey(call.parameters["provider"]?.trim().orEmpty())
    if (provider.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider required"))
      return@post
    }

    val req = call.receive<ProviderApiKeyAuthRequest>()
    val key = req.apiKey?.trim().orEmpty()
    if (key.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "apiKey required"))
      return@post
    }
    val apiBaseInput = req.apiBaseUrl?.trim().orEmpty().ifBlank { null }
    val discoveryInput = req.discoveryUrl?.trim().orEmpty().ifBlank { null }
    listOf(
      validateProviderUrl(apiBaseInput, "apiBaseUrl"),
      validateProviderUrl(discoveryInput, "discoveryUrl")
    ).firstOrNull { !it.isNullOrBlank() }?.let { error ->
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to error))
      return@post
    }
    val connector = ProviderConnectorStore.getOrCreate(provider)
    synchronized(connector) {
      connector.authMode = "api_key"
      connector.apiKey = key
      connector.apiBaseUrl = req.apiBaseUrl?.trim().orEmpty().ifBlank { connector.apiBaseUrl }
      connector.discoveryUrl = req.discoveryUrl?.trim().orEmpty().ifBlank { connector.discoveryUrl }
      connector.webhookSecret = req.webhookSecret?.trim().orEmpty().ifBlank { connector.webhookSecret }
      connector.connected = true
      connector.lastError = null
      connector.updatedAt = System.currentTimeMillis()
    }
    OpenClawDashboardState.restartGateway(provider)
    call.respond(ProviderConnectorStore.getOrCreate(provider).toSnapshot())
  }

  post("/api/openclaw/providers/{provider}/webhook") {
    val provider = normalizeProviderKey(call.parameters["provider"]?.trim().orEmpty())
    if (provider.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider required"))
      return@post
    }

    val connector = ProviderConnectorStore.getOrCreate(provider)
    val expectedSecret = connector.webhookSecret?.takeIf { it.isNotBlank() }
    if (!expectedSecret.isNullOrBlank()) {
      val provided = call.request.headers["X-Webhook-Secret"]?.trim().orEmpty()
      if (provided.isBlank() || !constantTimeSecretMatch(expectedSecret, provided)) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid webhook secret"))
        return@post
      }
    }

    val raw = call.receiveText()
    if (raw.length > OPENCLAW_MAX_WEBHOOK_CHARS) {
      call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "payload too large"))
      return@post
    }
    val entries = parseInboundMessages(provider, raw)
    if (entries.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no supported messages in payload"))
      return@post
    }
    entries.forEach { (channel, body) ->
      OpenClawDashboardState.addMessage(gateway = provider, channel = channel, body = body)
    }
    synchronized(connector) {
      connector.connected = true
      connector.lastError = null
      connector.updatedAt = System.currentTimeMillis()
    }
    call.respond(ProviderInboundWebhookResponse(ok = true, provider = provider, ingested = entries.size))
  }

  get("/api/openclaw/providers/{provider}/channels") {
    val provider = normalizeProviderKey(call.parameters["provider"]?.trim().orEmpty())
    if (provider.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider required"))
      return@get
    }

    val connector = ProviderConnectorStore.getOrCreate(provider)
    val discoveryUrl = connector.discoveryUrl?.takeIf { it.isNotBlank() }
      ?: connector.apiBaseUrl?.trimEnd('/')?.plus("/channels")
      ?: when (provider) {
        "telegram" -> connector.accessToken?.let { "https://api.telegram.org/bot$it/getUpdates?limit=100" }
        else -> null
      }

    if (discoveryUrl.isNullOrBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider discovery URL is not configured"))
      return@get
    }
    validateProviderUrl(discoveryUrl, "discoveryUrl")?.let { error ->
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to error))
      return@get
    }

    val (status, body) = try {
      httpGetJson(discoveryUrl, bearerToken = connector.accessToken, apiKey = connector.apiKey)
    } catch (e: Exception) {
      synchronized(connector) {
        connector.connected = false
        connector.lastError = "channel_discovery_failed"
        connector.updatedAt = System.currentTimeMillis()
      }
      call.respond(HttpStatusCode.BadGateway, mapOf("error" to "channel discovery failed"))
      return@get
    }

    if (status !in 200..299) {
      synchronized(connector) {
        connector.connected = false
        connector.lastError = "channel_discovery_http_$status"
        connector.updatedAt = System.currentTimeMillis()
      }
      call.respond(HttpStatusCode.BadGateway, mapOf("error" to "channel discovery failed", "status" to status))
      return@get
    }

    val channels = parseChannels(provider, body)
    synchronized(connector) {
      connector.connected = true
      connector.lastError = null
      connector.updatedAt = System.currentTimeMillis()
    }
    call.respond(ProviderChannelDiscoveryResponse(provider = provider, source = discoveryUrl, channels = channels))
  }
}
