package com.netninja

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationController(private val context: Context) {
  companion object {
    const val CHANNEL_ID = "netninja"
    private const val CHANNEL_NAME = "Net Ninja"
  }

  fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = context.getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  fun buildEngineNotification(): Notification {
    val intent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setContentTitle("Net Ninja")
      .setContentText("Local engine running")
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .build()
  }
}
