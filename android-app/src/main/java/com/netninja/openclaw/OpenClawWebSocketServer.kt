package com.netninja.openclaw

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import java.io.IOException

class OpenClawWebSocketServer(
  port: Int,
  private val onMessage: (OpenClawMessage, NodeSession) -> Unit
) : NanoWSD(port) {

  override fun openWebSocket(handshake: IHTTPSession): WebSocket {
    return object : WebSocket(handshake) {
      private val session = NodeSession(this)

      override fun onMessage(message: WebSocketFrame) {
        val msg = OpenClawMessage.fromJson(message.textPayload) ?: return
        onMessage(msg, session)
      }

      override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) = Unit

      override fun onException(exception: IOException?) = Unit

      override fun onPong(pong: WebSocketFrame?) = Unit
    }
  }
}
