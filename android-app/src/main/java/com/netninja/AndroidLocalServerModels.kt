@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.netninja

import android.os.Build
import kotlinx.serialization.Serializable
import com.netninja.openclaw.OpenClawNodeSnapshot

@Serializable
data class Device(
  val id: String,
  val ip: String,
  val name: String? = null,
  val online: Boolean,
  val lastSeen: Long,
  val mac: String? = null,
  val hostname: String? = null,
  val vendor: String? = null,
  val os: String? = null,
  val owner: String? = null,
  val room: String? = null,
  val note: String? = null,
  val trust: String? = null,
  val type: String? = null,
  val status: String? = null,
  val via: String? = null,
  val signal: String? = null,
  val activityToday: String? = null,
  val traffic: String? = null,
  val openPorts: List<Int> = emptyList()
)

@Serializable data class DeviceEvent(val deviceId: String, val ts: Long, val event: String)
@Serializable data class ScanRequest(val subnet: String? = null, val timeoutMs: Int? = 300)
@Serializable data class ActionRequest(val ip: String? = null, val mac: String? = null, val url: String? = null)
@Serializable data class ScheduleRequest(val subnet: String? = null, val freq: String? = null)
@Serializable data class RuleRequest(val match: String? = null, val action: String? = null)
@Serializable data class RuleEntry(val match: String, val action: String)

@Serializable
data class DeviceMetaUpdate(
  val name: String? = null,
  val owner: String? = null,
  val room: String? = null,
  val note: String? = null,
  val trust: String? = null,
  val type: String? = null,
  val status: String? = null,
  val via: String? = null,
  val signal: String? = null,
  val activityToday: String? = null,
  val traffic: String? = null
)

@Serializable data class DeviceActionRequest(val action: String? = null)
@Serializable data class PortScanRequest(val ip: String? = null, val timeoutMs: Int? = null)

@Serializable
data class PermissionSnapshot(
  val nearbyWifi: Boolean? = null,
  val fineLocation: Boolean = false,
  val coarseLocation: Boolean = false,
  val networkState: Boolean = false,
  val wifiState: Boolean = false,
  val permissionPermanentlyDenied: Boolean = false
)

@Serializable
data class ScanPreconditions(
  val ready: Boolean,
  val blocker: String? = null,
  val reason: String? = null,
  val fixAction: String? = null,
  val androidVersion: Int = Build.VERSION.SDK_INT,
  val wifiEnabled: Boolean,
  val locationEnabled: Boolean,
  val permissions: PermissionSnapshot,
  val subnet: String? = null
)

@Serializable
data class ScanProgress(
  val progress: Int = 0,
  val phase: String = "IDLE",
  val message: String? = null,
  val fixAction: String? = null,
  val networks: Int = 0,
  val devices: Int = 0,
  val rssiDbm: Double? = null,
  val ssid: String? = null,
  val bssid: String? = null,
  val subnet: String? = null,
  val gateway: String? = null,
  val linkUp: Boolean = true,
  val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ApiError(val ok: Boolean = false, val error: String)

@Serializable
data class RouterInfo(
  val ok: Boolean,
  val gatewayIp: String? = null,
  val mac: String? = null,
  val vendor: String? = null,
  val openTcpPorts: List<Int> = emptyList(),
  val adminUrls: List<String> = emptyList(),
  val note: String? = null
)

@Serializable data class PermissionsActionRequest(val action: String? = null, val context: String? = null)

@Serializable
data class PermissionsActionResponse(
  val ok: Boolean,
  val message: String,
  val platform: String = "android",
  val details: Map<String, String?> = emptyMap()
)

@Serializable data class OpenClawStatus(val nodes: Int, val uptimeMs: Long)

@Serializable
data class OpenClawWsMessage(
  val type: String,
  val protocolVersion: Int? = null,
  val nodeId: String? = null,
  val capabilities: List<String> = emptyList(),
  val payload: String? = null,
  val requestId: String? = null,
  val success: Boolean? = null,
  val error: String? = null
)

@Serializable
data class OpenClawGatewaySnapshot(
  val nodes: List<OpenClawNodeSnapshot>,
  val uptimeMs: Long = 0L,
  val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class OpenClawStatsResponse(
  val uptimeMs: Long,
  val nodeCount: Int
)

@Serializable
data class MetricsResponse(
  val uptimeMs: Long,
  val memTotal: Long,
  val memUsed: Long,
  val devicesTotal: Int,
  val devicesOnline: Int,
  val lastScanAt: Long? = null
)

