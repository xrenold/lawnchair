package app.lawnchair.metro.applist

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import app.lawnchair.metro.theme.MetroTheme
import kotlin.math.abs
import kotlin.math.exp

/**
 * Niagara-style alphabet scrubber.
 *
 * A thin column of letters on the right edge. Touch it and slide: the letters near your finger
 * bulge out towards you in a smooth wave and grow, the current letter lights up in the accent
 * colour with a large bubble beside your finger, and the list jumps to that letter with a
 * light tick. Letters with no apps are dimmed and skipped.
 *
 * The view is wider than the visible column so the wave and bubble have room; only touches
 * that start on the column itself are taken.
 */
@SuppressLint("ViewConstructor")
class AlphabetScrubber(context: Context, private val onLetter: (Char) -> Unit) : View(context) {

    var letters: List<Char> = LETTERS
    var available: Set<Char> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    private val columnWidth = dp(30f)
    private val waveReach = dp(64f)
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = app.lawnchair.metro.theme.PaneFonts.semibold
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = app.lawnchair.metro.theme.PaneFonts.light
    }

    private var touchY = 0f
    private var current: Char? = null
    /** 0 = resting, 1 = finger down; animated so the wave grows and settles smoothly. */
    private var active = 0f
    private var activeAnim: ValueAnimator? = null

    /** Space kept free at the top and bottom (search bar, gesture bar). */
    var topInset = 0f
    var bottomInset = 0f

    /**
     * Letters start a third of the way down the screen, within easy thumb reach; the top third
     * of the edge is left to normal scrolling.
     */
    private val letterTop: Float get() = maxOf(topInset, height / 3f)

    private fun spacing(): Float = (height - letterTop - bottomInset) / letters.size
    private fun letterY(i: Int): Float = letterTop + spacing() * (i + 0.5f)

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val accent = MetroTheme.accent(context)
        val baseX = width - columnWidth / 2f
        val gap = spacing()
        val baseSize = minOf(sp(11f), gap * 0.8f)

        letters.forEachIndexed { i, c ->
            val y = letterY(i)
            // Gaussian wave around the finger: strongest at the touch point, fading over ~3 rows.
            val d = (y - touchY) / gap
            val influence = active * exp(-(d * d) / (2f * 2.4f * 2.4f))
            val x = baseX - influence * waveReach
            val scale = 1f + influence * 1.1f
            val isCurrent = active > 0f && c == current
            val has = c in available
            letterPaint.textSize = baseSize * scale
            letterPaint.color = when {
                isCurrent -> accent
                has -> Color.WHITE
                else -> 0x55FFFFFF
            }
            letterPaint.alpha = if (has || isCurrent) (170 + 85 * influence).toInt().coerceAtMost(255) else 70
            val fm = letterPaint.fontMetrics
            canvas.drawText(c.toString(), x, y - (fm.ascent + fm.descent) / 2f, letterPaint)
        }

        // Big letter bubble to the left of the finger.
        val sel = current
        if (active > 0.01f && sel != null) {
            val r = dp(34f) * active
            val cx = baseX - waveReach - dp(64f)
            val lo = letterTop + r
            val hi = height - bottomInset - r
            val cy = if (lo <= hi) touchY.coerceIn(lo, hi) else (letterTop + height - bottomInset) / 2f
            bubblePaint.color = accent
            bubblePaint.alpha = (235 * active).toInt()
            canvas.drawCircle(cx, cy, r, bubblePaint)
            bubbleText.textSize = sp(32f) * active
            val fm = bubbleText.fontMetrics
            canvas.drawText(sel.lowercaseChar().toString(), cx, cy - (fm.ascent + fm.descent) / 2f, bubbleText)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (ev.x < width - columnWidth - dp(8f) || ev.y < letterTop - dp(8f) || spacing() <= 0f) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                animateActive(1f)
                track(ev.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> track(ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animateActive(0f)
                current = null
            }
        }
        return true
    }

    private fun track(y: Float) {
        touchY = y
        val raw = ((y - letterTop) / spacing()).toInt().coerceIn(0, letters.size - 1)
        // Snap to the nearest letter that has apps.
        val target = letters.indices
            .filter { letters[it] in available }
            .minByOrNull { abs(it - raw) }
            ?.let { letters[it] }
        if (target != null && target != current) {
            current = target
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onLetter(target)
        }
        invalidate()
    }

    private fun animateActive(to: Float) {
        activeAnim?.cancel()
        activeAnim = ValueAnimator.ofFloat(active, to).apply {
            duration = if (to > active) 140 else 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                active = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        val LETTERS: List<Char> = listOf('#') + ('A'..'Z')
    }
}
