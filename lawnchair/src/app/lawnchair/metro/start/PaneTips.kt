package app.lawnchair.metro.start

import android.content.Context
import app.lawnchair.preferences.PreferenceManager

/**
 * One-time tips after setup, each shown once in the slim bar at the bottom of Start, at the
 * moment it's useful. At most one a day (except the layout lock tip, which answers what you just
 * did), never before setup is finished. "Show tips again" in Settings resets them.
 */
object PaneTips {

    enum class Tip(val text: String) {
        TILES("Hold a tile to resize, move or recolour it."),
        APP_LIST("Swipe left for all your apps."),
        LETTERS("Tap a letter to jump through the list."),
        AUTO_LAYOUT("Hold the arrow at the end of Start to arrange it for you."),
        LIVE("Tiles turn over to show what's new."),
        LIVE_OFF("Turn on notification access in settings to bring tiles to life."),
        LOCKED("Unlock any time from the long-press menu."),
    }

    private const val PREFS = "pane_tips"
    private const val DAY = 24 * 60 * 60 * 1000L

    private fun store(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seen(context: Context, tip: Tip) = store(context).getBoolean("seen_${tip.name}", false)

    /**
     * Whether [tip] may show now; if so it's marked as shown. [now] skips the once-a-day limit
     * for tips that answer something the user just did.
     */
    fun claim(context: Context, tip: Tip, now: Boolean = false): Boolean {
        if (!PreferenceManager.getInstance(context).paneOnboarded.get()) return false
        val s = store(context)
        if (s.getBoolean("seen_${tip.name}", false)) return false
        val last = s.getLong("last", 0L)
        if (!now && System.currentTimeMillis() - last < DAY) return false
        s.edit().putBoolean("seen_${tip.name}", true).putLong("last", System.currentTimeMillis()).apply()
        // The two notification tips are one idea: once either has shown, the other is done too.
        if (tip == Tip.LIVE || tip == Tip.LIVE_OFF) {
            s.edit().putBoolean("seen_${Tip.LIVE.name}", true).putBoolean("seen_${Tip.LIVE_OFF.name}", true).apply()
        }
        return true
    }

    /** Settings › Show tips again. */
    fun reset(context: Context) = store(context).edit().clear().apply()
}
