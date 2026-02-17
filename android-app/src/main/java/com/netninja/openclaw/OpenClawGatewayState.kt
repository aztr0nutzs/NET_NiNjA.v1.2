package com.netninja.openclaw

import com.netninja.OpenClawWsMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

object OpenClawGatewayState {
  private val startTimeMs = System.currentTimeMillis()
  private val nodes = ConcurrentHashMap<String, OpenClawNode>()
  private val json = Json { encodeDefaults = true }
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

  fun updateResult(
    nodeId: String,
    payload: String?,
    requestId: String? = null,
    success: Boolean? = null,
    error: String? = null
  ) {
    val now = System.currentTimeMillis()
    nodes[nodeId]?.apply {
      lastSeen = now
      lastResult = payload
    }
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

    val node = nodes[normalizedNodeId]
    if (node == null) {
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

    val commandMessage = OpenClawWsMessage(
      type = "COMMAND",
      nodeId = normalizedNodeId,
      requestId = requestId,
      payload = normalizedAction
    )

    try {
      node.session.send(json.encodeToString(commandMessage))
    } catch (t: Throwable) {
      pending.remove(requestId)
      val msg = t.message ?: "send failed"
      node.lastSeen = System.currentTimeMillis()
      node.lastResult = "SEND_ERROR[$requestId]: $msg"
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
      node.lastSeen = System.currentTimeMillis()
      node.lastResult = "TIMEOUT[$requestId]: ${normalizedAction.take(120)}"
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
