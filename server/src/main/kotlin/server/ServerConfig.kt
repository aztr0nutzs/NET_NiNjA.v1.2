package server

data class ServerConfig(
  val host: String,
  val port: Int,
  val dbPath: String,
  val allowedOrigins: List<String>
)

fun resolveServerConfig(env: Map<String, String> = System.getenv()): ServerConfig {
  val host = env["NET_NINJA_HOST"]?.trim().orEmpty().ifBlank { "127.0.0.1" }
  val port = env["NET_NINJA_PORT"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8787
  val dbPath = env["NET_NINJA_DB"]?.trim().orEmpty().ifBlank { "netninja.db" }
  val allowedOrigins = parseAllowedOrigins(env["NET_NINJA_ALLOWED_ORIGINS"], host, port)
  return ServerConfig(host = host, port = port, dbPath = dbPath, allowedOrigins = allowedOrigins)
}

private fun parseAllowedOrigins(raw: String?, host: String, port: Int): List<String> {
  val configured = raw
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotBlank() }
    .orEmpty()

  if (configured.isNotEmpty()) {
    return configured
  }

  val portSuffix = if (port == 80 || port == 443) "" else ":$port"
  return listOf(
    "http://127.0.0.1$portSuffix",
    "http://localhost$portSuffix",
    "http://$host$portSuffix"
  ).distinct()
}
