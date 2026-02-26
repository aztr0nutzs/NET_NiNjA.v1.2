package com.netninja.ninja

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A lightweight in‑process pub/sub bus used by the ninja companion.  Events
 * posted to this bus are delivered on the main thread to simplify UI updates.
 * A small history of recent events is also kept so that the thought bubble
 * can display recent messages even if the user opens the panel later.
 */
object NinjaEventBus {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(NinjaEvent) -> Unit>()

    private const val MAX_HISTORY = 25
    private val history = ArrayDeque<NinjaEvent>(MAX_HISTORY)

    /**
     * Post an event to all registered listeners.  If called off the main
     * thread the event will be delivered on the main thread.
     */
    fun post(event: NinjaEvent) {
        // track history
        synchronized(history) {
            if (history.size >= MAX_HISTORY) history.removeFirst()
            history.addLast(event)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it.invoke(event) }
        } else {
            mainHandler.post { listeners.forEach { it.invoke(event) } }
        }
    }

    /**
     * Register a listener.  Listeners should be removed when no longer needed
     * (e.g. in `onStop`).
     */
    fun addListener(listener: (NinjaEvent) -> Unit) {
        listeners.add(listener)
    }

    /** Remove a previously registered listener. */
    fun removeListener(listener: (NinjaEvent) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Returns a snapshot of the recent event history, with the most recent
     * events first.  Useful for displaying a list of recent messages when the
     * user opens a panel.
     */
    fun getHistorySnapshot(): List<NinjaEvent> {
        synchronized(history) {
            return history.toList().asReversed()
        }
    }
}
