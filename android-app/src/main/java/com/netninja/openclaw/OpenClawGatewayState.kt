package com.netninja.openclaw

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

data class OpenClawNode(
  val id: String,
  val capabilities: List<String> = emptyList(),
  val session: NodeSession,
  @Volatile var lastSeen: Long = System.currentTimeMillis(),
  @Volatile var lastResult: String? = null
)

@Serializable
data class OpenClawNodeSnapshot(
  val id: String,
  val capabilities: List<String>,
  val lastSeen: Long,
  val lastResult: String?
)

object OpenClawGatewayState {
  private val startTimeMs = System.currentTimeMillis()
  private val nodes = ConcurrentHashMap<String, OpenClawNode>()

  fun register(nodeId: String, capabilities: List<String>, session: NodeSession) {
    nodes[nodeId] = OpenClawNode(
      id = nodeId,
      capabilities = capabilities,
      session = session,
      lastSeen = System.currentTimeMillis()
    )
  }

  fun updateHeartbeat(nodeId: String) {
    nodes[nodeId]?.lastSeen = System.currentTimeMillis()
  }

  fun updateResult(nodeId: String, payload: String?) {
    nodes[nodeId]?.apply {
      lastSeen = System.currentTimeMillis()
      lastResult = payload
    }
  }

  fun nodeCount(): Int = nodes.size

  fun uptimeMs(): Long = System.currentTimeMillis() - startTimeMs

  fun listNodes(): List<OpenClawNodeSnapshot> =
    nodes.values.map { node ->
      OpenClawNodeSnapshot(
        id = node.id,
        capabilities = node.capabilities,
        lastSeen = node.lastSeen,
        lastResult = node.lastResult
      )
    }.sortedBy { it.id }
}
