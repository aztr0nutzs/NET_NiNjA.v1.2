@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.netninja.gateway.g5ar

import com.netninja.json.booleanOrNull
import com.netninja.json.contentOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class G5arSession(
  val token: String,
  val issuedAtMs: Long,
  val authHeader: String? = null,
  val cookieHeader: String? = null
)

@Serializable
data class GatewayInfo(
  val firmware: String? = null,
  val uiVersion: String? = null,
  val serial: String? = null,
  val uptime: String? = null,
  val raw: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class GatewaySignal(
  val status: String? = null,
  val bars: Int? = null,
  val raw: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class CellTelemetry(
  val rsrp: String? = null,
  val rsrq: String? = null,
  val sinr: String? = null,
  val band: String? = null,
  val pci: String? = null,
  val raw: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class SimInfo(
  val iccid: String? = null,
  val imei: String? = null,
  val imsi: String? = null,
  val raw: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ClientDevice(
  val name: String? = null,
  val ip: String? = null,
  val mac: String? = null,
  val signal: String? = null,
  val interfaceType: String? = null,
  val raw: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class WifiApConfig(
  val ssid24: String? = null,
  val ssid5: String? = null,
  val ssid6: String? = null,
  val pass24: String? = null,
  val pass5: String? = null,
  val pass6: String? = null,
  val enabled24: Boolean? = null,
  val enabled5: Boolean? = null,
  val enabled6: Boolean? = null,
  val raw: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class G5arCapabilities(
  val reachable: Boolean = false,
  val canViewGatewayInfo: Boolean = false,
  val canViewClients: Boolean = false,
  val canViewCellTelemetry: Boolean = false,
  val canViewSimInfo: Boolean = false,
  val canViewWifiConfig: Boolean = false,
  val canSetWifiConfig: Boolean = false,
  val canReboot: Boolean = false
)

internal fun JsonObject.getString(vararg keys: String): String? {
  for (key in keys) {
    val primitive = this[key]?.toPrimitiveStringOrNull() ?: continue
    if (primitive.isNotBlank()) return primitive
  }
  return null
}

internal fun JsonObject.getBoolean(vararg keys: String): Boolean? {
  for (key in keys) {
    val value = this[key] ?: continue
    when (value) {
      is kotlinx.serialization.json.JsonPrimitive -> {
        value.booleanOrNull?.let { return it }
        value.contentOrNull?.trim()?.lowercase()?.let {
          when (it) {
            "1", "true", "enabled", "on", "up" -> return true
            "0", "false", "disabled", "off", "down" -> return false
          }
        }
      }

      else -> Unit
    }
  }
  return null
}

internal fun JsonElement.toPrimitiveStringOrNull(): String? {
  val primitive = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
  return primitive.contentOrNull
}
