package server.openclaw

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class OpenClawStatus(val nodes: Int, val uptimeMs: Long)

fun Route.openClawRoutes(gateway: OpenClawGateway) {
  get("/openclaw/nodes") {
    call.respond(gateway.listNodes())
  }

  get("/openclaw/status") {
    call.respond(OpenClawStatus(nodes = gateway.nodeCount(), uptimeMs = gateway.uptimeMs()))
  }
}
