package app.lawnchair.metro.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * The Windows Phone turnstile.
 *
 * Every element swings around one vertical hinge that sits off-screen, a third of a screen
 * width beyond the left edge, turning towards you like a door opening your way. Because the
 * hinge is off-screen, an element that has turned all the way is edge-on out of sight: nothing
 * piles up along the edge. Elements stay solid as they turn (only the last few degrees fade, so
 * a tile swinging close to you doesn't flash past).
 *
 * Leaving (opening an app), rows go top to bottom with a short beat between rows and the
 * tapped element last, after a pause. Coming back retraces it: the same hinge, the same order.
 *
 * [onFrame] is called on every frame so window-mode masks can follow the turning elements.
 */
object Turnstile {

    private const val OUT_MS = 230L
    private const val IN_MS = 300L
    private const val ROW_MS = 30L
    private const val COLUMN_MS = 8L
    private const val MAX_ROWS = 8
    /** Extra beat before the tapped element follows the others. */
    private const val LAST_PAUSE_MS = 70L
    /** Turned towards the viewer: negative is towards, on Android's rotationY. */
    private const val ANGLE = -88f
    /** The app starts once the tapped element is this far through its swing. */
    private const val LAUNCH_AT = 0.62f

    private val loc = IntArray(2)
    /** Running swings, so a new one (or [reset]) can stop the old. */
    private val running = java.util.WeakHashMap<View, ValueAnimator>()

    private fun track(v: View, a: ValueAnimator) {
        running.remove(v)?.cancel()
        running[v] = a
    }

    private fun screenTop(v: View): Int {
        v.getLocationOnScreen(loc)
        return loc[1]
    }

    private fun screenLeft(v: View): Int {
        v.getLocationOnScreen(loc)
        return loc[0]
    }

    /** Rows, top to bottom; each row left to right. Elements within ~a quarter tile share a row. */
    private fun rows(views: List<View>): List<List<View>> {
        if (views.isEmpty()) return emptyList()
        val sorted = views.sortedWith(compareBy<View>({ screenTop(it) }, { screenLeft(it) }))
        val out = ArrayList<MutableList<View>>()
        var rowTop = Int.MIN_VALUE
        for (v in sorted) {
            val t = screenTop(v)
            val tolerance = (v.height / 4).coerceAtLeast(1)
            if (out.isEmpty() || t - rowTop > tolerance) {
                out += mutableListOf(v)
                rowTop = t
            } else {
                out.last() += v
            }
        }
        out.forEach { row -> row.sortBy { screenLeft(it) } }
        return out
    }

    /** Hinge off-screen to the left, at the element's vertical centre. */
    private fun hinge(v: View) {
        val screenW = v.resources.displayMetrics.widthPixels
        v.pivotX = -(screenLeft(v) + screenW / 3f)
        v.pivotY = v.height / 2f
        // Far camera: elements swing a long arm towards it and mustn't pass through it.
        v.cameraDistance = 14000f * v.resources.displayMetrics.density
    }

    /** Delay for each element: one beat per row, a small step per column. */
    private fun delays(views: List<View>, last: View?): Map<View, Long> {
        val out = HashMap<View, Long>()
        var maxDelay = 0L
        rows(views.filter { it !== last }).forEachIndexed { r, row ->
            row.forEachIndexed { c, v ->
                val d = minOf(r, MAX_ROWS) * ROW_MS + c * COLUMN_MS
                out[v] = d
                maxDelay = maxOf(maxDelay, d)
            }
        }
        if (last != null) out[last] = if (out.isEmpty()) 0L else maxDelay + LAST_PAUSE_MS
        return out
    }

    private fun applyOut(v: View, p: Float) {
        v.rotationY = ANGLE * p
        // Solid while turning; gone over the last fifth.
        v.alpha = if (p < 0.8f) 1f else 1f - (p - 0.8f) / 0.2f
    }

    /**
     * Swings [views] away (the tapped [last] after a pause), calls [onLaunch] partway through the
     * last element's swing so the app's own start-up overlaps it, then [onEnd] once all are gone.
     */
    fun out(views: List<View>, last: View?, onFrame: () -> Unit, onLaunch: () -> Unit, onEnd: () -> Unit = {}) {
        val all = views.filter { it.width > 0 }
        if (all.isEmpty()) {
            onLaunch()
            onEnd()
            return
        }
        val delays = delays(all, last)
        val finalView = last ?: delays.maxByOrNull { it.value }!!.key
        var launched = false
        val accel = AccelerateInterpolator(1.4f)
        all.forEach { v ->
            hinge(v)
            v.animate().cancel()
            running.remove(v)?.cancel()
            ValueAnimator.ofFloat(0f, 1f).apply {
                track(v, this)
                startDelay = delays[v] ?: 0L
                duration = OUT_MS
                interpolator = accel
                addUpdateListener {
                    val f = it.animatedFraction
                    applyOut(v, it.animatedValue as Float)
                    onFrame()
                    if (v === finalView && !launched && f >= LAUNCH_AT) {
                        launched = true
                        onLaunch()
                    }
                }
                if (v === finalView) {
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (!launched) {
                                launched = true
                                onLaunch()
                            }
                            onEnd()
                        }
                    })
                }
                start()
            }
        }
    }

    /** Swings [views] back in from the turned-away position, retracing [out]. */
    fun into(views: List<View>, onFrame: () -> Unit) {
        val all = views.filter { it.width > 0 }
        val delays = delays(all, null)
        val decel = DecelerateInterpolator(1.8f)
        all.forEach { v ->
            hinge(v)
            v.animate().cancel()
            running.remove(v)?.cancel()
            applyOut(v, 1f)
            ValueAnimator.ofFloat(1f, 0f).apply {
                track(v, this)
                startDelay = delays[v] ?: 0L
                duration = IN_MS
                interpolator = decel
                addUpdateListener {
                    applyOut(v, it.animatedValue as Float)
                    onFrame()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        v.rotationY = 0f
                        v.alpha = 1f
                        onFrame()
                    }
                })
                start()
            }
        }
    }

    /** Puts views back to rest without animating. */
    fun reset(views: List<View>) {
        views.forEach {
            it.animate().cancel()
            running.remove(it)?.cancel()
            it.rotationY = 0f
            it.alpha = 1f
        }
    }
}
