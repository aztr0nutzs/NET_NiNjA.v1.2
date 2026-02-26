package com.netninja.openclaw

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class OpenClawNodeSnapshot(
  val id: String,
  val capabilities: List<String> = emptyList(),
  val lastSeen: Long = 0L,
  val lastResult: String? = null
)

class NodeSession(
  val nodeId: String,
  val send: (String) -> Unit
)

object OpenClawGatewayState {
  private val startedAtMs = System.currentTimeMillis()
  private val nodes = ConcurrentHashMap<String, OpenClawNodeSnapshot>()
  private val sessions = ConcurrentHashMap<String, NodeSession>()

  fun register(nodeId: String, capabilities: List<String>, session: NodeSession) {
    val now = System.currentTimeMillis()
    nodes[nodeId] = OpenClawNodeSnapshot(
      id = nodeId,
      capabilities = capabilities,
      lastSeen = now,
      lastResult = nodes[nodeId]?.lastResult
    )
    sessions[nodeId] = session
  }

  fun updateHeartbeat(nodeId: String) {
    val current = nodes[nodeId] ?: return
    nodes[nodeId] = current.copy(lastSeen = System.currentTimeMillis())
  }

  fun updateResult(
    nodeId: String,
    payload: String?,
    requestId: String?,
    success: Boolean?,
    error: String?
  ) {
    val current = nodes[nodeId] ?: OpenClawNodeSnapshot(id = nodeId, lastSeen = System.currentTimeMillis())
    val summary = when {
      !error.isNullOrBlank() -> "ERROR: $error"
      success == true -> "OK${requestId?.let { " ($it)" } ?: ""}${payload?.let { ": $it" } ?: ""}"
      !payload.isNullOrBlank() -> payload
      else -> "RESULT${requestId?.let { " ($it)" } ?: ""}"
    }
    nodes[nodeId] = current.copy(lastSeen = System.currentTimeMillis(), lastResult = summary)
  }

  fun listNodes(): List<OpenClawNodeSnapshot> = nodes.values.sortedBy { it.id }

  fun nodeCount(): Int = nodes.size

  fun uptimeMs(): Long = System.currentTimeMillis() - startedAtMs
}

