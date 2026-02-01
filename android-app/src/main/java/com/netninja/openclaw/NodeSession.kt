package com.netninja.openclaw

import fi.iki.elonen.NanoWSD.WebSocket

class NodeSession(private val socket: WebSocket) {
  fun send(message: OpenClawMessage) {
    socket.send(message.toJson())
  }
}
