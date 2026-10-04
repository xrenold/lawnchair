package app.lawnchair.metro.start

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.sign

/**
 * ScrollView with the Windows Phone edge bounce.
 *
 * Pulling past the top or bottom drags the tiles with rising resistance; letting go, or a fling
 * that hits an edge, springs them back with a quick, slightly elastic settle.
 *
 * The bounce moves the content by scrolling the child itself ([View.scrollTo]) rather than
 * translating views, so the window-mode mask drawn by the grid moves with the tiles exactly.
 */
@SuppressLint("ViewConstructor")
class BounceScrollView(context: Context) : ScrollView(context) {

    /** Called with the current scroll fraction (0 = top, 1 = bottom) on every scroll change. */
    var onScrollFraction: ((Float) -> Unit)? = null

    /** Bounce offset in px. Positive = content pushed down (top edge), negative = up. */
    private var offset = 0f
    private val maxOffset get() = height * 0.18f

    private val holder = FloatValueHolder()
    private val spring = SpringAnimation(holder).apply {
        spring = SpringForce(0f).apply {
            stiffness = 900f // snappy return
            dampingRatio = 0.72f // one small overshoot, no wobble
        }
        addUpdateListener { _, value, _ -> applyOffset(value) }
    }

    private var touching = false
    private var lastY = 0f

    // Scroll velocity estimate, for fling impacts at the edges.
    private var lastScrollY = 0
    private var lastScrollTime = 0L
    private var scrollVelocity = 0f // px/s, positive = scrolling down the list

    init {
        overScrollMode = OVER_SCROLL_NEVER
    }

    private val content: View? get() = if (childCount > 0) getChildAt(0) else null

    private fun applyOffset(value: Float) {
        offset = value
        content?.scrollTo(0, -value.toInt())
    }

    private fun maxScroll(): Int = ((content?.height ?: 0) - height).coerceAtLeast(0)

    /** Finger movement since the previous event, tracked for every gesture. */
    private var pendingDy = 0f

    // Tracked here rather than in onTouchEvent: when a drag starts on a tile, ScrollView only
    // starts receiving onTouchEvent mid-gesture, without the ACTION_DOWN.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                lastY = ev.y
                pendingDy = 0f
                if (spring.isRunning) spring.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                pendingDy = ev.y - lastY
                lastY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                if (offset != 0f) {
                    spring.setStartValue(offset)
                    spring.setStartVelocity(0f)
                    spring.start()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            val dy = pendingDy
            val before = scrollY
            val atTop = scrollY <= 0
            val atBottom = scrollY >= maxScroll()
            val pullingPastTop = atTop && (dy > 0 || offset > 0f)
            val pullingPastBottom = atBottom && (dy < 0 || offset < 0f)
            if (pullingPastTop || pullingPastBottom) {
                // Resistance grows as the content is pulled further.
                val resistance = 0.5f * (1f - (abs(offset) / maxOffset).coerceIn(0f, 0.95f))
                var next = offset + dy * resistance
                // Moving back towards rest: release the offset first, then scroll normally.
                if (offset != 0f && sign(next) != sign(offset)) next = 0f
                applyOffset(next.coerceIn(-maxOffset, maxOffset))
                val handled = super.onTouchEvent(ev)
                if (scrollY != before) scrollTo(0, before) // the bounce took this movement
                return handled
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastScrollTime).coerceAtLeast(1)
        if (dt < 100) scrollVelocity = (t - lastScrollY) * 1000f / dt
        lastScrollY = t
        lastScrollTime = now
        val max = maxScroll()
        onScrollFraction?.invoke(if (max == 0) 0f else (t.toFloat() / max).coerceIn(0f, 1f))
    }

    /** A fling ran into the top or bottom: let the content carry on a little and spring back. */
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (!clampedY || touching || spring.isRunning) return
        val v = scrollVelocity
        if (abs(v) < 600f) return
        scrollVelocity = 0f
        // Scrolling up into the top pushes content down (positive offset), and vice versa.
        val impulse = (-v * 0.45f).coerceIn(-9000f, 9000f)
        spring.setStartValue(offset)
        spring.setStartVelocity(impulse)
        spring.start()
    }
}
