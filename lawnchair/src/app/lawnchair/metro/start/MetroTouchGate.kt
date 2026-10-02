package app.lawnchair.metro.start

import android.view.MotionEvent
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.LauncherState
import com.android.launcher3.util.TouchController

/**
 * Wraps one of Launcher3's DragLayer touch controllers so it ignores touches while the
 * Start screen is showing (normal state). Without this, swiping up or down to scroll the
 * tiles would open the app drawer or the notification shade.
 *
 * Once a gesture has been handed to the controller it keeps receiving it, so animations that
 * leave the normal state (e.g. closing the drawer) finish cleanly.
 */
class MetroTouchGate(
    private val launcher: LawnchairLauncher,
    private val delegate: TouchController,
) : TouchController {

    private var passThrough = false

    private fun allowed(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            passThrough = !launcher.isInState(LauncherState.NORMAL)
        }
        return passThrough
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean =
        allowed(ev) && delegate.onControllerInterceptTouchEvent(ev)

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean =
        allowed(ev) && delegate.onControllerTouchEvent(ev)

    override fun dump(): String = "MetroTouchGate(" + delegate.dump() + ")"

    override fun onTouchControllerDestroyed() = delegate.onTouchControllerDestroyed()
}
