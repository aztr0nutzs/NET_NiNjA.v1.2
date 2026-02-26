package com.netninja.ninja

/**
 * Simple enumeration of the possible visual/emotional states for the ninja companion.
 * Each state maps to a drawable resource that conveys a different expression or mood.
 */
enum class NinjaState {
    /** Default idle state used when nothing interesting is happening. */
    IDLE,
    /** Sleepy/idle state when the companion wants to rest. */
    SLEEP,
    /** Alert state used for warnings or minor notices. */
    ALERT,
    /** Active/action state used while performing a task or operation. */
    ACTION,
    /** Error state used when something has gone wrong. */
    ERROR,
    /** Confident state used on success or completion. */
    CONFIDENT
}
