package com.netninja.openclaw

/**
 * Represents a registered node (client) connected to the OpenClaw gateway. In the
 * full implementation, nodes maintain state (such as a list of tasks or sensors)
 * and a live WebSocket session. Here we capture only the node’s identifier and
 * the session used for communication.
 */
data class OpenClawNode(
    val id: String,
    val session: NodeSession
)