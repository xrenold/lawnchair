package app.lawnchair.metro.theme

import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.lawnchair.LawnchairApp
import com.android.launcher3.R

/**
 * Pane's typeface: Selawik (Microsoft, SIL Open Font License), bundled so text looks the
 * same on every phone. Characters Selawik lacks (non-Latin scripts, some symbols) fall
 * back to the system fonts automatically.
 */
object PaneFonts {

    private fun load(res: Int, fallback: String): Typeface = runCatching {
        ResourcesCompat.getFont(LawnchairApp.instance, res)
    }.getOrNull() ?: Typeface.create(fallback, Typeface.NORMAL)

    /** Big numbers, page titles, tile headlines. */
    @JvmStatic val light: Typeface by lazy { load(R.font.selawik_light, "sans-serif-light") }

    /** Secondary headings. */
    @JvmStatic val semilight: Typeface by lazy { load(R.font.selawik_semilight, "sans-serif-light") }

    /** Tile names, body text. */
    @JvmStatic val regular: Typeface by lazy { load(R.font.selawik_regular, "sans-serif") }

    /** Counts, buttons, emphasis. */
    @JvmStatic val semibold: Typeface by lazy { load(R.font.selawik_semibold, "sans-serif-medium") }

    @JvmStatic val bold: Typeface by lazy { load(R.font.selawik_bold, "sans-serif") }
}
