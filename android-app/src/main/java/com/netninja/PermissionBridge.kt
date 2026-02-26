package com.netninja

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal "bridge" for permission-help actions triggered by the embedded WebView UI (via localhost API).
 * The server runs in a Service with only application Context, so runtime permission prompts must be
 * delegated to an Activity when available.
 */
object PermissionBridge {
  private val activityRef = AtomicReference<WeakReference<Activity>?>(null)

  const val REQ_MIC = 2202
  const val REQ_NOTIFICATIONS = 2203

  fun setForegroundActivity(activity: Activity?) {
    activityRef.set(if (activity == null) null else WeakReference(activity))
  }

  fun openAppSettings(ctx: Context): PermissionActionResult {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.fromParts("package", ctx.packageName, null)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
      ctx.startActivity(intent)
      PermissionActionResult(ok = true, message = "Opened app settings.", status = "opened_settings")
    }.getOrElse { e ->
      PermissionActionResult(ok = false, message = "Unable to open app settings.", status = "error", error = e.message)
    }
  }

  fun requestMic(): PermissionActionResult {
    return requestRuntimePermission(Manifest.permission.RECORD_AUDIO, REQ_MIC, label = "Microphone")
  }

  fun requestNotifications(ctx: Context): PermissionActionResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return PermissionActionResult(ok = true, message = "Notifications permission is not required on this Android version.", status = "not_applicable")
    }
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
      return PermissionActionResult(ok = true, message = "Notifications permission is already granted.", status = "already_granted")
    }
    return requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS, REQ_NOTIFICATIONS, label = "Notifications")
  }

  private fun requestRuntimePermission(permission: String, requestCode: Int, label: String): PermissionActionResult {
    val activity = activityRef.get()?.get()
      ?: return PermissionActionResult(
        ok = false,
        message = "Unable to request $label permission: app is not in the foreground.",
        status = "no_activity"
      )

    val alreadyGranted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    if (alreadyGranted) {
      return PermissionActionResult(ok = true, message = "$label permission is already granted.", status = "already_granted")
    }

    return runCatching {
      ActivityCompat.requestPermissions(activity, arrayOf(permission), requestCode)
      PermissionActionResult(ok = true, message = "Requesting $label permission.", status = "requested")
    }.getOrElse { e ->
      PermissionActionResult(ok = false, message = "Failed to request $label permission.", status = "error", error = e.message)
    }
  }
}

data class PermissionActionResult(
  val ok: Boolean,
  val message: String,
  val status: String,
  val error: String? = null
)

fun String?.normalizedPermissionAction(): String = this?.trim()?.uppercase(Locale.ROOT).orEmpty()

