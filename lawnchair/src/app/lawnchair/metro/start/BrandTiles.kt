package app.lawnchair.metro.start

import android.graphics.Rect
import android.view.View
import app.lawnchair.metro.data.MetroTile

/**
 * Picks which tiles show their app's brand colour (Start and the background preview).
 *
 * Installed apps with one clear colour qualify (see MetroIcons). Within each screen-height band
 * of Start, automatic brand colour covers at most a quarter of the area, measured in cells (a
 * wide tile counts twice a medium one), and two automatic brand tiles never sit side by side;
 * the clearest brand colours win. Tiles set to "Brand color" always get it, independently.
 */
object BrandTiles {

    private val a = Rect()
    private val b = Rect()

    /** True when two views share an edge or corner (or overlap), allowing [slack] px. */
    fun touches(x: View, y: View, slack: Int): Boolean {
        a.set(x.left - slack, x.top - slack, x.right + slack, x.bottom + slack)
        b.set(y.left, y.top, y.right, y.bottom)
        return Rect.intersects(a, b)
    }

    /** Applies brand colours to [grid]'s tiles. Returns true if any tile changed. */
    fun assign(grid: TileGridView, viewportHeight: Int): Boolean {
        val views = grid.tiles.filterIsInstance<TileView>()
        val slack = (grid.resources.displayMetrics.density * 8).toInt()
        val bandRows = grid.rowsInViewport(viewportHeight).coerceAtLeast(2)
        val bandArea = grid.columns * bandRows
        // Tiles you set to "Brand color" always get it, and are kept apart from the automatic
        // picks: they don't use up the quarter, and automatic tiles don't avoid them. So
        // setting one tile never changes another.
        val picked = LinkedHashMap<TileView, Int>()
        val chosen = LinkedHashMap<TileView, Int>()
        val usedArea = HashMap<Int, Int>()
        fun band(v: View) = grid.cellRowOf(v).coerceAtLeast(0) / bandRows
        fun area(v: TileView) = v.tile.size.span.coerceAtMost(grid.columns) * v.tile.size.rowSpan

        for (v in views) {
            if (v.tile.colorFor(grid.windowMode) != MetroTile.COLOR_BRAND) continue
            val info = v.iconInfo ?: continue
            val c = if (info.brandTile != 0) info.brandTile else info.brandColor
            if (c == 0) continue
            picked[v] = c
        }
        val candidates = views
            .filter { it.tile.colorFor(grid.windowMode) == 0 && (it.iconInfo?.brandTile ?: 0) != 0 }
            .sortedWith(compareByDescending<TileView> { it.iconInfo!!.brandStrength }.thenBy { area(it) })
        for (v in candidates) {
            val band = band(v)
            if ((usedArea[band] ?: 0) + area(v) > bandArea / 4f) continue
            if (chosen.keys.any { touches(it, v, slack) }) continue
            chosen[v] = v.iconInfo!!.brandTile
            usedArea.merge(band, area(v), Int::plus)
        }
        var changed = false
        for (v in views) {
            val c = picked[v] ?: chosen[v] ?: 0
            if (v.brandColor != c) {
                v.brandColor = c
                changed = true
            }
        }
        return changed
    }
}
