package com.netninja.openclaw

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

class OpenClawGatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "openclaw_gateway"
        const val NOTIF_ID = 9001
        const val PORT = 18789
    }

    private lateinit var socketServer: OpenClawWebSocketServer
    private val nodes = ConcurrentHashMap<String, OpenClawNode>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        socketServer = OpenClawWebSocketServer(PORT, ::onNodeMessage)
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

    private fun onNodeMessage(msg: OpenClawMessage, session: NodeSession) {
        when (msg.type) {
            "HELLO" -> {
                val node = OpenClawNode(
                    id = msg.nodeId,
                    capabilities = msg.capabilities,
                    session = session
                )
                nodes[msg.nodeId] = node
                session.send(
                    OpenClawMessage.registered(msg.nodeId)
                )
            }

            "HEARTBEAT" -> {
                nodes[msg.nodeId]?.lastSeen = System.currentTimeMillis()
            }

            "RESULT" -> {
                nodes[msg.nodeId]?.lastResult = msg.payload
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
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
