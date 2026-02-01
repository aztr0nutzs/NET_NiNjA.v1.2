class OpenClawWebSocketServer(
    port: Int,
    private val onMessage: (OpenClawMessage, NodeSession) -> Unit
) : NanoWSD(port) {

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return object : WebSocket(handshake) {

            private val session = NodeSession(this)

            override fun onMessage(message: WebSocketFrame) {
                val msg = OpenClawMessage.fromJson(message.textPayload)
                onMessage(msg, session)
            }

            override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {}
            override fun onException(exception: IOException?) {}
            override fun onPong(pong: WebSocketFrame?) {}
        }
    }
}
