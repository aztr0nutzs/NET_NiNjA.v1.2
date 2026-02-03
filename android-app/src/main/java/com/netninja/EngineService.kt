
package com.netninja

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

class EngineService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var server: AndroidLocalServer? = null
  private val notificationController by lazy { NotificationController(this) }

  override fun onCreate() {
    super.onCreate()
    notificationController.ensureChannel()
    startForeground(1, notificationController.buildEngineNotification())

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
}
