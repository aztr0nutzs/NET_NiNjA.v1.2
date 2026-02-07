package server.openclaw

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class OpenClawMessage(
  val type: String,
  val nodeId: String? = null,
  val capabilities: List<String>? = null,
  val payload: JsonElement? = null
)

@Serializable
data class OpenClawStateSnapshot(
  val nodes: List<OpenClawNodeSnapshot>,
  val updatedAt: Long = System.currentTimeMillis()
)

private val json = Json { ignoreUnknownKeys = true }

fun Route.openClawWebSocketServer() {
  webSocket("/openclaw/ws") {
    var nodeId: String? = null

    try {
      for (frame in incoming) {
        val text = (frame as? Frame.Text)?.readText() ?: continue
        val msg = runCatching { json.decodeFromString<OpenClawMessage>(text) }.getOrNull() ?: continue

        when (msg.type.uppercase()) {
          "HELLO" -> {
            val registeredId = msg.nodeId?.trim().orEmpty()
            if (registeredId.isBlank()) {
              send(Frame.Text("{\"error\":\"missing nodeId\"}"))
              continue
            }
            nodeId = registeredId
            OpenClawGatewayState.register(nodeId = registeredId, capabilities = msg.capabilities)
          }
          "HEARTBEAT" -> {
            val currentId = nodeId ?: msg.nodeId?.trim().orEmpty()
            if (currentId.isBlank()) {
              send(Frame.Text("{\"error\":\"unregistered node\"}"))
              continue
            }
            nodeId = currentId
            OpenClawGatewayState.updateHeartbeat(currentId)
          }
          "RESULT" -> {
            val currentId = nodeId ?: msg.nodeId?.trim().orEmpty()
            if (currentId.isBlank()) {
              send(Frame.Text("{\"error\":\"unregistered node\"}"))
              continue
            }
            nodeId = currentId
            OpenClawGatewayState.updateResult(currentId, msg.payload)
          }
        }

        val snapshot = OpenClawStateSnapshot(OpenClawGatewayState.listNodes())
        send(Frame.Text(json.encodeToString(snapshot)))
      }
    } finally {
      nodeId?.let { OpenClawGatewayState.unregister(it) }
    }
  }
}
