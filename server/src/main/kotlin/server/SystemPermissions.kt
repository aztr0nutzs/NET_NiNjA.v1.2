package server

import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.Locale

@Serializable
data class PermissionActionDetails(
  val action: String? = null,
  val context: String? = null,
  val target: String? = null,
  val command: List<String> = emptyList(),
  val error: String? = null
)

@Serializable
data class PermissionActionResponse(
  val ok: Boolean,
  val message: String,
  val platform: String,
  val details: PermissionActionDetails = PermissionActionDetails()
)

enum class HostPlatform(val apiName: String) {
  WINDOWS("windows"),
  MACOS("macos"),
  LINUX("linux"),
  UNKNOWN("unknown")
}

fun detectHostPlatform(): HostPlatform {
  val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
  return when {
    os.contains("win") -> HostPlatform.WINDOWS
    os.contains("mac") || os.contains("darwin") -> HostPlatform.MACOS
    os.contains("nix") || os.contains("nux") || os.contains("aix") -> HostPlatform.LINUX
    else -> HostPlatform.UNKNOWN
  }
}

fun performPermissionAction(action: String, context: String?): PermissionActionResponse {
  val normalizedAction = action.trim().uppercase(Locale.ROOT)
  val normalizedContext = context?.trim()?.uppercase(Locale.ROOT)
  val platform = detectHostPlatform()

  if (normalizedAction != "OPEN_SETTINGS") {
    return PermissionActionResponse(
      ok = false,
      message = "Unsupported action '$action'.",
      platform = platform.apiName,
      details = PermissionActionDetails(action = normalizedAction, context = normalizedContext)
    )
  }

  val target = when (platform) {
    HostPlatform.WINDOWS -> windowsSettingsTarget(normalizedContext)
    HostPlatform.MACOS -> macosSettingsTarget(normalizedContext)
    HostPlatform.LINUX, HostPlatform.UNKNOWN -> null
  }

  if (target == null) {
    return PermissionActionResponse(
      ok = false,
      message = "Permission settings are unsupported on this platform.",
      platform = platform.apiName,
      details = PermissionActionDetails(action = normalizedAction, context = normalizedContext)
    )
  }

  val command = when (platform) {
    HostPlatform.WINDOWS ->
      // Use `start` so the command returns immediately (best-effort, no blocking).
      listOf("cmd.exe", "/c", "start", "", target)
    HostPlatform.MACOS -> listOf("open", target)
    HostPlatform.LINUX, HostPlatform.UNKNOWN -> emptyList()
  }

  return try {
    ProcessBuilder(command)
      .redirectErrorStream(true)
      .start()
    PermissionActionResponse(
      ok = true,
      message = "Attempted to open system settings.",
      platform = platform.apiName,
      details = PermissionActionDetails(
        action = normalizedAction,
        context = normalizedContext,
        target = target,
        command = command
      )
    )
  } catch (e: IOException) {
    PermissionActionResponse(
      ok = false,
      message = "Failed to open system settings.",
      platform = platform.apiName,
      details = PermissionActionDetails(
        action = normalizedAction,
        context = normalizedContext,
        target = target,
        command = command,
        error = e.message
      )
    )
  } catch (e: SecurityException) {
    PermissionActionResponse(
      ok = false,
      message = "Permission settings action blocked by security policy.",
      platform = platform.apiName,
      details = PermissionActionDetails(
        action = normalizedAction,
        context = normalizedContext,
        target = target,
        command = command,
        error = e.message
      )
    )
  }
}

private fun windowsSettingsTarget(context: String?): String {
  return when (context) {
    "CAMERA" -> "ms-settings:privacy-webcam"
    "MIC" -> "ms-settings:privacy-microphone"
    "NETWORK" -> "ms-settings:network"
    "NOTIFICATIONS" -> "ms-settings:notifications"
    else -> "ms-settings:privacy"
  }
}

private fun macosSettingsTarget(context: String?): String {
  // `x-apple.systempreferences:` is best-effort. Targets may vary by macOS version.
  return when (context) {
    "CAMERA" -> "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera"
    "MIC" -> "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"
    "NETWORK" -> "x-apple.systempreferences:com.apple.preference.network"
    "NOTIFICATIONS" -> "x-apple.systempreferences:com.apple.preference.notifications"
    else -> "x-apple.systempreferences:com.apple.preference.security?Privacy"
  }
}

