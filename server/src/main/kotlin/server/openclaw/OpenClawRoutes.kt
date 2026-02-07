package server.openclaw

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.openClawRoutes() {
  get("/api/openclaw/nodes") {
    call.respond(OpenClawGatewayState.listNodes())
  }

  get("/api/openclaw/stats") {
    call.respond(
      mapOf(
        "uptimeMs" to OpenClawGatewayState.uptimeMs(),
        "nodeCount" to OpenClawGatewayState.nodeCount()
      )
    )
  }
}
