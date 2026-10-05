package app.lawnchair.metro.theme

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.ColorUtils
import app.lawnchair.preferences.PreferenceManager

/**
 * Metro colour engine.
 *
 * Tile colours come from one of three sources:
 *  - **monet**: a single Material You accent for every tile (closest to Windows Phone).
 *  - **monet_tonal**: each tile gets a stable pick from the accent1/2/3 tonal palettes.
 *  - **classic**: one of the 20 original Windows Phone accent colours.
 *
 * Monet colours are re-read on every call, so they follow the system palette. [Listener]
 * reports wallpaper colour changes so the Start screen can repaint without a restart.
 */
object MetroTheme {

    /** The 20 Windows Phone 8.1 accent colours, in the order the phone listed them. */
    @JvmField
    val CLASSIC_ACCENTS: LinkedHashMap<String, Int> = linkedMapOf(
        "lime" to 0xFFA4C400.toInt(),
        "green" to 0xFF60A917.toInt(),
        "emerald" to 0xFF008A00.toInt(),
        "teal" to 0xFF00ABA9.toInt(),
        "cyan" to 0xFF1BA1E2.toInt(),
        "cobalt" to 0xFF0050EF.toInt(),
        "indigo" to 0xFF6A00FF.toInt(),
        "violet" to 0xFFAA00FF.toInt(),
        "pink" to 0xFFF472D0.toInt(),
        "magenta" to 0xFFD80073.toInt(),
        "crimson" to 0xFFA20025.toInt(),
        "red" to 0xFFE51400.toInt(),
        "orange" to 0xFFFA6800.toInt(),
        "amber" to 0xFFF0A30A.toInt(),
        "yellow" to 0xFFE3C800.toInt(),
        "brown" to 0xFF825A2C.toInt(),
        "olive" to 0xFF6D8764.toInt(),
        "steel" to 0xFF647687.toInt(),
        "mauve" to 0xFF76608A.toInt(),
        "taupe" to 0xFF87794E.toInt(),
    )

    const val MODE_MONET = "monet"
    const val MODE_MONET_TONAL = "monet_tonal"
    const val MODE_CLASSIC = "classic"

    const val BG_BLACK = "black"
    const val BG_WALLPAPER = "wallpaper"
    const val BG_WINDOW = "window"

    private fun prefs(context: Context) = PreferenceManager.getInstance(context)

    @JvmStatic
    fun background(context: Context): String = prefs(context).metroBackground.get()

    @JvmStatic
    fun columns(context: Context) = if (prefs(context).metroShowMoreTiles.get()) 6 else 4

    /** The Start screen's main accent: used for app list letters, buttons and single-accent tiles. */
    @JvmStatic
    fun accent(context: Context): Int {
        return when (prefs(context).metroColorMode.get()) {
            MODE_CLASSIC -> classicAccent(context)
            else -> monet(context, android.R.color.system_accent1_600, CLASSIC_ACCENTS.getValue("cyan"))
        }
    }

    /**
     * Base colour of a tile, before opacity. [overrideColor] is a per-tile choice (0 = none),
     * [stableKey] keeps the tonal pick the same for a tile across launches.
     */
    @JvmStatic
    fun tileColor(context: Context, stableKey: String, overrideColor: Int): Int {
        if (Color.alpha(overrideColor) != 0) return overrideColor // brand/accent sentinels have none
        return when (prefs(context).metroColorMode.get()) {
            MODE_CLASSIC -> classicAccent(context)
            MODE_MONET_TONAL -> tonal(context, stableKey)
            else -> accent(context)
        }
    }

    /** White, or a dark tone on very light tiles so labels stay readable. */
    @JvmStatic
    fun onTileColor(tileColor: Int): Int =
        if (ColorUtils.calculateLuminance(tileColor) > 0.6) 0xFF1A1A1A.toInt() else Color.WHITE

    private fun classicAccent(context: Context): Int =
        CLASSIC_ACCENTS[prefs(context).metroClassicAccent.get()] ?: CLASSIC_ACCENTS.getValue("cobalt")

    private val TONAL_IDS = intArrayOf(
        android.R.color.system_accent1_500,
        android.R.color.system_accent1_600,
        android.R.color.system_accent1_700,
        android.R.color.system_accent2_500,
        android.R.color.system_accent2_600,
        android.R.color.system_accent2_700,
        android.R.color.system_accent3_500,
        android.R.color.system_accent3_600,
        android.R.color.system_accent3_700,
    )

    private fun tonal(context: Context, key: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return accent(context)
        val index = (key.hashCode() and 0x7FFFFFFF) % TONAL_IDS.size
        return context.getColor(TONAL_IDS[index])
    }

    private fun monet(context: Context, id: Int, fallback: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getColor(id) else fallback

    /**
     * Calls [onChange] on the main thread whenever the wallpaper colours change, which is
     * when Android regenerates the Monet palette. Call [close] to stop listening.
     */
    class Listener(context: Context, private val onChange: Runnable) : AutoCloseable {
        private val wallpaperManager = WallpaperManager.getInstance(context)
        private val handler = Handler(Looper.getMainLooper())
        private val callback = WallpaperManager.OnColorsChangedListener { _, _ ->
            // The system palette updates slightly after the wallpaper colours; wait a moment.
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(onChange, 1200)
        }

        init {
            wallpaperManager.addOnColorsChangedListener(callback, handler)
        }

        override fun close() {
            handler.removeCallbacksAndMessages(null)
            wallpaperManager.removeOnColorsChangedListener(callback)
        }
    }
}
