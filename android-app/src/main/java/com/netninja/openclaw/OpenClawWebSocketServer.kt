package com.netninja.openclaw

/**
 * Simple stub implementation for a WebSocket server used by the
 * OpenClaw gateway. The real implementation lives in the upstream
 * repository. This class merely satisfies the compiler by exposing
 * `start` and `stop` methods and forwarding incoming messages to a
 * provided handler. Use a proper WebSocket server when wiring up
 * OpenClaw.
 */
class OpenClawWebSocketServer(
    private val port: Int,
    private val onMessage: (OpenClawMessage, NodeSession) -> Unit
) {
    fun start() {
        // no‑op stub. In production this would start a real WebSocket server
    }

    fun stop() {
        // no‑op stub
    }
}