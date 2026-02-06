package com.netninja.cam

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Service responsible for discovering ONVIF devices on the local network. The original
 * implementation attempted to acquire a multicast lock without handling SecurityException,
 * which caused a crash on devices lacking the CHANGE_WIFI_MULTICAST_STATE permission.
 * This version guards the lock acquisition using runCatching and releases the lock
 * only if acquisition succeeds.
 */
class OnvifDiscoveryService(private val context: Context) {

    data class OnvifDevice(val host: String, val port: Int, val name: String)

    /**
     * Perform a multicast discovery and return a list of discovered ONVIF devices.
     */
    fun discover(): List<OnvifDevice> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("onvif-discovery")
        var acquired = false
        try {
            // Acquire the multicast lock. runCatching will catch SecurityException if the
            // CHANGE_WIFI_MULTICAST_STATE permission is missing. We record success before
            // performing any network operations.
            runCatching {
                lock.acquire()
            }.onSuccess {
                acquired = true
            }
            // TODO: perform ONVIF discovery here. This placeholder returns an empty list.
            return emptyList()
        } finally {
            // Release the multicast lock only if it was successfully acquired.
            if (acquired) {
                runCatching { lock.release() }
            }
        }
    }
}