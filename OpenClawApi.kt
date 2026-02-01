fun Route.openClawRoutes(gateway: OpenClawGatewayService) {

    get("/openclaw/agents") {
        call.respond(gateway.listNodes())
    }

    get("/openclaw/status") {
        call.respond(
            mapOf(
                "nodes" to gateway.nodeCount(),
                "uptime" to gateway.uptime()
            )
        )
    }
}
