package com.netninja.routercontrol

import android.content.Context
import com.netninja.gateway.g5ar.ClientDevice
import com.netninja.gateway.g5ar.G5arApi
import com.netninja.gateway.g5ar.G5arApiImpl
import com.netninja.gateway.g5ar.G5arCredentialStore
import com.netninja.gateway.g5ar.G5arSession
import com.netninja.gateway.g5ar.GatewayInfo
import com.netninja.gateway.g5ar.GatewaySignal
import com.netninja.gateway.g5ar.WifiApConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RouterControlGatewayClient(
  context: Context,
  initialBaseUrl: String = G5arApiImpl.DEFAULT_BASE_URL,
  private val apiFactory: (String) -> G5arApi = { base -> G5arApiImpl(baseUrl = base) }
) {
  private val creds = G5arCredentialStore(context.applicationContext)
  private val lock = Mutex()
  private val logs = ArrayDeque<String>()
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
  }

  @Volatile
  private var session: G5arSession? = null

  @Volatile
  private var baseUrl: String = normalizeBaseUrl(initialBaseUrl)

  @Volatile
  private var api: G5arApi = apiFactory(baseUrl)

  @Volatile
  private var version: JsonObject? = null

  @Volatile
  private var gatewayAll: JsonObject? = null

  @Volatile
  private var gatewaySignal: JsonObject? = null

  @Volatile
  private var cell: JsonObject? = null

  @Volatile
  private var clients: JsonArray = JsonArray(emptyList())

  @Volatile
  private var sim: JsonObject? = null

  @Volatile
  private var wifi: JsonObject? = null

  suspend fun handleHttp(
    method: String,
    path: String,
    body: JsonElement?,
    remember: Boolean = false
  ): JsonElement? = lock.withLock {
    val normalizedMethod = method.trim().uppercase()
    val normalizedPath = path.trim()

    when {
      normalizedMethod == "GET" && normalizedPath == "/TMI/v1/version" -> {
        val obj = fetchVersion()
        version = obj
        addLog("DISCOVER ok: /TMI/v1/version")
        obj
      }

      // Handle firmware v2 version endpoint.  This falls back to the same fetch logic
      // but targets the v2 path.
      normalizedMethod == "GET" && normalizedPath == "/TMI/v2/version" -> {
        val obj = fetchVersionV2()
        version = obj
        addLog("DISCOVER ok: /TMI/v2/version")
        obj
      }

      // Support v1 and v2 auth endpoints.  Return the real token rather than a placeholder.
      (normalizedMethod == "POST" && (normalizedPath == "/TMI/v1/auth/login" || normalizedPath == "/TMI/v2/auth/login")) -> {
        val payload = body as? JsonObject ?: JsonObject(emptyMap())
        val user = payload["username"]?.asString()?.trim().orEmpty().ifBlank { "admin" }
        val password = payload["password"]?.asString().orEmpty()
        if (password.isBlank()) throw IOException("Password is required")
        // Perform the login via the API and capture the returned session.
        session = withContext(Dispatchers.IO) { api.login(user, password) }
        if (remember) {
          creds.save(user, password)
        } else {
          creds.clear()
        }
        addLog("AUTH ok")
        // Extract the real bearer token for the web layer.  Prefer the auth header,
        // removing the Bearer prefix, then fall back to the raw token.  If no token is
        // available return an empty string.  This allows the JS to detect a successful login.
        val token = session?.authHeader?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?: session?.token
          ?: ""
        buildJsonObject { put("token", token) }
      }

      normalizedMethod == "GET" && normalizedPath == "/TMI/v1/gateway?get=all" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getGatewayInfo(s) }
        gatewayAll = gatewayInfoToJson(data)
        gatewayAll
      }

      // Support v2 gateway all endpoint.
      normalizedMethod == "GET" && normalizedPath == "/TMI/v2/gateway?get=all" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getGatewayInfo(s) }
        gatewayAll = gatewayInfoToJson(data)
        gatewayAll
      }

      normalizedMethod == "GET" && normalizedPath == "/TMI/v1/gateway?get=signal" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getGatewaySignal(s) }
        gatewaySignal = gatewaySignalToJson(data)
        gatewaySignal
      }

      // Support v2 gateway signal endpoint.
      normalizedMethod == "GET" && normalizedPath == "/TMI/v2/gateway?get=signal" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getGatewaySignal(s) }
        gatewaySignal = gatewaySignalToJson(data)
        gatewaySignal
      }

      normalizedMethod == "GET" && normalizedPath == "/TMI/v1/network/telemetry?get=cell" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getCellTelemetry(s) }
        cell = data.raw
        cell
      }

      // Support v2 cell telemetry endpoint.
      normalizedMethod == "GET" && normalizedPath == "/TMI/v2/network/telemetry?get=cell" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getCellTelemetry(s) }
        cell = data.raw
        cell
      }

      normalizedMethod == "GET" && normalizedPath == "/TMI/v1/network/telemetry?get=clients" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getClients(s) }
        clients = JsonArray(data.map { clientToJson(it) })
        clients
      }

      // Support v2 clients telemetry endpoint.
      normalizedMethod == "GET" && normalizedPath == "/TMI/v2/network/telemetry?get=clients" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getClients(s) }
        clients = JsonArray(data.map { clientToJson(it) })
        clients
      }

      normalizedMethod == "GET" && normalizedPath == "/TMI/v1/network/telemetry?get=sim" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getSimInfo(s) }
        sim = data.raw
        sim
      }

      // Support v2 SIM telemetry endpoint.
      normalizedMethod == "GET" && normalizedPath == "/TMI/v2/network/telemetry?get=sim" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getSimInfo(s) }
        sim = data.raw
        sim
      }

      normalizedMethod == "GET" && normalizedPath == "/TMI/v1/network/configuration/v2?get=ap" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getWifiConfig(s) }
        wifi = wifiToJson(data)
        wifi
      }

      // Support v2 Wi‑Fi configuration (v2) endpoint.
      normalizedMethod == "GET" && normalizedPath == "/TMI/v2/network/configuration/v2?get=ap" -> {
        val s = requireSession()
        val data = withContext(Dispatchers.IO) { api.getWifiConfig(s) }
        wifi = wifiToJson(data)
        wifi
      }

      normalizedMethod == "POST" && normalizedPath == "/TMI/v1/network/configuration/v2?set=ap" -> {
        val s = requireSession()
        val cfg = parseWifiConfig(body)
        val updated = withContext(Dispatchers.IO) { api.setWifiConfig(s, cfg) }
        wifi = wifiToJson(updated)
        addLog("ACTION wifi apply")
        wifi
      }

      // Support v2 Wi‑Fi configuration set endpoint.
      normalizedMethod == "POST" && normalizedPath == "/TMI/v2/network/configuration/v2?set=ap" -> {
        val s = requireSession()
        val cfg = parseWifiConfig(body)
        val updated = withContext(Dispatchers.IO) { api.setWifiConfig(s, cfg) }
        wifi = wifiToJson(updated)
        addLog("ACTION wifi apply")
        wifi
      }

      normalizedMethod == "POST" && normalizedPath == "/TMI/v1/gateway/reset?set=reboot" -> {
        val s = requireSession()
        withContext(Dispatchers.IO) { api.reboot(s) }
        addLog("ACTION reboot requested")
        buildJsonObject { put("ok", true) }
      }

      // Support v2 reboot endpoint.
      normalizedMethod == "POST" && normalizedPath == "/TMI/v2/gateway/reset?set=reboot" -> {
        val s = requireSession()
        withContext(Dispatchers.IO) { api.reboot(s) }
        addLog("ACTION reboot requested")
        buildJsonObject { put("ok", true) }
      }

      else -> throw IOException("Unsupported route: $normalizedMethod $normalizedPath")
    }
  }

  suspend fun setBaseUrl(nextBaseUrl: String?) = lock.withLock {
    val normalized = normalizeBaseUrl(nextBaseUrl)
    if (baseUrl != normalized) {
      baseUrl = normalized
      api = apiFactory(baseUrl)
      session = null
      version = null
      gatewayAll = null
      gatewaySignal = null
      cell = null
      clients = JsonArray(emptyList())
      sim = null
      wifi = null
      addLog("BASE_URL set to $baseUrl")
    }
  }

  suspend fun refreshSnapshot() = lock.withLock {
    val s = requireSession()
    gatewayAll = runCatching { withContext(Dispatchers.IO) { gatewayInfoToJson(api.getGatewayInfo(s)) } }.getOrNull()
    gatewaySignal = runCatching { withContext(Dispatchers.IO) { gatewaySignalToJson(api.getGatewaySignal(s)) } }.getOrNull()
    cell = runCatching { withContext(Dispatchers.IO) { api.getCellTelemetry(s).raw } }.getOrNull()
    clients = runCatching {
      withContext(Dispatchers.IO) { JsonArray(api.getClients(s).map { clientToJson(it) }) }
    }.getOrDefault(JsonArray(emptyList()))
    sim = runCatching { withContext(Dispatchers.IO) { api.getSimInfo(s).raw } }.getOrNull()
    wifi = runCatching { withContext(Dispatchers.IO) { wifiToJson(api.getWifiConfig(s)) } }.getOrNull()
    addLog("REFRESH ok")
  }

  suspend fun logout() = lock.withLock {
    session = null
    creds.clear()
    addLog("AUTH cleared (logout)")
  }

  suspend fun buildState(error: String? = null): JsonObject = lock.withLock {
    buildJsonObject {
      put("baseUrl", baseUrl)
      // Propagate the real token to the UI.  When a session exists, extract the bearer
      // token from the authHeader (removing the Bearer prefix) or fall back to the
      // raw session token.  Otherwise return null.
      if (session != null) {
        val raw = session?.authHeader?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
          ?.takeIf { it?.isNotEmpty() == true }
          ?: session?.token
        if (!raw.isNullOrEmpty()) {
          put("token", JsonPrimitive(raw))
        } else {
          put("token", JsonNull)
        }
      } else {
        put("token", JsonNull)
      }
      put("last", buildJsonObject {
        put("version", version ?: JsonObject(emptyMap()))
        put("gatewayAll", gatewayAll ?: JsonObject(emptyMap()))
        put("gatewaySignal", gatewaySignal ?: JsonObject(emptyMap()))
        put("cell", cell ?: JsonObject(emptyMap()))
        put("clients", clients)
        put("sim", sim ?: JsonObject(emptyMap()))
        put("wifi", wifi ?: JsonObject(emptyMap()))
      })
      put("logs", JsonArray(logs.map { JsonPrimitive(it) }))
      error?.let { put("error", it) }
    }
  }

  private suspend fun requireSession(): G5arSession {
    session?.let { return it }
    val saved = creds.load() ?: throw IOException("Login required")
    val newSession = withContext(Dispatchers.IO) { api.login(saved.first, saved.second) }
    session = newSession
    return newSession
  }

  private suspend fun fetchVersion(): JsonObject = withContext(Dispatchers.IO) {
    val conn = (URL(baseUrl.trimEnd('/') + "/TMI/v1/version").openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 2500
      readTimeout = 4500
      setRequestProperty("Accept", "application/json")
      // Forward session cookies for consistency with authenticated flow
      session?.cookieHeader?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
    }
    try {
      val code = conn.responseCode
      val stream = if (code >= 400) conn.errorStream else conn.inputStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw IOException("Version probe failed with HTTP $code")
      val parsed = if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
      parsed.jsonObject
    } finally {
      conn.disconnect()
    }
  }

  /**
   * Fetch the router version from the v2 firmware path.  This uses the same logic
   * as [fetchVersion] but targets `/TMI/v2/version`.  We avoid using the API layer
   * because it hardcodes the v1 path.
   */
  private suspend fun fetchVersionV2(): JsonObject = withContext(Dispatchers.IO) {
    val conn =
      (URL(baseUrl.trimEnd('/') + "/TMI/v2/version").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 2500
        readTimeout = 4500
        setRequestProperty("Accept", "application/json")
        // Forward session cookies for consistency with authenticated flow
        session?.cookieHeader?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
      }
    try {
      val code = conn.responseCode
      val stream = if (code >= 400) conn.errorStream else conn.inputStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw IOException("Version probe failed with HTTP $code")
      val parsed = if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
      parsed.jsonObject
    } finally {
      conn.disconnect()
    }
  }

  private fun parseWifiConfig(body: JsonElement?): WifiApConfig {
    val obj = body as? JsonObject ?: JsonObject(emptyMap())
    return WifiApConfig(
      ssid24 = obj["ssid24"]?.asString(),
      ssid5 = obj["ssid5"]?.asString(),
      ssid6 = obj["ssid6"]?.asString(),
      pass24 = obj["pass24"]?.asString(),
      pass5 = obj["pass5"]?.asString(),
      pass6 = obj["pass6"]?.asString(),
      enabled24 = obj["enabled24"]?.asBoolean(),
      enabled5 = obj["enabled5"]?.asBoolean(),
      enabled6 = obj["enabled6"]?.asBoolean(),
      raw = obj
    )
  }

  private fun gatewayInfoToJson(info: GatewayInfo): JsonObject {
    val base = info.raw.toMutableMap()
    info.firmware?.let { base.putIfAbsent("firmware", JsonPrimitive(it)) }
    info.uiVersion?.let { base.putIfAbsent("uiVersion", JsonPrimitive(it)) }
    info.serial?.let { base.putIfAbsent("serial", JsonPrimitive(it)) }
    info.uptime?.let { base.putIfAbsent("uptime", JsonPrimitive(it)) }
    return JsonObject(base)
  }

  private fun gatewaySignalToJson(signal: GatewaySignal): JsonObject {
    val base = signal.raw.toMutableMap()
    signal.status?.let { base.putIfAbsent("status", JsonPrimitive(it)) }
    signal.bars?.let { base.putIfAbsent("bars", JsonPrimitive(it)) }
    return JsonObject(base)
  }

  private fun wifiToJson(config: WifiApConfig): JsonObject {
    val base = config.raw.toMutableMap()
    config.ssid24?.let { base.putIfAbsent("ssid24", JsonPrimitive(it)) }
    config.ssid5?.let { base.putIfAbsent("ssid5", JsonPrimitive(it)) }
    config.ssid6?.let { base.putIfAbsent("ssid6", JsonPrimitive(it)) }
    config.pass24?.let { base.putIfAbsent("pass24", JsonPrimitive(it)) }
    config.pass5?.let { base.putIfAbsent("pass5", JsonPrimitive(it)) }
    config.pass6?.let { base.putIfAbsent("pass6", JsonPrimitive(it)) }
    config.enabled24?.let { base.putIfAbsent("enabled24", JsonPrimitive(it)) }
    config.enabled5?.let { base.putIfAbsent("enabled5", JsonPrimitive(it)) }
    config.enabled6?.let { base.putIfAbsent("enabled6", JsonPrimitive(it)) }
    return JsonObject(base)
  }

  private fun clientToJson(client: ClientDevice): JsonObject {
    val base = client.raw.toMutableMap()
    client.name?.let { base.putIfAbsent("name", JsonPrimitive(it)) }
    client.ip?.let { base.putIfAbsent("ip", JsonPrimitive(it)) }
    client.mac?.let { base.putIfAbsent("mac", JsonPrimitive(it)) }
    client.signal?.let { base.putIfAbsent("rssi", JsonPrimitive(it)) }
    client.interfaceType?.let { base.putIfAbsent("interfaceType", JsonPrimitive(it)) }
    return JsonObject(base)
  }

  private fun addLog(msg: String) {
    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    logs.addFirst("$ts | $msg")
    while (logs.size > 200) logs.removeLast()
  }

  private fun resolveSessionToken(activeSession: G5arSession?): String? {
    val authToken = activeSession?.authHeader
      ?.trim()
      ?.takeIf { it.isNotBlank() }
      ?.let { auth ->
        if (auth.startsWith("Bearer ", ignoreCase = true)) {
          auth.substring("Bearer ".length).trim()
        } else {
          auth
        }
      }
      ?.takeIf { it.isNotBlank() }
    return authToken ?: activeSession?.token?.takeIf { it.isNotBlank() }
  }

  private fun normalizeBaseUrl(raw: String?): String {
    val cleaned = raw?.trim().orEmpty().ifBlank { G5arApiImpl.DEFAULT_BASE_URL }
    val withScheme = if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) cleaned else "http://$cleaned"
    return withScheme.trimEnd('/')
  }
}

private fun JsonElement.asString(): String? {
  val primitive = this as? JsonPrimitive ?: return null
  return primitive.contentOrNull
}

private fun JsonElement.asBoolean(): Boolean? {
  val primitive = this as? JsonPrimitive ?: return null
  return when (primitive.contentOrNull?.trim()?.lowercase()) {
    "1", "true", "on", "enabled", "up" -> true
    "0", "false", "off", "disabled", "down" -> false
    else -> null
  }
}
