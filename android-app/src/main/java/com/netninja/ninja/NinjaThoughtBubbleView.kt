package com.netninja.ninja

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.netninja.R

/**
 * A small transient thought bubble that appears above the ninja and fades out
 * automatically after a short duration.  It uses a Handler to schedule hiding
 * itself on the main thread.  The text colour and background are set via
 * drawable resources.
 */
class NinjaThoughtBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    private val main = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    init {
        id = R.id.ninja_companion_thought
        setBackgroundResource(R.drawable.ninja_thought_bg)
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 12f
        alpha = 0f
        visibility = View.GONE
    }

    /** Show a thought bubble with the given text for a specified duration. */
    fun show(text: String, durationMs: Long = 2200L) {
        this.text = text
        hideRunnable?.let { main.removeCallbacks(it) }
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
        }
        animate().cancel()
        alpha = 0f
        translationY = 0f
        animate()
            .alpha(1f)
            .translationY(-dp(6))
            .setDuration(140)
            .start()
        hideRunnable = Runnable { hide() }
        main.postDelayed(hideRunnable!!, durationMs)
    }

    /** Immediately hide the bubble by fading it out. */
    fun hide() {
        hideRunnable?.let { main.removeCallbacks(it) }
        hideRunnable = null
        animate().cancel()
        animate()
            .alpha(0f)
            .translationY(0f)
            .setDuration(160)
            .withEndAction { visibility = View.GONE }
            .start()
    }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density
}
