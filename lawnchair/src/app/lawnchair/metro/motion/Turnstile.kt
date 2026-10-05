package app.lawnchair.metro.motion

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * The Windows Phone turnstile.
 *
 * Every element swings around a vertical hinge on the left edge of the screen, like pages of a
 * book. Leaving (opening an app), elements swing away from top to bottom in a quick cascade,
 * with the tapped one going last. Arriving (back on Start), they swing back in the same order.
 *
 * [onFrame] is called on every frame so window-mode masks can follow the rotating elements.
 */
object Turnstile {

    private const val OUT_MS = 190L
    private const val IN_MS = 260L
    private const val STAGGER_MS = 18L
    private const val MAX_STAGGER = 10
    private const val ANGLE = 85f

    /** Elements ordered as the eye reads them: top to bottom, then left to right. */
    private fun order(views: List<View>): List<View> =
        views.sortedWith(compareBy<View>({ screenTop(it) / 8 }, { screenLeft(it) }))

    private fun screenTop(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[1]
    }

    private fun screenLeft(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[0]
    }

    /** Hinge on the screen's left edge, at the element's vertical centre. */
    private fun hinge(v: View) {
        v.pivotX = -screenLeft(v).toFloat()
        v.pivotY = v.height / 2f
        v.cameraDistance = 9000f * v.resources.displayMetrics.density
    }

    /** Swings [views] away, [last] after the others, then runs [onEnd]. */
    fun out(views: List<View>, last: View?, onFrame: () -> Unit, onEnd: () -> Unit) {
        val ordered = order(views.filter { it !== last }) + listOfNotNull(last)
        if (ordered.isEmpty()) {
            onEnd()
            return
        }
        ordered.forEachIndexed { i, v ->
            hinge(v)
            v.animate().cancel()
            val anim = v.animate()
                .rotationY(ANGLE)
                .alpha(0f)
                .setStartDelay(minOf(i, MAX_STAGGER) * STAGGER_MS)
                .setDuration(OUT_MS)
                .setInterpolator(AccelerateInterpolator(1.6f))
                .setUpdateListener { onFrame() }
            if (i == ordered.lastIndex) anim.withEndAction(onEnd)
            anim.start()
        }
    }

    /** Swings [views] back in from their turned-away position. */
    fun into(views: List<View>, onFrame: () -> Unit) {
        order(views).forEachIndexed { i, v ->
            hinge(v)
            v.animate().cancel()
            v.rotationY = -ANGLE
            v.alpha = 0f
            v.animate()
                .rotationY(0f)
                .alpha(1f)
                .setStartDelay(minOf(i, MAX_STAGGER) * STAGGER_MS)
                .setDuration(IN_MS)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .setUpdateListener { onFrame() }
                .withEndAction { onFrame() }
                .start()
        }
    }

    /** Puts views back to rest without animating. */
    fun reset(views: List<View>) {
        views.forEach {
            it.animate().cancel()
            it.rotationY = 0f
            it.alpha = 1f
        }
    }
}
