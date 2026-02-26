package com.netninja.ninja

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.random.Random

/**
 * A controller responsible for orchestrating the ninja companion's behaviour.
 * It subscribes to [NinjaEventBus] and drives the [NinjaAnimatedImageView] and
 * [NinjaThoughtBubbleView] based on incoming events.  It also manages
 * periodic idle behaviours such as blinking and random idle state changes.
 *
 * The controller must be started and stopped in your activity's lifecycle
 * (`onStart`/`onStop`) to avoid leaking listeners.  See MainActivity for
 * usage.
 */
class NinjaCompanionController(
    private val ninja: NinjaAnimatedImageView,
    private val thought: NinjaThoughtBubbleView
) {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var lastThoughtAt = 0L

    // Use an object listener so we can early-return cleanly when not running.
    private val listener = object : (NinjaEvent) -> Unit {
        override fun invoke(event: NinjaEvent) {
            if (!running) return
            when (event) {
            is NinjaEvent.Info -> {
                ninja.pulse()
                maybeThought(event.message)
            }
            is NinjaEvent.Success -> {
                ninja.setState(NinjaState.CONFIDENT)
                ninja.pulse()
                maybeThought(event.message)
                scheduleReturnToIdle()
            }
            is NinjaEvent.Warning -> {
                ninja.setState(NinjaState.ALERT)
                ninja.pulse()
                maybeThought(event.message)
                scheduleReturnToIdle()
            }
            is NinjaEvent.Error -> {
                ninja.setState(NinjaState.ERROR)
                ninja.shakeNo()
                ninja.pulse()
                maybeThought(event.message, force = true)
                scheduleReturnToIdle()
            }
            is NinjaEvent.RouterLoginResult -> {
                if (event.success) {
                    ninja.setState(NinjaState.CONFIDENT)
                    ninja.pulse()
                    maybeThought("Logged in. Try not to break it.")
                } else {
                    ninja.setState(NinjaState.ERROR)
                    ninja.shakeNo()
                    ninja.pulse()
                    maybeThought("Login failed: ${event.detail}", force = true)
                }
                scheduleReturnToIdle()
            }
            is NinjaEvent.DeviceDiscoveryFound -> {
                ninja.setState(NinjaState.ACTION)
                ninja.pulse()
                maybeThought("Found ${event.count} devices.")
                scheduleReturnToIdle()
            }
            is NinjaEvent.SpeedTestCompleted -> {
                ninja.setState(NinjaState.ACTION)
                ninja.pulse()
                // Use plain text instead of arrow characters to avoid mojibake in some builds.
                maybeThought(
                    "Speed: ${event.downMbps.toInt()} down / ${event.upMbps.toInt()} up (${event.pingMs}ms)"
                )
                scheduleReturnToIdle()
            }
            }
        }
    }

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            ninja.blink()
            main.postDelayed(this, randomBetween(2200, 5200).toLong())
        }
    }

    private val idleSwapRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val next = ninja.randomIdleState()
            ninja.setState(next)
            main.postDelayed(this, randomBetween(7000, 14000).toLong())
        }
    }

    private val returnToIdleRunnable = Runnable {
        if (!running) return@Runnable
        ninja.setState(NinjaState.IDLE)
    }

    /** Start listening to events and begin idle behaviours. */
    fun start() {
        if (running) return
        running = true
        ninja.startIdleAnimation()
        NinjaEventBus.addListener(listener)
        main.postDelayed(blinkRunnable, randomBetween(1400, 2800).toLong())
        main.postDelayed(idleSwapRunnable, randomBetween(6000, 11000).toLong())
    }

    /** Stop listening to events and idle behaviours. */
    fun stop() {
        if (!running) return
        running = false
        NinjaEventBus.removeListener(listener)
        main.removeCallbacks(blinkRunnable)
        main.removeCallbacks(idleSwapRunnable)
        main.removeCallbacks(returnToIdleRunnable)
        thought.hide()
        ninja.stopIdleAnimation()
    }

    private fun scheduleReturnToIdle(delayMs: Long = 2400L) {
        main.removeCallbacks(returnToIdleRunnable)
        main.postDelayed(returnToIdleRunnable, delayMs)
    }

    private fun maybeThought(text: String, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val cooldown = 4500L
        if (!force && now - lastThoughtAt < cooldown) return
        lastThoughtAt = now
        thought.show(text)
    }

    private fun randomBetween(min: Int, max: Int): Int {
        return Random.nextInt(max - min + 1) + min
    }
}

