package app.lawnchair.metro

import android.content.Context
import app.lawnchair.preferences.PreferenceManager

/** Global switches for the Metro UI, usable from Launcher3's Java code. */
object MetroMode {

    /** True when the Metro Start screen replaces the workspace, dock and page indicator. */
    @JvmStatic
    fun isStartEnabled(context: Context): Boolean =
        PreferenceManager.getInstance(context).metroTiles.get()
}
