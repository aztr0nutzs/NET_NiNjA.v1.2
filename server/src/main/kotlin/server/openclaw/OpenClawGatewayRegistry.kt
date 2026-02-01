package server.openclaw

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class OpenClawNodeSnapshot(
  val id: String,
  val capabilities: List<String> = emptyList(),
  val lastSeen: Long = 0,
  val lastResult: String? = null
)

interface OpenClawGateway {
  fun listNodes(): List<OpenClawNodeSnapshot>
  fun nodeCount(): Int
  fun uptimeMs(): Long
}

class OpenClawGatewayRegistry : OpenClawGateway {
  private val startTimeMs = System.currentTimeMillis()
  private val nodes = ConcurrentHashMap<String, OpenClawNodeSnapshot>()

  override fun listNodes(): List<OpenClawNodeSnapshot> = nodes.values.sortedBy { it.id }

  override fun nodeCount(): Int = nodes.size

  override fun uptimeMs(): Long = System.currentTimeMillis() - startTimeMs

  fun upsert(node: OpenClawNodeSnapshot) {
    nodes[node.id] = node
  }
}
