package app.lawnchair.metro.start

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import app.lawnchair.metro.theme.MetroTheme
import kotlin.math.hypot

/**
 * The app-list arrow at the end of Start.
 *
 * Tap: opens the app list. Hold: after the usual long-press delay a halo starts growing
 * around the arrow, an accent ring filling clockwise with a soft glow and light ticks. Hold
 * until it closes to run auto layout; let go early to cancel.
 */
@SuppressLint("ViewConstructor")
class ArrowButton(
    context: Context,
    private val onTap: () -> Unit,
    private val onHoldComplete: () -> Unit,
) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()
    private val arc = RectF()

    /** 0..1 while holding. */
    private var progress = 0f
    private var holdAnim: ValueAnimator? = null
    private var lastTick = 0
    private var downX = 0f
    private var downY = 0f
    private var holding = false
    private var triggered = false

    private val startHold = Runnable {
        holding = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        holdAnim?.cancel()
        holdAnim = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = (HOLD_MS * (1f - progress)).toLong().coerceAtLeast(1)
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                val tick = (progress * 4).toInt()
                if (tick > lastTick && tick < 4) {
                    lastTick = tick
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (progress >= 1f && !triggered) complete()
                invalidate()
            }
            start()
        }
    }

    init {
        isClickable = true
        contentDescription = "All apps. Hold to arrange Start automatically"
    }

    private fun complete() {
        triggered = true
        holding = false
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        // A quick pulse, then the halo fades.
        animate().scaleX(1.18f).scaleY(1.18f).setDuration(110).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
        release(fade = true)
        onHoldComplete()
    }

    private fun release(fade: Boolean) {
        holdAnim?.cancel()
        holdAnim = ValueAnimator.ofFloat(progress, 0f).apply {
            duration = if (fade) 420 else 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        lastTick = 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                holding = false
                triggered = false
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(startHold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > width) cancelHold()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(startHold)
                when {
                    triggered -> Unit
                    holding -> release(fade = false)
                    else -> {
                        performClick()
                        onTap()
                    }
                }
                holding = false
            }
            MotionEvent.ACTION_CANCEL -> cancelHold()
        }
        return true
    }

    private fun cancelHold() {
        removeCallbacks(startHold)
        if (holding) release(fade = false)
        holding = false
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - dp(6f)
        val accent = MetroTheme.accent(context)

        // Halo: a soft glow that grows with the hold, and an accent ring filling clockwise.
        if (progress > 0f) {
            glow.color = accent
            glow.alpha = (90 * progress).toInt()
            canvas.drawCircle(cx, cy, r + dp(5f) * progress, glow)
            ring.color = accent
            ring.strokeWidth = dp(3f)
            arc.set(cx - r - dp(3f), cy - r - dp(3f), cx + r + dp(3f), cy + r + dp(3f))
            canvas.drawArc(arc, -90f, 360f * progress, false, ring)
        }

        stroke.strokeWidth = dp(1.5f)
        canvas.drawCircle(cx, cy, r, stroke)
        val a = r * 0.45f
        arrow.rewind()
        arrow.moveTo(cx - a, cy)
        arrow.lineTo(cx + a, cy)
        arrow.moveTo(cx + a * 0.45f, cy - a * 0.55f)
        arrow.lineTo(cx + a, cy)
        arrow.lineTo(cx + a * 0.45f, cy + a * 0.55f)
        canvas.drawPath(arrow, stroke)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        private const val HOLD_MS = 1500L
    }
}
