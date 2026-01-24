package server

import java.io.File

class NetReaperProcess(
  private val repoRoot: File = File(".")
) {
  private var process: Process? = null

  fun startIfEnabled() {
    val enabled = System.getenv("NETREAPER_ENABLE")?.lowercase() in setOf("1", "true", "yes")
    if (!enabled) {
      println("[netreaper] NETREAPER_ENABLE not set; skipping NetReaper startup.")
      return
    }

    val secret = System.getenv("NETREAPER_SECRET")
    val password = System.getenv("NETREAPER_PASSWORD")
    if (secret.isNullOrBlank() || password.isNullOrBlank()) {
      println("[netreaper] NETREAPER_SECRET or NETREAPER_PASSWORD missing; NetReaper will not start.")
      return
    }

    val netreaperRoot = File(repoRoot, "modules/netreaper")
    val serviceModule = "modules.netreaper.service.main:app"
    val python = System.getenv("NETREAPER_PYTHON")?.takeIf { it.isNotBlank() } ?: "python3"
    val port = System.getenv("NETREAPER_PORT")?.takeIf { it.isNotBlank() } ?: "9088"
    val outputDir = File(netreaperRoot, "output")
    outputDir.mkdirs()

    if (!netreaperRoot.exists()) {
      println("[netreaper] NetReaper root not found at ${netreaperRoot.absolutePath}; skipping startup.")
      return
    }

    val command = listOf(
      python,
      "-m",
      "uvicorn",
      serviceModule,
      "--host",
      "127.0.0.1",
      "--port",
      port
    )

    val env = mutableMapOf<String, String>()
    env.putAll(System.getenv())
    env["PYTHONPATH"] = repoRoot.absolutePath
    env["NETREAPER_ROOT"] = netreaperRoot.absolutePath
    env["NETREAPER_OUTPUT_DIR"] = outputDir.absolutePath
    env["NETREAPER_ALLOWED_ORIGINS"] = env["NETREAPER_ALLOWED_ORIGINS"]
      ?: "http://127.0.0.1:8787,http://localhost:8787"

    println("[netreaper] Starting NetReaper FastAPI on port $port")
    process = ProcessBuilder(command)
      .directory(repoRoot)
      .inheritIO()
      .apply { environment().putAll(env) }
      .start()
  }

  fun stop() {
    val proc = process ?: return
    println("[netreaper] Stopping NetReaper process...")
    proc.destroy()
    process = null
  }
}
