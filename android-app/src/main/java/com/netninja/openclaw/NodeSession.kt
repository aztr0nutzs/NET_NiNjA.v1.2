package com.netninja.openclaw

/**
 * Encapsulates the information associated with an individual client connection to
 * the OpenClaw gateway. A NodeSession holds the node’s identifier and a
 * callback function for sending messages back to the node. In a full
 * implementation this would wrap a WebSocket or other transport layer.
 */
class NodeSession(
    val id: String,
    private val sendFn: (String) -> Unit
) {
    /**
     * Send a raw string message to the client.
     */
    fun send(message: String) {
        sendFn(message)
    }
}