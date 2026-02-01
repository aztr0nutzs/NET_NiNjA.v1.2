package com.netninja.openclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class OpenClawGatewayService : Service() {
  companion object {
    const val CHANNEL_ID = "openclaw_gateway"
    const val NOTIF_ID = 9001
    const val PORT = 18789
  }

  private lateinit var socketServer: OpenClawWebSocketServer

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    socketServer = OpenClawWebSocketServer(PORT) { msg, session ->
      handleMessage(msg, session)
    }
    socketServer.start()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIF_ID, buildNotification())
    return START_STICKY
  }

  override fun onDestroy() {
    socketServer.stop()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun handleMessage(msg: OpenClawMessage, session: NodeSession) {
    when (msg.type.uppercase()) {
      "HELLO" -> {
        if (msg.nodeId.isBlank()) return
        val capabilities = msg.capabilities
          ?.filter { it.isNotBlank() }
          ?: emptyList()
        OpenClawGatewayState.register(msg.nodeId, capabilities, session)
        session.send(OpenClawMessage.registered(msg.nodeId))
      }
      "HEARTBEAT" -> {
        if (msg.nodeId.isBlank()) return
        OpenClawGatewayState.updateHeartbeat(msg.nodeId)
      }
      "RESULT" -> {
        if (msg.nodeId.isBlank()) return
        OpenClawGatewayState.updateResult(msg.nodeId, msg.payload)
      }
    }
  }

  private fun buildNotification(): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("OpenClaw Gateway")
      .setContentText("Gateway active on port $PORT")
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setOngoing(true)
      .build()

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "OpenClaw Gateway",
      NotificationManager.IMPORTANCE_LOW
    )
    val mgr = getSystemService(NotificationManager::class.java)
    mgr?.createNotificationChannel(channel)
  }
}
