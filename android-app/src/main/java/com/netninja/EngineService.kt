
package com.netninja

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

class EngineService : Service() {
  companion object {
    private val sharedScanProgress = MutableStateFlow(ScanProgress())
    val scanProgressFlow: StateFlow<ScanProgress> = sharedScanProgress.asStateFlow()
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var server: AndroidLocalServer? = null
  private val notificationController by lazy { NotificationController(this) }
  private var progressRelayJob: Job? = null

  override fun onCreate() {
    super.onCreate()
    notificationController.ensureChannel()
    startForeground(1, notificationController.buildEngineNotification())

    server = AndroidLocalServer(applicationContext)
    progressRelayJob = scope.launch {
      server?.scanProgressFlow?.collectLatest { progress ->
        sharedScanProgress.value = progress
      }
    }
    scope.launch {
      server?.start()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    progressRelayJob?.cancel()
    scope.cancel()
    server?.stop()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
