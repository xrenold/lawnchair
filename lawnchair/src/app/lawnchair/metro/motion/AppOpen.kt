package app.lawnchair.metro.motion

import android.app.ActivityOptions
import android.content.Context
import android.os.Bundle
import android.view.View
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.R

/**
 * How apps open from Start and the app list (settings › app opening animation):
 * - turnstile: the tiles swing away (see [Turnstile]) and the app slides in from the right.
 * - zoom: the app grows out of the tile it was opened from.
 * - none: the app just appears.
 */
object AppOpen {
    const val TURNSTILE = "turnstile"
    const val ZOOM = "zoom"
    const val NONE = "none"

    fun mode(context: Context): String = PreferenceManager.getInstance(context).metroLaunchAnimation.get()

    fun turnstile(context: Context): Boolean = mode(context) == TURNSTILE

    /** Window animation for an app opened from [view]. */
    fun options(context: Context, view: View): Bundle? = when (mode(context)) {
        TURNSTILE -> ActivityOptions.makeCustomAnimation(context, R.anim.pane_app_open, R.anim.pane_stay).toBundle()
        NONE -> ActivityOptions.makeCustomAnimation(context, R.anim.pane_none, R.anim.pane_none).toBundle()
        else -> ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.width, view.height).toBundle()
    }
}
