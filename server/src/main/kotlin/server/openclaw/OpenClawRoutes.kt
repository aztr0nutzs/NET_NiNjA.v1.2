package server.openclaw

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ConnectRequest(val host: String? = null)

@Serializable
data class ConfigRequest(
  val host: String? = null,
  val profile: String? = null,
  val workspace: String? = null
)

@Serializable
data class InstanceRequest(
  val name: String? = null,
  val profile: String? = null,
  val workspace: String? = null,
  val sandbox: Boolean? = null,
  val access: String? = null
)

@Serializable
data class MessageRequest(
  val gateway: String? = null,
  val channel: String? = null,
  val body: String? = null
)

@Serializable
data class SessionRequest(
  val type: String? = null,
  val target: String? = null,
  val payload: String? = null
)

@Serializable
data class CommandRequest(val command: String? = null)

@Serializable
data class ModeRequest(val mode: String? = null)

@Serializable
data class SkillInvokeRequest(val name: String? = null)

@Serializable
data class OpenClawStatsResponse(
  val uptimeMs: Long,
  val nodeCount: Int
)

fun Route.openClawRoutes() {
  get("/api/openclaw/nodes") {
    call.respond(OpenClawGatewayState.listNodes())
  }

  get("/api/openclaw/stats") {
    call.respond(OpenClawStatsResponse(uptimeMs = OpenClawGatewayState.uptimeMs(), nodeCount = OpenClawGatewayState.nodeCount()))
  }

  get("/api/openclaw/dashboard") {
    call.respond(OpenClawDashboardState.snapshot())
  }

  post("/api/openclaw/connect") {
    val req = call.receive<ConnectRequest>()
    call.respond(OpenClawDashboardState.connect(req.host))
  }

  post("/api/openclaw/disconnect") {
    call.respond(OpenClawDashboardState.disconnect())
  }

  post("/api/openclaw/refresh") {
    call.respond(OpenClawDashboardState.refresh())
  }

  post("/api/openclaw/panic") {
    call.respond(OpenClawDashboardState.panic())
  }

  get("/api/openclaw/gateways") {
    call.respond(OpenClawDashboardState.listGateways())
  }

  post("/api/openclaw/gateways/{key}/restart") {
    val key = call.parameters["key"]?.trim().orEmpty()
    val updated = OpenClawDashboardState.restartGateway(key)
    if (updated == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown gateway"))
    } else {
      call.respond(updated)
    }
  }

  post("/api/openclaw/gateways/{key}/ping") {
    val key = call.parameters["key"]?.trim().orEmpty()
    val result = OpenClawDashboardState.pingGateway(key)
    if (result == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown gateway"))
    } else {
      call.respond(result)
    }
  }

  get("/api/openclaw/instances") {
    call.respond(OpenClawDashboardState.listInstances())
  }

  post("/api/openclaw/instances") {
    val req = call.receive<InstanceRequest>()
    val name = req.name?.trim().orEmpty()
    if (name.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Instance name required"))
      return@post
    }
    val instance = OpenClawDashboardState.addInstance(name, req.profile, req.workspace, req.sandbox, req.access)
    if (instance == null) {
      call.respond(HttpStatusCode.Conflict, mapOf("error" to "Instance already exists"))
    } else {
      call.respond(instance)
    }
  }

  post("/api/openclaw/instances/{name}/select") {
    val name = call.parameters["name"]?.trim().orEmpty()
    val instance = OpenClawDashboardState.selectInstance(name)
    if (instance == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown instance"))
    } else {
      call.respond(instance)
    }
  }

  post("/api/openclaw/instances/{name}/activate") {
    val name = call.parameters["name"]?.trim().orEmpty()
    val instance = OpenClawDashboardState.activateInstance(name)
    if (instance == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown instance"))
    } else {
      call.respond(instance)
    }
  }

  post("/api/openclaw/instances/{name}/stop") {
    val name = call.parameters["name"]?.trim().orEmpty()
    val instance = OpenClawDashboardState.stopInstance(name)
    if (instance == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown instance"))
    } else {
      call.respond(instance)
    }
  }

  get("/api/openclaw/sessions") {
    call.respond(OpenClawDashboardState.listSessions())
  }

  post("/api/openclaw/sessions") {
    val req = call.receive<SessionRequest>()
    val type = req.type?.trim().orEmpty().ifBlank { "session" }
    val target = req.target?.trim().orEmpty().ifBlank { "default" }
    val session = OpenClawDashboardState.createSession(type, target, req.payload)
    call.respond(session)
  }

  post("/api/openclaw/sessions/{id}/cancel") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val session = OpenClawDashboardState.cancelSession(id)
    if (session == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown session"))
    } else {
      call.respond(session)
    }
  }

  get("/api/openclaw/skills") {
    call.respond(OpenClawDashboardState.listSkills())
  }

  post("/api/openclaw/skills/refresh") {
    call.respond(OpenClawDashboardState.refreshSkills())
  }

  post("/api/openclaw/skills/invoke") {
    val req = call.receive<SkillInvokeRequest>()
    val name = req.name?.trim().orEmpty()
    if (name.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Skill name required"))
      return@post
    }
    val skill = OpenClawDashboardState.invokeSkill(name)
    if (skill == null) {
      call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown skill"))
    } else {
      call.respond(skill)
    }
  }

  get("/api/openclaw/mode") {
    call.respond(mapOf("mode" to OpenClawDashboardState.snapshot().mode))
  }

  post("/api/openclaw/mode") {
    val req = call.receive<ModeRequest>()
    val mode = req.mode?.trim().orEmpty().ifBlank { "safe" }
    call.respond(mapOf("mode" to OpenClawDashboardState.setMode(mode)))
  }

  get("/api/openclaw/config") {
    call.respond(OpenClawDashboardState.configSnapshot())
  }

  post("/api/openclaw/config") {
    val req = call.receive<ConfigRequest>()
    call.respond(OpenClawDashboardState.updateConfig(req.host, req.profile, req.workspace))
  }

  get("/api/openclaw/debug") {
    call.respond(OpenClawDashboardState.debugSnapshot())
  }

  post("/api/openclaw/debug/dump") {
    call.respond(OpenClawDashboardState.debugSnapshot())
  }

  get("/api/openclaw/logs") {
    call.respond(OpenClawDashboardState.listLogs())
  }

  post("/api/openclaw/logs/clear") {
    OpenClawDashboardState.clearLogs()
    call.respond(mapOf("ok" to true))
  }

  get("/api/openclaw/messages") {
    call.respond(OpenClawDashboardState.listMessages())
  }

  post("/api/openclaw/messages") {
    val req = call.receive<MessageRequest>()
    val gateway = req.gateway?.trim().orEmpty()
    val channel = req.channel?.trim().orEmpty()
    val body = req.body?.trim().orEmpty()
    if (gateway.isBlank() || channel.isBlank() || body.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "gateway, channel, and body required"))
      return@post
    }
    call.respond(OpenClawDashboardState.addMessage(gateway, channel, body))
  }

  post("/api/openclaw/command") {
    val req = call.receive<CommandRequest>()
    val command = req.command?.trim().orEmpty()
    if (command.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Command required"))
      return@post
    }
    call.respond(OpenClawDashboardState.runCommand(command))
  }
}
