package server.openclaw

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class OpenClawMessage(
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
data class OpenClawWsErrorFrame(
  val type: String = "ERROR",
  val code: String,
  val message: String,
  val protocolVersion: Int = OPENCLAW_WS_PROTOCOL_VERSION
)

@Serializable
data class OpenClawGatewaySnapshot(
  val nodes: List<OpenClawNodeSnapshot>,
  val updatedAt: Long = System.currentTimeMillis()
)

data class NodeDispatchOutcome(
  val ok: Boolean,
  val status: String,
  val nodeId: String,
  val action: String,
  val requestId: String? = null,
  val payload: String? = null,
  val error: String? = null,
  val durationMs: Long = 0L
)

private const val OPENCLAW_WS_PROTOCOL_VERSION = 1
private val OPENCLAW_WS_ALLOWED_TYPES = setOf("OBSERVE", "HELLO", "HEARTBEAT", "RESULT")

sealed class OpenClawWsValidationResult {
  data class Valid(val message: OpenClawMessage) : OpenClawWsValidationResult()
  data class Invalid(val error: OpenClawWsErrorFrame, val closeCode: CloseReason.Codes = CloseReason.Codes.CANNOT_ACCEPT) :
    OpenClawWsValidationResult()
}

object OpenClawGatewayState {
  private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
  private val observers = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
  private val sessionToNode = ConcurrentHashMap<DefaultWebSocketServerSession, String>()
  private val json = Json { ignoreUnknownKeys = true }
  private var registry: OpenClawGatewayRegistry? = null
  private val requestCounter = AtomicLong(0L)
  private val pending = ConcurrentHashMap<String, PendingNodeRequest>()

  private data class PendingNodeRequest(
    val requestId: String,
    val nodeId: String,
    val action: String,
    val startedAt: Long,
    val latch: CountDownLatch = CountDownLatch(1),
    val outcomeRef: AtomicReference<NodeDispatchOutcome?> = AtomicReference(null)
  )

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

  suspend fun updateResult(
    nodeId: String,
    payload: String?,
    requestId: String? = null,
    success: Boolean? = null,
    error: String? = null
  ) {
    val now = System.currentTimeMillis()
    val existing = registry?.get(nodeId) ?: OpenClawNodeSnapshot(id = nodeId)
    registry?.upsert(existing.copy(lastSeen = now, lastResult = payload))
    val reqId = requestId?.trim().orEmpty()
    if (reqId.isNotBlank()) {
      val pendingReq = pending.remove(reqId)
      if (pendingReq != null) {
        val isSuccess = success ?: error.isNullOrBlank()
        pendingReq.outcomeRef.set(
          NodeDispatchOutcome(
            ok = isSuccess,
            status = if (isSuccess) "success" else "failure",
            nodeId = pendingReq.nodeId,
            action = pendingReq.action,
            requestId = reqId,
            payload = payload,
            error = if (isSuccess) null else (error ?: "Node reported failure"),
            durationMs = now - pendingReq.startedAt
          )
        )
        pendingReq.latch.countDown()
      }
    }
    emitSnapshot()
  }

  fun dispatchNodeAction(nodeId: String, action: String, timeoutMs: Long = 5_000L): NodeDispatchOutcome {
    val normalizedNodeId = nodeId.trim()
    val normalizedAction = action.trim()
    if (normalizedNodeId.isBlank() || normalizedAction.isBlank()) {
      return NodeDispatchOutcome(
        ok = false,
        status = "invalid",
        nodeId = normalizedNodeId,
        action = normalizedAction,
        error = "nodeId and action are required"
      )
    }

    val session = sessions[normalizedNodeId]
    if (session == null) {
      return NodeDispatchOutcome(
        ok = false,
        status = "offline",
        nodeId = normalizedNodeId,
        action = normalizedAction,
        error = "Node '$normalizedNodeId' is not connected"
      )
    }

    val requestId = "REQ-${requestCounter.incrementAndGet()}"
    val startedAt = System.currentTimeMillis()
    val pendingReq = PendingNodeRequest(
      requestId = requestId,
      nodeId = normalizedNodeId,
      action = normalizedAction,
      startedAt = startedAt
    )
    pending[requestId] = pendingReq

    val commandFrame = OpenClawMessage(
      type = "COMMAND",
      protocolVersion = OPENCLAW_WS_PROTOCOL_VERSION,
      nodeId = normalizedNodeId,
      requestId = requestId,
      payload = normalizedAction
    )

    try {
      runBlocking { session.send(json.encodeToString(commandFrame)) }
    } catch (t: Throwable) {
      pending.remove(requestId)
      val msg = t.message ?: "send failed"
      val existing = registry?.get(normalizedNodeId) ?: OpenClawNodeSnapshot(id = normalizedNodeId)
      registry?.upsert(existing.copy(lastSeen = System.currentTimeMillis(), lastResult = "SEND_ERROR[$requestId]: $msg"))
      return NodeDispatchOutcome(
        ok = false,
        status = "send_error",
        nodeId = normalizedNodeId,
        action = normalizedAction,
        requestId = requestId,
        error = msg,
        durationMs = System.currentTimeMillis() - startedAt
      )
    }

    val completed = try {
      pendingReq.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    }

    if (!completed) {
      pending.remove(requestId)
      val existing = registry?.get(normalizedNodeId) ?: OpenClawNodeSnapshot(id = normalizedNodeId)
      registry?.upsert(existing.copy(lastSeen = System.currentTimeMillis(), lastResult = "TIMEOUT[$requestId]: ${normalizedAction.take(120)}"))
      return NodeDispatchOutcome(
        ok = false,
        status = "timeout",
        nodeId = normalizedNodeId,
        action = normalizedAction,
        requestId = requestId,
        error = "Timed out after ${timeoutMs}ms waiting for node result",
        durationMs = System.currentTimeMillis() - startedAt
      )
    }

    return pendingReq.outcomeRef.get() ?: NodeDispatchOutcome(
      ok = false,
      status = "failure",
      nodeId = normalizedNodeId,
      action = normalizedAction,
      requestId = requestId,
      error = "Missing node result payload",
      durationMs = System.currentTimeMillis() - startedAt
    )
  }

  suspend fun disconnect(session: DefaultWebSocketServerSession) {
    val nodeId = sessionToNode.remove(session)
    if (nodeId != null) {
      sessions.remove(nodeId)
      val now = System.currentTimeMillis()
      val disconnected = pending.entries.filter { it.value.nodeId == nodeId }
      disconnected.forEach { entry ->
        if (pending.remove(entry.key, entry.value)) {
          entry.value.outcomeRef.set(
            NodeDispatchOutcome(
              ok = false,
              status = "disconnected",
              nodeId = nodeId,
              action = entry.value.action,
              requestId = entry.key,
              error = "Node disconnected before returning result",
              durationMs = now - entry.value.startedAt
            )
          )
          entry.value.latch.countDown()
        }
      }
      emitSnapshot()
    }
  }

  suspend fun registerObserver(key: String, session: DefaultWebSocketServerSession) {
    observers[key] = session
    val snapshot = OpenClawGatewaySnapshot(nodes = registry?.listNodes().orEmpty())
    val text = json.encodeToString(snapshot)
    try {
      session.send(text)
    } catch (_: Exception) {}
  }

  fun disconnectObserver(key: String) {
    observers.remove(key)
  }

  suspend fun emitSnapshot() {
    val snapshot = OpenClawGatewaySnapshot(nodes = registry?.listNodes().orEmpty())
    val message = json.encodeToString(snapshot)
    (sessions.values + observers.values).forEach { session ->
      try {
        session.send(message)
      } catch (_: Exception) {
      }
    }
  }

  private fun invalid(code: String, message: String): OpenClawWsValidationResult.Invalid =
    OpenClawWsValidationResult.Invalid(
      error = OpenClawWsErrorFrame(code = code, message = message)
    )

  fun parseAndValidateMessage(payload: String, currentNodeId: String?): OpenClawWsValidationResult {
    val msg = runCatching {
      json.decodeFromString(OpenClawMessage.serializer(), payload)
    }.getOrNull() ?: return invalid("MALFORMED_JSON", "Invalid JSON payload.")

    val type = msg.type.trim().uppercase()
    if (type !in OPENCLAW_WS_ALLOWED_TYPES) {
      return invalid("UNKNOWN_TYPE", "Unsupported message type '$type'.")
    }

    val version = msg.protocolVersion ?: OPENCLAW_WS_PROTOCOL_VERSION
    if (version != OPENCLAW_WS_PROTOCOL_VERSION) {
      return invalid(
        "UNSUPPORTED_PROTOCOL_VERSION",
        "Unsupported protocolVersion '$version'. Expected $OPENCLAW_WS_PROTOCOL_VERSION."
      )
    }

    val trimmedNodeId = msg.nodeId?.trim()?.takeIf { it.isNotBlank() }
    val trimmedPayload = msg.payload?.trim()

    return when (type) {
      "HELLO" -> {
        if (trimmedNodeId == null) invalid("MISSING_NODE_ID", "HELLO requires nodeId.")
        else OpenClawWsValidationResult.Valid(
          msg.copy(type = type, protocolVersion = version, nodeId = trimmedNodeId)
        )
      }
      "HEARTBEAT" -> {
        if (currentNodeId.isNullOrBlank() && trimmedNodeId == null) {
          invalid("MISSING_NODE_IDENTITY", "HEARTBEAT requires nodeId or prior HELLO.")
        } else {
          OpenClawWsValidationResult.Valid(
            msg.copy(type = type, protocolVersion = version, nodeId = trimmedNodeId)
          )
        }
      }
      "RESULT" -> {
        when {
          currentNodeId.isNullOrBlank() && trimmedNodeId == null ->
            invalid("MISSING_NODE_IDENTITY", "RESULT requires nodeId or prior HELLO.")
          trimmedPayload == null ->
            invalid("MISSING_PAYLOAD", "RESULT requires payload.")
          else ->
            OpenClawWsValidationResult.Valid(
              msg.copy(type = type, protocolVersion = version, nodeId = trimmedNodeId, payload = trimmedPayload)
            )
        }
      }
      else -> OpenClawWsValidationResult.Valid(msg.copy(type = type, protocolVersion = version, nodeId = trimmedNodeId))
    }
  }
}

fun Route.openClawWebSocketServer() {
  webSocket("/openclaw/ws") {
    var nodeId: String? = null
    var isObserver = false
    val observerKey = "obs_${System.nanoTime()}"

    try {
      for (frame in incoming) {
        val text = (frame as? Frame.Text)?.readText() ?: continue
        val validation = OpenClawGatewayState.parseAndValidateMessage(text, nodeId)
        val msg = when (validation) {
          is OpenClawWsValidationResult.Valid -> validation.message
          is OpenClawWsValidationResult.Invalid -> {
            send(Json.encodeToString(validation.error))
            close(CloseReason(validation.closeCode, validation.error.message.take(120)))
            return@webSocket
          }
        }

        when (msg.type.uppercase()) {
          "OBSERVE" -> {
            isObserver = true
            OpenClawGatewayState.registerObserver(observerKey, this)
          }
          "HELLO" -> {
            val resolvedId = msg.nodeId?.trim().orEmpty()
            if (resolvedId.isBlank()) {
              close(reason = CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing nodeId"))
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
              OpenClawGatewayState.updateResult(
                nodeId = resolvedId,
                payload = msg.payload,
                requestId = msg.requestId,
                success = msg.success,
                error = msg.error
              )
            }
          }
        }
      }
    } catch (_: CancellationException) {
    } finally {
      OpenClawGatewayState.disconnect(this)
      if (isObserver) {
        OpenClawGatewayState.disconnectObserver(observerKey)
      }
    }
  }
}
