package com.netninja.ninja

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import com.netninja.R
import kotlin.math.max
import kotlin.random.Random

/**
 * An ImageView that makes the ninja feel alive.  It supports idle floating and
 * breathing animations, blinking, pulsing on events, a small shake for errors,
 * and smooth crossfades when changing state drawables.  Use with
 * [NinjaCompanionController] to orchestrate higher level behaviors.
 */
class NinjaAnimatedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private var idleAnim: AnimatorSet? = null
    private var lastPulseAt = 0L
    private var currentState: NinjaState = NinjaState.IDLE

    init {
        id = R.id.ninja_companion_bubble
        setImageResource(R.drawable.ninja_idle_blue_placeholder)
        scaleType = ScaleType.CENTER_INSIDE
        isClickable = true
        isFocusable = true
    }

    /** Start the idle floating + breathing animation. */
    fun startIdleAnimation() {
        stopIdleAnimation()
        val floatY = ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, 0f, -dp(7f)).apply {
            duration = 1400
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val breatheX = ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, 1.035f).apply {
            duration = 1600
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val breatheY = ObjectAnimator.ofFloat(this, View.SCALE_Y, 1f, 1.035f).apply {
            duration = 1600
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        idleAnim = AnimatorSet().apply {
            playTogether(floatY, breatheX, breatheY)
            start()
        }
    }

    /** Stop idle animations and reset transforms. */
    fun stopIdleAnimation() {
        idleAnim?.cancel()
        idleAnim = null
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        rotation = 0f
    }

    /**
     * Update the displayed state.  Optionally crossfades between the old
     * drawable and the new one to avoid abrupt changes.
     */
    fun setState(state: NinjaState, withCrossfade: Boolean = true) {
        if (state == currentState) return
        currentState = state
        val resId = when (state) {
            NinjaState.IDLE -> R.drawable.ninja_idle_blue_placeholder
            NinjaState.SLEEP -> R.drawable.ninja_idle_sleep_placeholder
            NinjaState.ALERT -> R.drawable.ninja_alert_pink_placeholder
            NinjaState.ACTION -> R.drawable.ninja_action_purple_placeholder
            NinjaState.ERROR -> R.drawable.ninja_error_red_placeholder
            NinjaState.CONFIDENT -> R.drawable.ninja_confident_purple_placeholder
        }
        if (!withCrossfade) {
            setImageResource(resId)
            return
        }
        // small crossfade for smoother state transitions
        animate().cancel()
        val out = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.15f).apply { duration = 90 }
        val `in` = ObjectAnimator.ofFloat(this, View.ALPHA, 0.15f, 1f).apply { duration = 120 }
        out.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                setImageResource(resId)
            }
        })
        AnimatorSet().apply {
            playSequentially(out, `in`)
            start()
        }
    }

    /** Fake blink by squashing the Y axis briefly.  Looks like blinking on a chibi head. */
    fun blink() {
        animate().cancel()
        val squash = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(this@NinjaAnimatedImageView, View.SCALE_Y, scaleY, 0.22f),
                ObjectAnimator.ofFloat(this@NinjaAnimatedImageView, View.SCALE_X, scaleX, 1.02f)
            )
            duration = 70
        }
        val restore = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(this@NinjaAnimatedImageView, View.SCALE_Y, 0.22f, 1f),
                ObjectAnimator.ofFloat(this@NinjaAnimatedImageView, View.SCALE_X, 1.02f, 1f)
            )
            duration = 90
        }
        AnimatorSet().apply {
            playSequentially(squash, restore)
            start()
        }
    }

    /** Quick pulse when an event arrives to draw the user's attention. */
    fun pulse() {
        val now = System.currentTimeMillis()
        if (now - lastPulseAt < 350) return
        lastPulseAt = now
        animate().cancel()
        animate()
            .scaleX(1.12f)
            .scaleY(1.12f)
            .setDuration(110)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** Shake gently left/right to indicate an error or negative feedback. */
    fun shakeNo() {
        animate().cancel()
        ObjectAnimator.ofFloat(this, View.ROTATION, 0f, -9f, 9f, -7f, 7f, 0f).apply {
            duration = 260
            interpolator = AccelerateDecelerateInterpolator()
        }.start()
    }

    /** Randomly choose an idle state for variety during long idle periods. */
    fun randomIdleState(): NinjaState {
        val r = Random.nextInt(100)
        return when {
            r < 70 -> NinjaState.IDLE
            r < 88 -> NinjaState.CONFIDENT
            else -> NinjaState.SLEEP
        }
    }

    /** Convert dp to pixels based on current display metrics. */
    private fun dp(v: Float): Float = max(1f, v * resources.displayMetrics.density)
}
