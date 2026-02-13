package com.netninja.gateway.g5ar

interface G5arApi {
  suspend fun discover(): Boolean
  suspend fun login(username: String, password: String): G5arSession
  suspend fun getGatewayInfo(session: G5arSession): GatewayInfo
  suspend fun getClients(session: G5arSession): List<ClientDevice>
  suspend fun getCellTelemetry(session: G5arSession): CellTelemetry
  suspend fun getSimInfo(session: G5arSession): SimInfo
  suspend fun getWifiConfig(session: G5arSession): WifiApConfig
  suspend fun setWifiConfig(session: G5arSession, config: WifiApConfig): WifiApConfig
  suspend fun reboot(session: G5arSession)
}
