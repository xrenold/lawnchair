package app.lawnchair.metro.start

import android.graphics.Rect
import android.view.View
import app.lawnchair.metro.data.MetroTile

/**
 * Applies brand colours (Start and the background preview): only tiles you set to "Brand
 * color" get one, in the app's own colour (see MetroIcons). Nothing is coloured automatically,
 * so changing one tile never changes another.
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
        val picked = LinkedHashMap<TileView, Int>()

        for (v in views) {
            if (v.tile.colorFor(grid.windowMode) != MetroTile.COLOR_BRAND) continue
            val info = v.iconInfo ?: continue
            val c = if (info.brandTile != 0) info.brandTile else info.brandColor
            if (c == 0) continue
            picked[v] = c
        }
        var changed = false
        for (v in views) {
            val c = picked[v] ?: 0
            if (v.brandColor != c) {
                v.brandColor = c
                changed = true
            }
        }
        return changed
    }
}
