package com.netninja

import android.os.Build
import android.os.SystemClock
import com.netninja.cam.OnvifDiscoveryService
import com.netninja.gateway.g5ar.G5arApiImpl
import com.netninja.network.SpeedTestEngine
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
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
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
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.net.URLEncoder
import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

@Serializable
private data class SpeedtestServer(
  val id: String,
  val name: String,
  val location: String
)

@Serializable
private data class SpeedtestConfig(
  val serverId: String = "neo-1",
  val realMode: Boolean = true,
  val precisionRange: Boolean = false
)

@Serializable
private data class SpeedtestConfigPatch(
  val serverId: String? = null,
  val realMode: Boolean? = null,
  val precisionRange: Boolean? = null
)

@Serializable
private data class SpeedtestControlRequest(
  val serverId: String? = null,
  val realMode: Boolean? = null,
  val precisionRange: Boolean? = null
)

@Serializable
private data class SpeedtestLiveState(
  val running: Boolean = false,
  val phase: String = "IDLE",
  val progress: Int = 0,
  val serverId: String = "neo-1",
  val pingMs: Double? = null,
  val jitterMs: Double? = null,
  val downloadMbps: Double? = null,
  val uploadMbps: Double? = null,
  val lossPct: Double? = null,
  val bufferbloat: String? = null,
  val error: String? = null,
  val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
private data class SpeedtestResultRequest(
  val running: Boolean? = null,
  val phase: String? = null,
  val progress: Int? = null,
  val serverId: String? = null,
  val pingMs: Double? = null,
  val jitterMs: Double? = null,
  val downloadMbps: Double? = null,
  val uploadMbps: Double? = null,
  val lossPct: Double? = null,
  val bufferbloat: String? = null,
  val error: String? = null
)

@Serializable
private data class ProviderConnectorConfigRequest(
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
private data class ProviderAuthStartResponse(
  val provider: String,
  val authMode: String,
  val state: String? = null,
  val authUrl: String? = null,
  val message: String? = null
)

@Serializable
private data class ProviderAuthCallbackRequest(
  val code: String? = null,
  val state: String? = null
)

@Serializable
private data class ProviderApiKeyAuthRequest(
  val apiKey: String? = null,
  val apiBaseUrl: String? = null,
  val discoveryUrl: String? = null,
  val webhookSecret: String? = null
)

@Serializable
private data class ProviderInboundWebhookResponse(
  val ok: Boolean,
  val provider: String,
  val ingested: Int
)

@Serializable
private data class ProviderChannelSnapshot(
  val id: String,
  val name: String? = null,
  val kind: String = "channel"
)

@Serializable
private data class ProviderChannelDiscoveryResponse(
  val provider: String,
  val source: String,
  val channels: List<ProviderChannelSnapshot>
)

@Serializable
private data class ProviderConnectorSnapshot(
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

private data class ProviderConnectorState(
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

private fun normalizeProviderKey(raw: String): String = raw.trim().lowercase()

private const val OPENCLAW_MAX_COMMAND_CHARS = 4096
private const val OPENCLAW_MAX_CHAT_CHARS = 4096
private const val OPENCLAW_MAX_WEBHOOK_CHARS = 256_000
private const val OPENCLAW_WS_PROTOCOL_VERSION = 1
private val OPENCLAW_WS_ALLOWED_TYPES = setOf("OBSERVE", "HELLO", "HEARTBEAT", "RESULT")

@Serializable
internal data class OpenClawWsErrorFrame(
  val type: String = "ERROR",
  val code: String,
  val message: String,
  val protocolVersion: Int = OPENCLAW_WS_PROTOCOL_VERSION
)

internal sealed class OpenClawWsValidationResult {
  data class Valid(val message: OpenClawWsMessage) : OpenClawWsValidationResult()
  data class Invalid(val error: OpenClawWsErrorFrame, val closeCode: CloseReason.Codes = CloseReason.Codes.CANNOT_ACCEPT) :
    OpenClawWsValidationResult()
}

internal fun validateOpenClawWsMessage(
  payload: String,
  currentNodeId: String?,
  parser: Json
): OpenClawWsValidationResult {
  fun invalid(code: String, message: String): OpenClawWsValidationResult.Invalid =
    OpenClawWsValidationResult.Invalid(OpenClawWsErrorFrame(code = code, message = message))

  val msg = runCatching {
    parser.decodeFromString(OpenClawWsMessage.serializer(), payload)
  }.getOrNull() ?: return invalid("MALFORMED_JSON", "Invalid JSON payload.")

  val type = msg.type.trim().uppercase(java.util.Locale.ROOT)
  if (type !in OPENCLAW_WS_ALLOWED_TYPES) {
    return invalid("UNKNOWN_TYPE", "Unsupported message type '$type'.")
  }

  val version = msg.protocolVersion ?: OPENCLAW_WS_PROTOCOL_VERSION
  if (version != OPENCLAW_WS_PROTOCOL_VERSION) {
    return invalid(
      "UNSUPPORTED_PROTOCOL_VERSION",
      "Unsupported protocolVersion '$version'. Expected $OPENCLAW_WS_PROTOCOL_VERSION."
    )
  }

  val trimmedNodeId = msg.nodeId?.trim()?.takeIf { it.isNotBlank() }
  val trimmedPayload = msg.payload?.trim()

  return when (type) {
    "HELLO" -> {
      if (trimmedNodeId == null) invalid("MISSING_NODE_ID", "HELLO requires nodeId.")
      else OpenClawWsValidationResult.Valid(
        msg.copy(type = type, protocolVersion = version, nodeId = trimmedNodeId)
      )
    }
    "HEARTBEAT" -> {
      if (currentNodeId.isNullOrBlank() && trimmedNodeId == null) {
        invalid("MISSING_NODE_IDENTITY", "HEARTBEAT requires nodeId or prior HELLO.")
      } else {
        OpenClawWsValidationResult.Valid(
          msg.copy(type = type, protocolVersion = version, nodeId = trimmedNodeId)
        )
      }
    }
    "RESULT" -> {
      when {
        currentNodeId.isNullOrBlank() && trimmedNodeId == null ->
          invalid("MISSING_NODE_IDENTITY", "RESULT requires nodeId or prior HELLO.")
        trimmedPayload == null ->
          invalid("MISSING_PAYLOAD", "RESULT requires payload.")
        else ->
          OpenClawWsValidationResult.Valid(
            msg.copy(type = type, protocolVersion = version, nodeId = trimmedNodeId, payload = trimmedPayload)
          )
      }
    }
    else -> OpenClawWsValidationResult.Valid(msg.copy(type = type, protocolVersion = version, nodeId = trimmedNodeId))
  }
}

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

private fun parseInboundMessages(provider: String, rawBody: String, parser: Json): List<Pair<String, String>> {
  val payload = runCatching { parser.parseToJsonElement(rawBody) }.getOrNull() ?: return emptyList()
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

private fun parseChannels(provider: String, rawBody: String, parser: Json): List<ProviderChannelSnapshot> {
  val payload = runCatching { parser.parseToJsonElement(rawBody) }.getOrNull() ?: return emptyList()

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
      result.mapNotNull { update ->
        val message = update.jsonObject["message"]?.jsonObject ?: return@mapNotNull null
        val chat = message["chat"]?.jsonObject ?: return@mapNotNull null
        val id = chat["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val name = chat["title"]?.jsonPrimitive?.contentOrNull
          ?: chat["username"]?.jsonPrimitive?.contentOrNull
          ?: chat["first_name"]?.jsonPrimitive?.contentOrNull
        ProviderChannelSnapshot(id = id, name = name)
      }.distinctBy { it.id }
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

internal fun Application.installApiRoutes(server: AndroidLocalServer, uiDir: File) {
  with(server) {
    val g5arApi = G5arApiImpl()
    val g5arCredentials = G5arCredentialStore(ctx)
    var g5arSession: G5arSession? = null
    val speedtestConfig = AtomicReference(SpeedtestConfig())
    val speedtestLive = AtomicReference(SpeedtestLiveState())
    val speedtestAbort = AtomicBoolean(false)
    val speedtestEngine = SpeedTestEngine()
    val speedtestScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var speedtestJob: Job? = null
    val speedtestServers = listOf(
      SpeedtestServer(id = "neo-1", name = "NEO-1", location = "Downtown Relay"),
      SpeedtestServer(id = "arc-7", name = "ARC-7", location = "Subnet Spire"),
      SpeedtestServer(id = "hex-3", name = "HEX-3", location = "Industrial Backbone"),
      SpeedtestServer(id = "luna-9", name = "LUNA-9", location = "Edge Satellite")
    )
    val connectorStateLock = Any()
    val providerConnectors = LinkedHashMap<String, ProviderConnectorState>()

    fun getOrCreateConnector(provider: String): ProviderConnectorState = synchronized(connectorStateLock) {
      val key = normalizeProviderKey(provider)
      providerConnectors.getOrPut(key) { ProviderConnectorState(provider = key) }
    }

    fun snapshotConnectors(): List<ProviderConnectorSnapshot> = synchronized(connectorStateLock) {
      providerConnectors.values.map { it.toSnapshot() }
    }

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

      get("/api/v1/speedtest/servers") {
        call.respond(speedtestServers)
      }

      get("/api/v1/speedtest/config") {
        call.respond(speedtestConfig.get())
      }

      post("/api/v1/speedtest/config") {
        val req = catchingOrDefaultSuspend("speedtest:config.receive", SpeedtestConfigPatch()) { call.receive<SpeedtestConfigPatch>() }
        val current = speedtestConfig.get()
        val updated = current.copy(
          serverId = req.serverId?.trim()?.takeIf { it.isNotBlank() } ?: current.serverId,
          realMode = req.realMode ?: current.realMode,
          precisionRange = req.precisionRange ?: current.precisionRange
        )
        speedtestConfig.set(updated)
        call.respond(updated)
      }

      post("/api/v1/speedtest/start") {
        val req = catchingOrDefaultSuspend("speedtest:start.receive", SpeedtestControlRequest()) { call.receive<SpeedtestControlRequest>() }
        val current = speedtestConfig.get()
        val updatedConfig = current.copy(
          serverId = req.serverId?.trim()?.takeIf { it.isNotBlank() } ?: current.serverId,
          realMode = req.realMode ?: current.realMode,
          precisionRange = req.precisionRange ?: current.precisionRange
        )
        speedtestConfig.set(updatedConfig)
        speedtestAbort.set(false)

        // Cancel any in-flight engine run
        speedtestJob?.cancel()

        val live = speedtestLive.get().copy(
          running = true,
          phase = "PING",
          progress = 0,
          serverId = updatedConfig.serverId,
          error = null,
          updatedAt = System.currentTimeMillis()
        )
        speedtestLive.set(live)

        // When realMode is enabled, launch the OkHttp-based SpeedTestEngine
        if (updatedConfig.realMode) {
          speedtestJob = speedtestScope.launch {
            try {
              val result = speedtestEngine.execute(
                targets = SpeedTestEngine.Targets(),
                abortFlag = { speedtestAbort.get() },
                onProgress = SpeedTestEngine.ProgressCallback { phase, progress, pingMs, jitterMs, downloadMbps, uploadMbps ->
                  speedtestLive.set(
                    speedtestLive.get().copy(
                      running = true,
                      phase = phase,
                      progress = progress,
                      pingMs = pingMs ?: speedtestLive.get().pingMs,
                      jitterMs = jitterMs ?: speedtestLive.get().jitterMs,
                      downloadMbps = downloadMbps ?: speedtestLive.get().downloadMbps,
                      uploadMbps = uploadMbps ?: speedtestLive.get().uploadMbps,
                      updatedAt = System.currentTimeMillis()
                    )
                  )
                }
              )
              // Finalize live state with complete results
              speedtestLive.set(
                speedtestLive.get().copy(
                  running = false,
                  phase = "COMPLETE",
                  progress = 100,
                  pingMs = result.pingMs,
                  jitterMs = result.jitterMs,
                  downloadMbps = result.downloadMbps,
                  uploadMbps = result.uploadMbps,
                  lossPct = result.lossPct,
                  bufferbloat = result.bufferbloat,
                  error = null,
                  updatedAt = System.currentTimeMillis()
                )
              )
            } catch (e: SpeedTestEngine.SpeedTestAbortedException) {
              speedtestLive.set(
                speedtestLive.get().copy(
                  running = false,
                  phase = "ABORTED",
                  error = "aborted",
                  updatedAt = System.currentTimeMillis()
                )
              )
            } catch (e: Exception) {
              logEvent("error", mapOf("where" to "speedtest:engine", "message" to e.message))
              speedtestLive.set(
                speedtestLive.get().copy(
                  running = false,
                  phase = "ERROR",
                  error = e.message ?: "speedtest engine failed",
                  updatedAt = System.currentTimeMillis()
                )
              )
            }
          }
        }

        call.respond(mapOf("ok" to true, "config" to updatedConfig, "state" to live, "engineDriven" to updatedConfig.realMode))
      }

      post("/api/v1/speedtest/abort") {
        speedtestAbort.set(true)
        speedtestJob?.cancel()
        val updated = speedtestLive.get().copy(
          running = false,
          phase = "ABORTED",
          error = "aborted",
          updatedAt = System.currentTimeMillis()
        )
        speedtestLive.set(updated)
        call.respond(mapOf("ok" to true, "state" to updated))
      }

      post("/api/v1/speedtest/reset") {
        speedtestAbort.set(false)
        val cfg = speedtestConfig.get()
        val reset = SpeedtestLiveState(serverId = cfg.serverId)
        speedtestLive.set(reset)
        call.respond(mapOf("ok" to true, "state" to reset))
      }

      get("/api/v1/speedtest/live") {
        call.respond(speedtestLive.get())
      }

      post("/api/v1/speedtest/result") {
        val req = catchingOrDefaultSuspend("speedtest:result.receive", SpeedtestResultRequest()) { call.receive<SpeedtestResultRequest>() }
        val prev = speedtestLive.get()
        val updated = prev.copy(
          running = req.running ?: prev.running,
          phase = req.phase ?: prev.phase,
          progress = req.progress?.coerceIn(0, 100) ?: prev.progress,
          serverId = req.serverId?.takeIf { it.isNotBlank() } ?: prev.serverId,
          pingMs = req.pingMs ?: prev.pingMs,
          jitterMs = req.jitterMs ?: prev.jitterMs,
          downloadMbps = req.downloadMbps ?: prev.downloadMbps,
          uploadMbps = req.uploadMbps ?: prev.uploadMbps,
          lossPct = req.lossPct ?: prev.lossPct,
          bufferbloat = req.bufferbloat ?: prev.bufferbloat,
          error = req.error,
          updatedAt = System.currentTimeMillis()
        )
        speedtestLive.set(updated)
        call.respond(mapOf("ok" to true, "state" to updated))
      }

      get("/api/v1/speedtest/ping") {
        val now = System.currentTimeMillis()
        val prev = speedtestLive.get()
        speedtestLive.set(
          prev.copy(
            phase = if (prev.running) "PING" else prev.phase,
            updatedAt = now
          )
        )
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respond(mapOf("ok" to true, "serverTimeMs" to now))
      }

      get("/api/v1/speedtest/download") {
        val bytes = call.request.queryParameters["bytes"]?.toLongOrNull()?.coerceIn(1_024L, 250_000_000L) ?: 25_000_000L
        speedtestLive.set(
          speedtestLive.get().copy(
            running = true,
            phase = "DOWNLOAD",
            progress = 0,
            error = null,
            updatedAt = System.currentTimeMillis()
          )
        )
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondBytesWriter(contentType = ContentType.Application.OctetStream) {
          val chunk = ByteArray(64 * 1024)
          var sent = 0L
          while (sent < bytes) {
            if (speedtestAbort.get()) break
            val n = minOf(chunk.size.toLong(), bytes - sent).toInt()
            repeat(n) { idx ->
              chunk[idx] = ((sent + idx) and 0xFFL).toByte()
            }
            writeFully(chunk, 0, n)
            sent += n
            if (sent % (1024L * 1024L) == 0L) {
              val pct = ((sent.toDouble() / bytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
              speedtestLive.set(speedtestLive.get().copy(progress = pct, updatedAt = System.currentTimeMillis()))
            }
          }
          flush()
        }
      }

      post("/api/v1/speedtest/upload") {
        val startedAt = System.currentTimeMillis()
        speedtestLive.set(
          speedtestLive.get().copy(
            running = true,
            phase = "UPLOAD",
            error = null,
            updatedAt = startedAt
          )
        )
        val channel = call.receiveChannel()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          if (speedtestAbort.get()) break
          val read = channel.readAvailable(buffer, 0, buffer.size)
          if (read <= 0) break
          total += read.toLong()
        }
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val mbps = (total * 8.0) / (elapsedMs.toDouble() / 1000.0) / 1_000_000.0
        val prev = speedtestLive.get()
        speedtestLive.set(
          prev.copy(
            running = false,
            phase = if (speedtestAbort.get()) "ABORTED" else "COMPLETE",
            progress = if (speedtestAbort.get()) prev.progress else 100,
            uploadMbps = mbps,
            updatedAt = System.currentTimeMillis()
          )
        )
        call.respond(mapOf("ok" to true, "bytesReceived" to total, "elapsedMs" to elapsedMs, "uploadMbps" to mbps))
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
        var clients: List<com.netninja.gateway.g5ar.ClientDevice> = emptyList()
        var cell: com.netninja.gateway.g5ar.CellTelemetry? = null
        var sim: com.netninja.gateway.g5ar.SimInfo? = null
        var wifi: WifiApConfig? = null

        val canInfo = runCatching { withContext(Dispatchers.IO) { g5arApi.getGatewayInfo(activeSession) } }.onSuccess { info = it }.isSuccess
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
              canViewGatewayInfo = canInfo,
              canViewClients = canClients,
              canViewCellTelemetry = canCell,
              canViewSimInfo = canSim,
              canViewWifiConfig = canWifiRead,
              canSetWifiConfig = canWifiRead,
              canReboot = canInfo
            ),
            gatewayInfo = info,
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

      get("/api/openclaw/providers") {
        call.respond(snapshotConnectors())
      }

      get("/api/openclaw/providers/{provider}") {
        val provider = normalizeProviderKey(call.parameters["provider"]?.trim().orEmpty())
        if (provider.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider required"))
          return@get
        }
        call.respond(getOrCreateConnector(provider).toSnapshot())
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
        val connector = getOrCreateConnector(provider)
        synchronized(connectorStateLock) {
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
        synchronized(connectorStateLock) {
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

        val connector = getOrCreateConnector(provider)
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
          synchronized(connectorStateLock) {
            connector.lastError = "token_exchange_failed"
            connector.connected = false
            connector.updatedAt = System.currentTimeMillis()
          }
          logEvent("openclaw_provider_token_exchange_failed", mapOf("provider" to provider, "reason" to e.message))
          call.respond(HttpStatusCode.BadGateway, mapOf("error" to "token exchange failed"))
          return@post
        }

        if (status !in 200..299) {
          synchronized(connectorStateLock) {
            connector.lastError = "token_exchange_http_$status"
            connector.connected = false
            connector.updatedAt = System.currentTimeMillis()
          }
          logEvent("openclaw_provider_token_exchange_http_error", mapOf("provider" to provider, "status" to status))
          call.respond(HttpStatusCode.BadGateway, mapOf("error" to "token exchange failed", "status" to status))
          return@post
        }

        val parsed = runCatching { openClawJson.parseToJsonElement(body).jsonObject }.getOrNull()
        val accessToken = parsed?.get("access_token")?.jsonPrimitive?.contentOrNull
        val refreshToken = parsed?.get("refresh_token")?.jsonPrimitive?.contentOrNull
        if (accessToken.isNullOrBlank()) {
          synchronized(connectorStateLock) {
            connector.lastError = "token_missing_access_token"
            connector.connected = false
            connector.updatedAt = System.currentTimeMillis()
          }
          call.respond(HttpStatusCode.BadGateway, mapOf("error" to "provider token response missing access_token"))
          return@post
        }

        synchronized(connectorStateLock) {
          connector.accessToken = accessToken
          connector.refreshToken = refreshToken
          connector.connected = true
          connector.pendingState = null
          connector.lastError = null
          connector.updatedAt = System.currentTimeMillis()
        }
        openClawDashboard.restartGateway(provider)
        call.respond(getOrCreateConnector(provider).toSnapshot())
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
        val connector = getOrCreateConnector(provider)
        synchronized(connectorStateLock) {
          connector.authMode = "api_key"
          connector.apiKey = key
          connector.apiBaseUrl = req.apiBaseUrl?.trim().orEmpty().ifBlank { connector.apiBaseUrl }
          connector.discoveryUrl = req.discoveryUrl?.trim().orEmpty().ifBlank { connector.discoveryUrl }
          connector.webhookSecret = req.webhookSecret?.trim().orEmpty().ifBlank { connector.webhookSecret }
          connector.connected = true
          connector.lastError = null
          connector.updatedAt = System.currentTimeMillis()
        }
        openClawDashboard.restartGateway(provider)
        call.respond(getOrCreateConnector(provider).toSnapshot())
      }

      post("/api/openclaw/providers/{provider}/webhook") {
        val provider = normalizeProviderKey(call.parameters["provider"]?.trim().orEmpty())
        if (provider.isBlank()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider required"))
          return@post
        }

        val connector = getOrCreateConnector(provider)
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
        val entries = parseInboundMessages(provider, raw, openClawJson)
        if (entries.isEmpty()) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no supported messages in payload"))
          return@post
        }
        entries.forEach { (channel, body) ->
          openClawDashboard.addMessage(gateway = provider, channel = channel, body = body)
        }
        synchronized(connectorStateLock) {
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

        val connector = getOrCreateConnector(provider)
        val discoveryUrl = connector.discoveryUrl?.takeIf { it.isNotBlank() }
          ?: when (provider) {
            "telegram" -> connector.accessToken?.let { "https://api.telegram.org/bot$it/getUpdates?limit=100" }
            else -> connector.apiBaseUrl?.trimEnd('/')?.plus("/channels")
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
          synchronized(connectorStateLock) {
            connector.connected = false
            connector.lastError = "channel_discovery_failed"
            connector.updatedAt = System.currentTimeMillis()
          }
          logEvent("openclaw_provider_channel_discovery_failed", mapOf("provider" to provider, "reason" to e.message))
          call.respond(HttpStatusCode.BadGateway, mapOf("error" to "channel discovery failed"))
          return@get
        }

        if (status !in 200..299) {
          synchronized(connectorStateLock) {
            connector.connected = false
            connector.lastError = "channel_discovery_http_$status"
            connector.updatedAt = System.currentTimeMillis()
          }
          call.respond(HttpStatusCode.BadGateway, mapOf("error" to "channel discovery failed", "status" to status))
          return@get
        }

        val channels = parseChannels(provider, body, openClawJson)
        synchronized(connectorStateLock) {
          connector.connected = true
          connector.lastError = null
          connector.updatedAt = System.currentTimeMillis()
        }
        call.respond(ProviderChannelDiscoveryResponse(provider = provider, source = discoveryUrl, channels = channels))
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
        if (body.length > OPENCLAW_MAX_CHAT_CHARS) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "body too large"))
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
        if (command.length > OPENCLAW_MAX_COMMAND_CHARS) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Command too long"))
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
        if (body.length > OPENCLAW_MAX_CHAT_CHARS) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message body too large"))
          return@post
        }
        call.respond(openClawDashboard.addChatMessage(body = body, channel = ch))
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
        try {
          for (frame in incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            val validation = catchingOrNull("openclaw:parseValidateMessage", fields = mapOf("payloadLen" to text.length)) {
              validateOpenClawWsMessage(text, nodeId, openClawJson)
            }
            if (validation == null) {
              val error = OpenClawWsErrorFrame(
                code = "VALIDATION_FAILURE",
                message = "Failed to validate websocket payload."
              )
              outgoing.trySend(Frame.Text(openClawJson.encodeToString(OpenClawWsErrorFrame.serializer(), error)))
              close(CloseReason(CloseReason.Codes.UNEXPECTED_CONDITION, error.message.take(120)))
              return@webSocket
            }
            val msg = when (validation) {
              is OpenClawWsValidationResult.Valid -> validation.message
              is OpenClawWsValidationResult.Invalid -> {
                outgoing.trySend(Frame.Text(openClawJson.encodeToString(OpenClawWsErrorFrame.serializer(), validation.error)))
                close(CloseReason(validation.closeCode, validation.error.message.take(120)))
                return@webSocket
              }
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
                    nodes = OpenClawGatewayState.listNodes()
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
                  OpenClawGatewayState.updateResult(
                    nodeId = resolvedId,
                    payload = msg.payload,
                    requestId = msg.requestId,
                    success = msg.success,
                    error = msg.error
                  )
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
