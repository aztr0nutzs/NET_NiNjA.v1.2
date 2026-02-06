package com.netninja.openclaw

/**
 * Represents a message exchanged between an OpenClaw client and the gateway. The
 * real implementation in the upstream project uses a more complex payload and
 * message routing. For the purposes of compilation and testing, this simple data
 * class captures a type and a JSON‑encoded payload.
 */
data class OpenClawMessage(
    val type: String,
    val payload: String
)