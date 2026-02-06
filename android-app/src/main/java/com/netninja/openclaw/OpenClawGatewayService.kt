package com.netninja.openclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that hosts an OpenClaw gateway. A WebSocket server listens
 * for incoming connections from OpenClaw clients and forwards messages
 * to registered handlers. This stub implementation starts a dummy server
 * on an arbitrary port. In a full implementation, the server would run on
 * a configurable port and maintain a registry of connected nodes.
 */
class OpenClawGatewayService : Service() {

    private lateinit var server: OpenClawWebSocketServer

    override fun onCreate() {
        super.onCreate()
        // Create a notification channel for API 26+.
        val channelId = CHANNEL_ID
        val name = "OpenClaw Gateway"
        val description = "OpenClaw gateway service"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(channelId, name, importance).apply {
            this.description = description
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        // Build a minimal notification for the foreground service. A real app
        // should provide a meaningful notification with actions and metadata.
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(name)
            .setContentText(description)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        // Start foreground to comply with Android 8.0+ requirements.
        startForeground(FOREGROUND_ID, notification)

        // Start the stub WebSocket server on port 9999. Incoming messages are
        // forwarded to a no‑op handler.
        server = OpenClawWebSocketServer(9999) { message, session ->
            // In a real implementation, forward message to registered components.
        }
        server.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nothing else to do; the server is started in onCreate.
        return START_STICKY
    }

    override fun onDestroy() {
        // Stop the WebSocket server when the service is destroyed.
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Binding is not supported; return null.
        return null
    }

    companion object {
        private const val CHANNEL_ID = "openclaw_gateway"
        private const val FOREGROUND_ID = 1
    }
}