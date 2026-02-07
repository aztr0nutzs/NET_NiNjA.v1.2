package server

import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

@Serializable
data class PermissionActionRequest(val action: String? = null)

private const val SETTINGS_LAUNCH_TIMEOUT_SECONDS = 2L

private fun settingsCommandFor(osName: String, action: String): List<String>? {
  val os = osName.lowercase()
  return when {
    os.contains("win") -> when (action) {
      "app_settings" -> listOf("cmd", "/c", "start", "", "ms-settings:")
      "location_settings" -> listOf("cmd", "/c", "start", "", "ms-settings:privacy-location")
      "wifi_settings" -> listOf("cmd", "/c", "start", "", "ms-settings:network-wifi")
      else -> null
    }
    os.contains("mac") -> when (action) {
      "app_settings" -> listOf("open", "x-apple.systempreferences:")
      "location_settings" -> listOf(
        "open",
        "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices"
      )
      "wifi_settings" -> listOf(
        "open",
        "x-apple.systempreferences:com.apple.preference.network?Wi-Fi"
      )
      else -> null
    }
    else -> null
  }
}

fun tryLaunchSettings(action: String): Pair<Boolean, String> {
  val cmd = settingsCommandFor(System.getProperty("os.name") ?: "unknown", action)
    ?: return false to "Permission control is not supported on this host."
  return runCatching {
    val process = ProcessBuilder(cmd)
      .redirectErrorStream(true)
      .start()
    val finished = process.waitFor(SETTINGS_LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      process.destroy()
      false to "Settings command timed out."
    } else if (process.exitValue() == 0) {
      true to "Opening settings."
    } else {
      false to "Settings command failed."
    }
  }.getOrElse {
    false to "Unable to launch settings."
  }
}
