package server.openclaw

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class OpenClawNodeSnapshot(
  val id: String,
  val capabilities: List<String> = emptyList(),
  val lastSeen: Long = 0,
  val lastResult: JsonElement? = null
)

object OpenClawGatewayState {
  private val startTimeMs = System.currentTimeMillis()
  private val nodes = ConcurrentHashMap<String, OpenClawNodeSnapshot>()

  fun register(nodeId: String, capabilities: List<String>?, seenAt: Long = System.currentTimeMillis()) {
    val caps = capabilities ?: emptyList()
    val existing = nodes[nodeId]
    nodes[nodeId] = OpenClawNodeSnapshot(
      id = nodeId,
      capabilities = if (caps.isNotEmpty()) caps else existing?.capabilities ?: emptyList(),
      lastSeen = seenAt,
      lastResult = existing?.lastResult
    )
  }

  fun updateHeartbeat(nodeId: String, seenAt: Long = System.currentTimeMillis()) {
    val existing = nodes[nodeId] ?: OpenClawNodeSnapshot(id = nodeId, lastSeen = seenAt)
    nodes[nodeId] = existing.copy(lastSeen = seenAt)
  }

  fun updateResult(nodeId: String, payload: JsonElement?, seenAt: Long = System.currentTimeMillis()) {
    val existing = nodes[nodeId] ?: OpenClawNodeSnapshot(id = nodeId, lastSeen = seenAt)
    nodes[nodeId] = existing.copy(lastSeen = seenAt, lastResult = payload)
  }

  fun unregister(nodeId: String) {
    nodes.remove(nodeId)
  }

  fun listNodes(): List<OpenClawNodeSnapshot> = nodes.values.sortedBy { it.id }

  fun nodeCount(): Int = nodes.size

  fun uptimeMs(): Long = System.currentTimeMillis() - startTimeMs
}
