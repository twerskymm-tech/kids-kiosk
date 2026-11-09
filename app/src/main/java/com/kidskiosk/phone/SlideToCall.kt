package com.kidskiosk.phone

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView

/**
 * A call button that has to be slid, so a stray tap doesn't call anyone.
 * Drag the round phone knob all the way to the right to call.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SlideToCall(c: Context, label: String, private val onCall: () -> Unit) : FrameLayout(c) {
    private val green = 0xFF22B573.toInt()
    private val hint = c.text("$label   ››", 17f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val knob = TextView(c).apply {
        text = "📞"; textSize = 26f; gravity = Gravity.CENTER
        background = rounded(Color.WHITE, c.dpf(30f))
        elevation = c.dpf(4f)
    }
    private var startX = 0f
    private var knobStart = 0f
    private var fired = false

    init {
        background = rounded(0xCC1E8F5C.toInt(), c.dpf(32f))
        setPadding(c.dp(5), c.dp(5), c.dp(5), c.dp(5))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { marginStart = c.dp(56) })
        addView(knob, LayoutParams(c.dp(54), c.dp(54), Gravity.START or Gravity.CENTER_VERTICAL))
        knob.setTextColor(green)
        hint.typeface = Typeface.DEFAULT_BOLD
    }

    private fun maxX() = (width - paddingLeft - paddingRight - knob.width).toFloat().coerceAtLeast(1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only a drag that starts on (or near) the knob counts.
                if (e.x > paddingLeft + knob.translationX + knob.width + context.dp(24)) return false
                startX = e.x; knobStart = knob.translationX; fired = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = (knobStart + e.x - startX).coerceIn(0f, maxX())
                knob.translationX = x
                hint.alpha = 1f - x / maxX()
                if (!fired && x >= maxX() * 0.9f) {
                    fired = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onCall()
                    settle()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { settle(); return true }
        }
        return super.onTouchEvent(e)
    }

    private fun settle() {
        ValueAnimator.ofFloat(knob.translationX, 0f).apply {
            duration = 250
            addUpdateListener { knob.translationX = it.animatedValue as Float; hint.alpha = 1f - knob.translationX / maxX() }
            start()
        }
    }
}
