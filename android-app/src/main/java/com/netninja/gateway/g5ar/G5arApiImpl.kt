@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.netninja.gateway.g5ar

import com.netninja.network.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL

class G5arApiImpl(
  private val baseUrl: String = DEFAULT_BASE_URL,
  private val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 150, maxDelayMs = 500)
) : G5arApi {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
  }

  @Volatile
  private var lastLoginCredentials: Pair<String, String>? = null

  override suspend fun discover(): Boolean = withContext(Dispatchers.IO) {
    try {
      val response = request("GET", "/TMI/v1/version")
      response.code in 200..299
    } catch (_: SocketTimeoutException) {
      false
    } catch (_: IOException) {
      false
    }
  }

  override suspend fun login(username: String, password: String): G5arSession = withContext(Dispatchers.IO) {
    val attempts = listOf(
      LoginAttempt.Json(
        body = buildJsonObject {
          put("username", username)
          put("password", password)
        }
      ),
      LoginAttempt.Json(
        body = buildJsonObject {
          put("user", username)
          put("password", password)
        }
      ),
      LoginAttempt.Json(
        body = buildJsonObject {
          put("auth", buildJsonObject {
            put("username", username)
            put("password", password)
          })
        }
      ),
      LoginAttempt.Form(
        body = formBody(mapOf("username" to username, "password" to password))
      ),
      LoginAttempt.Form(
        body = formBody(mapOf("user" to username, "password" to password))
      )
    )

    var lastFailure: HttpResult? = null
    for (attempt in attempts) {
      val response = when (attempt) {
        is LoginAttempt.Json -> request(
          method = "POST",
          path = "/TMI/v1/auth/login",
          payload = attempt.body,
          contentType = "application/json"
        )

        is LoginAttempt.Form -> request(
          method = "POST",
          path = "/TMI/v1/auth/login",
          rawBody = attempt.body,
          contentType = "application/x-www-form-urlencoded"
        )
      }

      if (response.code !in 200..299) {
        lastFailure = response
        continue
      }

      val parsedBody = parseBody(response.body)
      val token = extractToken(parsedBody)
      val authHeader = extractAuthHeader(response)
      val cookieHeader = extractCookieHeader(response)
      val resolvedToken = token
        ?: authHeader?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
        ?: cookieHeader
          ?.substringBefore(';')
          ?.substringAfter('=', missingDelimiterValue = "")
          ?.trim()
          ?.takeIf { it.isNotBlank() }

      if (!resolvedToken.isNullOrBlank()) {
        lastLoginCredentials = username to password
        return@withContext G5arSession(
          token = resolvedToken,
          issuedAtMs = System.currentTimeMillis(),
          authHeader = authHeader,
          cookieHeader = cookieHeader
        )
      }
      lastFailure = response
    }

    val fail = lastFailure
    val reason = fail?.body?.take(200)?.ifBlank { "<empty>" } ?: "<no response>"
    throw IOException("Login failed: gateway did not return a token (last HTTP ${fail?.code ?: -1}, body=$reason)")
  }

  override suspend fun getGatewayInfo(session: G5arSession): GatewayInfo = withAuthRetry(session) {
    val body = getObject("/TMI/v1/gateway?get=all", it)
    GatewayInfo(
      firmware = body.getString("firmware", "firmwareVersion", "swVersion", "version"),
      uiVersion = body.getString("uiVersion", "webUiVersion"),
      serial = body.getString("serial", "serialNumber", "sn"),
      uptime = body.getString("uptime", "upTime"),
      raw = body
    )
  }

  override suspend fun getGatewaySignal(session: G5arSession): GatewaySignal = withAuthRetry(session) {
    val body = getObject("/TMI/v1/gateway?get=signal", it)
    GatewaySignal(
      status = body.getString("status", "connectionStatus", "state"),
      bars = body.getString("bars", "signalBars", "barsCount", "signal")?.toIntOrNull(),
      raw = body
    )
  }

  override suspend fun getClients(session: G5arSession): List<ClientDevice> = withAuthRetry(session) {
    val body = getElement("/TMI/v1/network/telemetry?get=clients", it)
    parseClients(body)
  }

  override suspend fun getCellTelemetry(session: G5arSession): CellTelemetry = withAuthRetry(session) {
    val body = getObject("/TMI/v1/network/telemetry?get=cell", it)
    CellTelemetry(
      rsrp = body.getString("rsrp", "RSRP"),
      rsrq = body.getString("rsrq", "RSRQ"),
      sinr = body.getString("sinr", "SINR"),
      band = body.getString("band", "lteBand", "nrBand"),
      pci = body.getString("pci", "PCI"),
      raw = body
    )
  }

  override suspend fun getSimInfo(session: G5arSession): SimInfo = withAuthRetry(session) {
    val body = getObject("/TMI/v1/network/telemetry?get=sim", it)
    SimInfo(
      iccid = body.getString("iccid", "ICCID"),
      imei = body.getString("imei", "IMEI"),
      imsi = body.getString("imsi", "IMSI"),
      raw = body
    )
  }

  override suspend fun getWifiConfig(session: G5arSession): WifiApConfig = withAuthRetry(session) {
    val body = getObject("/TMI/v1/network/configuration/v2?get=ap", it)
    WifiApConfig(
      ssid24 = body.getString("ssid24", "ssid_2g", "ssid2g", "ssid24g"),
      ssid5 = body.getString("ssid5", "ssid_5g", "ssid5g"),
      ssid6 = body.getString("ssid6", "ssid_6g", "ssid6g"),
      pass24 = body.getString("pass24", "password24", "wpaKey24", "key24"),
      pass5 = body.getString("pass5", "password5", "wpaKey5", "key5"),
      pass6 = body.getString("pass6", "password6", "wpaKey6", "key6"),
      enabled24 = body.getBoolean("enabled24", "radio24Enabled", "radio2g", "enable2g"),
      enabled5 = body.getBoolean("enabled5", "radio5Enabled", "radio5g", "enable5g"),
      enabled6 = body.getBoolean("enabled6", "radio6Enabled", "radio6g", "enable6g"),
      raw = body
    )
  }

  override suspend fun setWifiConfig(session: G5arSession, config: WifiApConfig): WifiApConfig = withAuthRetry(session) {
    val payload = if (config.raw.isNotEmpty()) {
      config.raw.toMutableMap().apply {
        config.ssid24?.let { put("ssid24", JsonPrimitive(it)) }
        config.ssid5?.let { put("ssid5", JsonPrimitive(it)) }
        config.ssid6?.let { put("ssid6", JsonPrimitive(it)) }
        config.pass24?.let { put("pass24", JsonPrimitive(it)) }
        config.pass5?.let { put("pass5", JsonPrimitive(it)) }
        config.pass6?.let { put("pass6", JsonPrimitive(it)) }
        config.enabled24?.let { put("enabled24", JsonPrimitive(it)) }
        config.enabled5?.let { put("enabled5", JsonPrimitive(it)) }
        config.enabled6?.let { put("enabled6", JsonPrimitive(it)) }
      }.let { JsonObject(it) }
    } else {
      buildJsonObject {
        config.ssid24?.let { put("ssid24", it) }
        config.ssid5?.let { put("ssid5", it) }
        config.ssid6?.let { put("ssid6", it) }
        config.pass24?.let { put("pass24", it) }
        config.pass5?.let { put("pass5", it) }
        config.pass6?.let { put("pass6", it) }
        config.enabled24?.let { put("enabled24", it) }
        config.enabled5?.let { put("enabled5", it) }
        config.enabled6?.let { put("enabled6", it) }
      }
    }
    val response = request("POST", "/TMI/v1/network/configuration/v2?set=ap", session = it, payload = payload)
    if (response.code !in 200..299) {
      throw IOException("Set Wi-Fi config failed with HTTP ${response.code}")
    }
    getWifiConfig(it)
  }

  override suspend fun reboot(session: G5arSession) {
    withAuthRetry(session) {
      val response = request("POST", "/TMI/v1/gateway/reset?set=reboot", session = it)
      if (response.code !in 200..299) {
        throw IOException("Reboot failed with HTTP ${response.code}")
      }
      Unit
    }
  }

  private suspend fun <T> withAuthRetry(session: G5arSession, block: suspend (G5arSession) -> T): T {
    return try {
      block(session)
    } catch (e: UnauthorizedException) {
      val creds = lastLoginCredentials ?: throw e
      val refreshed = login(creds.first, creds.second)
      block(refreshed)
    }
  }

  private suspend fun getObject(path: String, session: G5arSession): JsonObject {
    return getElement(path, session).jsonObject
  }

  private suspend fun getElement(path: String, session: G5arSession): JsonElement {
    val response = retryPolicy.execute("g5ar:get:$path") {
      request("GET", path, session = session)
    }.getOrThrow()
    if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) throw UnauthorizedException()
    if (response.code !in 200..299) throw IOException("GET $path failed with HTTP ${response.code}")
    return parseBody(response.body)
  }

  private suspend fun request(
    method: String,
    path: String,
    session: G5arSession? = null,
    payload: JsonObject? = null,
    rawBody: String? = null,
    contentType: String = "application/json"
  ): HttpResult {
    return withContext(Dispatchers.IO) {
      val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("Accept", "application/json")
        val authHeader = session?.authHeader?.takeIf { it.isNotBlank() }
        val token = session?.token?.takeIf { it.isNotBlank() }
        if (authHeader != null) {
          setRequestProperty("Authorization", authHeader)
        } else if (token != null && session?.cookieHeader == null) {
          setRequestProperty("Authorization", "Bearer $token")
        }
        session?.cookieHeader?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
      }
      try {
        val bodyToWrite = rawBody ?: payload?.let { json.encodeToString(JsonObject.serializer(), it) }
        if (bodyToWrite != null) {
          conn.doOutput = true
          conn.setRequestProperty("Content-Type", contentType)
          conn.outputStream.use { out ->
            out.write(bodyToWrite.toByteArray())
          }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        HttpResult(code = code, body = body, headers = conn.headerFields)
      } finally {
        conn.disconnect()
      }
    }
  }

  private fun parseBody(raw: String): JsonElement {
    return if (raw.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(raw)
  }

  private fun parseClients(element: JsonElement): List<ClientDevice> {
    val array = when (element) {
      is JsonArray -> element
      is JsonObject -> {
        val clients = element["clients"]
        if (clients is JsonArray) clients else JsonArray(emptyList())
      }

      else -> JsonArray(emptyList())
    }

    return array.mapNotNull { item ->
      val obj = item as? JsonObject ?: return@mapNotNull null
      ClientDevice(
        name = obj.getString("name", "hostname", "host"),
        ip = obj.getString("ip", "ipAddress"),
        mac = obj.getString("mac", "macAddress"),
        signal = obj.getString("rssi", "signal", "signalStrength"),
        interfaceType = obj.getString("interfaceType", "ifType", "network"),
        raw = obj
      )
    }
  }

  private fun extractToken(element: JsonElement): String? {
    when (element) {
      is JsonObject -> {
        element.getString("token", "jwt", "access_token")?.let { return it }
        for ((_, value) in element) {
          extractToken(value)?.let { return it }
        }
      }

      is JsonArray -> {
        for (value in element) {
          extractToken(value)?.let { return it }
        }
      }

      else -> Unit
    }
    return null
  }

  private fun formBody(params: Map<String, String>): String {
    return params.entries.joinToString("&") {
      "${URLEncoder.encode(it.key, Charsets.UTF_8.name())}=${URLEncoder.encode(it.value, Charsets.UTF_8.name())}"
    }
  }

  private sealed class LoginAttempt {
    data class Json(val body: JsonObject) : LoginAttempt()
    data class Form(val body: String) : LoginAttempt()
  }

  private fun extractAuthHeader(response: HttpResult): String? {
    val candidates = listOf("Authorization", "X-Auth-Token", "Token")
    return candidates.firstNotNullOfOrNull { key ->
      response.firstHeader(key)?.trim()?.takeIf { it.isNotBlank() }
    }
  }

  private fun extractCookieHeader(response: HttpResult): String? {
    val cookies = response.headerValues("Set-Cookie")
      .mapNotNull { it.substringBefore(';').trim().takeIf { value -> value.contains('=') } }
    if (cookies.isEmpty()) return null
    return cookies.joinToString("; ")
  }

  private data class HttpResult(
    val code: Int,
    val body: String,
    val headers: Map<String?, List<String>> = emptyMap()
  ) {
    fun firstHeader(name: String): String? = headerValues(name).firstOrNull()

    fun headerValues(name: String): List<String> {
      return headers.entries.firstOrNull { (key, _) -> key?.equals(name, ignoreCase = true) == true }?.value.orEmpty()
    }
  }

  private class UnauthorizedException : IOException("Unauthorized")

  companion object {
    const val DEFAULT_BASE_URL = "http://192.168.12.1"
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 4500
  }
}
