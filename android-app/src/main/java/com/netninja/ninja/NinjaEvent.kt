package com.netninja.ninja

/**
 * Data types for events delivered to the ninja companion.  The companion listens
 * to the [NinjaEventBus] and reacts to various types of events by changing
 * state, pulsing, showing thought bubbles, etc.  You can post your own
 * application‑specific events via [NinjaEventBus.post].
 */
sealed class NinjaEvent(val timestampMs: Long = System.currentTimeMillis()) {
    /**
     * Generic informational event.  The message should be user‑readable and
     * succinct (e.g. "Scanning network…").
     */
    data class Info(val message: String) : NinjaEvent()

    /**
     * Success event, e.g. an operation completed successfully.  The message
     * should describe what succeeded (e.g. "Login successful").
     */
    data class Success(val message: String) : NinjaEvent()

    /**
     * Warning event, e.g. something non‑fatal that still warrants attention.
     */
    data class Warning(val message: String) : NinjaEvent()

    /**
     * Error event, e.g. a failure or exception.  Consider passing a user
     * friendly message rather than raw exception text.
     */
    data class Error(val message: String) : NinjaEvent()

    /**
     * A router login result, used when the router login completes.  Contains
     * whether it was a success and a detail message.
     */
    data class RouterLoginResult(val success: Boolean, val detail: String) : NinjaEvent()

    /**
     * Device discovery event, fired when the discovery phase finds a given
     * number of devices on the network.
     */
    data class DeviceDiscoveryFound(val count: Int) : NinjaEvent()

    /**
     * Speed test completed event.  Contains measured down/up speeds and ping.
     */
    data class SpeedTestCompleted(
        val downMbps: Double,
        val upMbps: Double,
        val pingMs: Int
    ) : NinjaEvent()
}
