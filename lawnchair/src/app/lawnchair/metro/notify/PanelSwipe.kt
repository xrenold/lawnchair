package app.lawnchair.metro.notify

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs

/**
 * The sideways swipe that opens a [NotificationPanel], shared by Start (left to right) and the
 * app list (right to left).
 *
 * Once a swipe on a tile with notifications is clearly sideways, it takes over the gesture (so
 * Start stops scrolling and the tile isn't pressed) and the panel slides out with the finger.
 * Let go past about a third of the width, or flick, and it opens; let go before that and it
 * springs back. A light haptic tick marks the point where it will open.
 */
class PanelSwipe(
    private val host: ViewGroup,
    /** Panel target under (x, y) in host coordinates, or null. */
    private val find: (Float, Float) -> NotificationPanel.Target?,
    /** Creates and adds the panel (at progress 0) for a target. */
    private val create: (NotificationPanel.Target) -> NotificationPanel,
    /** 1: swipe left-to-right opens (Start). -1: right-to-left opens (app list). */
    private val direction: Int = 1,
) {
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var target: NotificationPanel.Target? = null
    private var downX = 0f
    private var downY = 0f
    private var panel: NotificationPanel? = null
    private var pastThreshold = false
    private var velocity: VelocityTracker? = null

    /** True while this gesture owns the touch stream. */
    val active: Boolean get() = panel != null

    /**
     * Feed every touch event of the host. Returns true when the event was consumed; the host
     * should then cancel whatever its children were doing with the gesture (see [cancelChildren]).
     */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panel = null
                pastThreshold = false
                downX = ev.x
                downY = ev.y
                target = find(ev.x, ev.y)
                velocity?.recycle()
                velocity = if (target != null) VelocityTracker.obtain() else null
            }
        }
        velocity?.addMovement(ev)
        val t = target ?: return false
        val dx = (ev.x - downX) * direction
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (panel == null) {
                    if (ev.pointerCount > 1) {
                        target = null
                        return false
                    }
                    if (dx > slop * 2 && dx > abs(ev.y - downY) * 1.4f) {
                        panel = create(t)
                        cancelChildren(ev)
                    } else {
                        if (abs(ev.y - downY) > slop * 2 || dx < -slop * 2) target = null
                        return false
                    }
                }
                val p = (dx - slop * 2) / (host.width * OPEN_AT * 2f)
                panel?.setProgress(p)
                val past = dx > host.width * OPEN_AT
                if (past != pastThreshold) {
                    pastThreshold = past
                    if (past) host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val p = panel ?: run {
                    target = null
                    return false
                }
                velocity?.computeCurrentVelocity(1000)
                val vx = (velocity?.xVelocity ?: 0f) * direction
                val fling = vx > host.resources.displayMetrics.density * 800
                if (ev.actionMasked == MotionEvent.ACTION_UP && (pastThreshold || fling)) p.animateOpen() else p.close(animate = true)
                panel = null
                target = null
                return true
            }
        }
        return panel != null
    }

    private fun cancelChildren(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        for (i in 0 until host.childCount) {
            val c: View = host.getChildAt(i)
            if (c !is NotificationPanel) c.dispatchTouchEvent(cancel)
        }
        cancel.recycle()
    }

    companion object {
        /** Past this fraction of the width, letting go opens the panel. */
        const val OPEN_AT = 1f / 3f
    }
}
