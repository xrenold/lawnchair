package app.lawnchair.metro.data

import android.content.Context
import android.widget.Toast
import app.lawnchair.preferences.PreferenceManager

/**
 * Start layout lock. While locked, tiles can't be moved, resized, unpinned or rearranged by
 * auto layout, and nothing new is pinned. Each tile's own settings (live tile, colour,
 * slideshow) still work.
 *
 * Pinning while locked goes through [guard]: Start shows "Start is locked" with an Unlock
 * button, which unlocks and then finishes the pin.
 */
object LayoutLock {

    @JvmStatic
    fun isLocked(context: Context): Boolean = PreferenceManager.getInstance(context).metroLayoutLocked.get()

    fun setLocked(context: Context, locked: Boolean) = PreferenceManager.getInstance(context).metroLayoutLocked.set(locked)

    /**
     * Shows the "Start is locked" bar; its Unlock button calls the given action. Set by Start
     * while it's on screen.
     */
    var showLockedBar: ((onUnlock: () -> Unit) -> Unit)? = null

    /**
     * Runs [action] now if Start isn't locked. Otherwise offers to unlock (and then runs it);
     * returns false in that case.
     */
    fun guard(context: Context, action: () -> Unit): Boolean {
        if (!isLocked(context)) {
            action()
            return true
        }
        val bar = showLockedBar
        if (bar != null) {
            bar {
                setLocked(context, false)
                action()
            }
        } else {
            Toast.makeText(context, "Start is locked. Unlock it to add tiles.", Toast.LENGTH_SHORT).show()
        }
        return false
    }
}
