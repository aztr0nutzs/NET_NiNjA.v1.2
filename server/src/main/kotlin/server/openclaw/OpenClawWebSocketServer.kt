package server.openclaw

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class OpenClawMessage(
  val type: String,
  val nodeId: String? = null,
  val capabilities: List<String> = emptyList(),
  val payload: String? = null
)

@Serializable
data class OpenClawGatewaySnapshot(
  val nodes: List<OpenClawNodeSnapshot>,
  val updatedAt: Long = System.currentTimeMillis()
)

object OpenClawGatewayState {
  private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
  private val sessionToNode = ConcurrentHashMap<DefaultWebSocketServerSession, String>()
  private val json = Json { ignoreUnknownKeys = true }
  private var registry: OpenClawGatewayRegistry? = null

  fun bindRegistry(gateway: OpenClawGatewayRegistry) {
    registry = gateway
  }

  fun listNodes(): List<OpenClawNodeSnapshot> = registry?.listNodes().orEmpty()

  fun nodeCount(): Int = registry?.nodeCount() ?: 0

  fun uptimeMs(): Long = registry?.uptimeMs() ?: 0L

  suspend fun register(nodeId: String, capabilities: List<String>, session: DefaultWebSocketServerSession) {
    sessions[nodeId] = session
    sessionToNode[session] = nodeId
    val now = System.currentTimeMillis()
    val existing = registry?.get(nodeId)
    registry?.upsert(
      OpenClawNodeSnapshot(
        id = nodeId,
        capabilities = if (capabilities.isNotEmpty()) capabilities else (existing?.capabilities ?: emptyList()),
        lastSeen = now,
        lastResult = existing?.lastResult
      )
    )
    emitSnapshot()
  }

  suspend fun updateHeartbeat(nodeId: String) {
    val existing = registry?.get(nodeId) ?: OpenClawNodeSnapshot(id = nodeId)
    registry?.upsert(existing.copy(lastSeen = System.currentTimeMillis()))
    emitSnapshot()
  }

  suspend fun updateResult(nodeId: String, payload: String?) {
    val existing = registry?.get(nodeId) ?: OpenClawNodeSnapshot(id = nodeId)
    registry?.upsert(existing.copy(lastSeen = System.currentTimeMillis(), lastResult = payload))
    emitSnapshot()
  }

  suspend fun disconnect(session: DefaultWebSocketServerSession) {
    val nodeId = sessionToNode.remove(session)
    if (nodeId != null) {
      sessions.remove(nodeId)
      emitSnapshot()
    }
  }

  suspend fun emitSnapshot() {
    val snapshot = OpenClawGatewaySnapshot(nodes = registry?.listNodes().orEmpty())
    val message = json.encodeToString(snapshot)
    sessions.values.forEach { session ->
      try {
        session.send(message)
      } catch (_: Exception) {
      }
    }
  }

  fun parseMessage(payload: String): OpenClawMessage? = runCatching {
    json.decodeFromString(OpenClawMessage.serializer(), payload)
  }.getOrNull()
}

fun Route.openClawWebSocketServer() {
  webSocket("/openclaw/ws") {
    var nodeId: String? = null

    try {
      for (frame in incoming) {
        val text = (frame as? Frame.Text)?.readText() ?: continue
        val msg = OpenClawGatewayState.parseMessage(text) ?: continue

        when (msg.type.uppercase()) {
          "HELLO" -> {
            val resolvedId = msg.nodeId?.trim().orEmpty()
            if (resolvedId.isBlank()) {
              close(reason = io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.CANNOT_ACCEPT, "Missing nodeId"))
              return@webSocket
            }
            nodeId = resolvedId
            OpenClawGatewayState.register(nodeId!!, msg.capabilities, this)
          }
          "HEARTBEAT" -> {
            val resolvedId = nodeId ?: msg.nodeId
            if (!resolvedId.isNullOrBlank()) {
              OpenClawGatewayState.updateHeartbeat(resolvedId)
            }
          }
          "RESULT" -> {
            val resolvedId = nodeId ?: msg.nodeId
            if (!resolvedId.isNullOrBlank()) {
              OpenClawGatewayState.updateResult(resolvedId, msg.payload)
            }
          }
        }
      }
    } catch (_: CancellationException) {
    } finally {
      OpenClawGatewayState.disconnect(this)
    }
  }
}
