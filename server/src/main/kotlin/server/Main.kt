package server

import java.io.File

fun main(args: Array<String>) {
  val uiDir = when {
    args.isNotEmpty() -> File(args[0])
    File("web-ui").exists() -> File("web-ui")
    File("../web-ui").exists() -> File("../web-ui")
    else -> null
  }

  if (uiDir == null || !uiDir.exists()) {
    System.err.println("ERROR: web-ui directory not found. Pass path as first argument.")
    System.err.println("Usage: server <path-to-web-ui>")
    return
  }

  val config = resolveServerConfig()
  startServer(
    uiDir,
    host = config.host,
    port = config.port,
    dbPath = config.dbPath,
    allowedOrigins = config.allowedOrigins,
    authToken = config.authToken
  )
}
