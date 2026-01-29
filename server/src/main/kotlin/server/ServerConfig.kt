package server

data class ServerConfig(
  val host: String,
  val port: Int,
  val dbPath: String
)

fun resolveServerConfig(env: Map<String, String> = System.getenv()): ServerConfig {
  val host = env["NET_NINJA_HOST"]?.trim().orEmpty().ifBlank { "127.0.0.1" }
  val port = env["NET_NINJA_PORT"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8787
  val dbPath = env["NET_NINJA_DB"]?.trim().orEmpty().ifBlank { "netninja.db" }
  return ServerConfig(host = host, port = port, dbPath = dbPath)
}
