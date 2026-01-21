
package com.netninja

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import java.io.File

class EngineService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var server: AndroidLocalServer? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(1, Notification.Builder(this, "netninja")
      .setContentTitle("Net Ninja")
      .setContentText("Local engine running")
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .build()
    )

    server = AndroidLocalServer(applicationContext)
    scope.launch {
      server?.start()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
    server?.stop()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val nm = getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(NotificationChannel("netninja", "Net Ninja", NotificationManager.IMPORTANCE_LOW))
    }
  }
}
