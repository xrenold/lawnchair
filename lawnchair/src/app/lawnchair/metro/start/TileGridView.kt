package app.lawnchair.metro.start

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import app.lawnchair.metro.data.TileSize

/**
 * Lays out [TileView]s on the Windows Phone grid.
 *
 * The grid is [columns] small-tile cells wide and runs edge to edge. Tiles are placed in list
 * order, each at the first free spot scanning top-to-bottom, left-to-right, so the Start screen
 * never has gaps the user didn't create. As on Windows Phone, tiles two or more cells wide only
 * start on even columns and rows, keeping medium tiles lined up in 2×2 blocks.
 *
 * An optional [footer] (the app-list arrow) sits below the last tile, so it only comes into
 * view at the end of the list, as on Windows Phone.
 */
class TileGridView(context: Context) : ViewGroup(context) {

    /**
     * Windows Phone 8.1 window mode: everything except the tiles is painted black, so the
     * fixed wallpaper only shows through the tiles. The mask is drawn here, in the same view
     * as the tiles, so it scrolls with them in lockstep. Each hole takes the tile's current
     * transform, so when a tile flips or is pressed, the window itself flips and shrinks.
     */
    var windowMode = false
        set(value) {
            field = value
            setWillNotDraw(!value)
            invalidate()
        }

    var columns = 4
        set(value) {
            field = value
            requestLayout()
        }

    /** Shown under the last tile, right-aligned. */
    var footer: View? = null
        set(value) {
            field?.let { removeView(it) }
            field = value
            value?.let { addView(it) }
        }
    var footerSize = dp(44f).toInt()
    private val footerMargin = dp(20f).toInt()

    private val gutter = dp(5f).toInt()
    var topPadding = dp(36f).toInt()
    /** Bottom system inset (gesture bar), kept clear below the footer. */
    var bottomInset = 0

    /** Cell positions from the last layout pass, parallel to [tiles]: [col, row]. */
    private var positions = IntArray(0)
    private var cellSize = 0
    private var contentBottom = 0

    private val holePath = Path()
    private val tileRectPath = Path()

    private val tiles: List<TileView>
        get() = (0 until childCount).mapNotNull { getChildAt(it) as? TileView }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellSize = ((width - gutter * (columns - 1)) / columns).coerceAtLeast(1)

        val tileViews = tiles
        val rows = pack(tileViews)
        for (child in tileViews) {
            val size = child.tile.size
            val span = size.span.coerceAtMost(columns)
            val w = span * cellSize + (span - 1) * gutter
            val h = size.rowSpan * cellSize + (size.rowSpan - 1) * gutter
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        footer?.measure(
            MeasureSpec.makeMeasureSpec(footerSize, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(footerSize, MeasureSpec.EXACTLY),
        )
        val contentHeight = if (rows == 0) 0 else rows * cellSize + (rows - 1) * gutter
        contentBottom = topPadding + contentHeight
        val footerSpace = if (footer != null) footerMargin * 2 + footerSize else dp(24f).toInt()
        var height = contentBottom + footerSpace + bottomInset
        // ScrollView's fillViewport passes the screen height; never be shorter than that, so the
        // window-mode mask always covers the whole screen.
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            height = maxOf(height, MeasureSpec.getSize(heightMeasureSpec))
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        tiles.forEachIndexed { i, child ->
            val left = positions[i * 2] * (cellSize + gutter)
            val top = topPadding + positions[i * 2 + 1] * (cellSize + gutter)
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
        footer?.let {
            val right = width - footerMargin
            val top = contentBottom + footerMargin
            it.layout(right - footerSize, top, right, top + footerSize)
        }
        if (windowMode) invalidate() // tile holes moved
    }

    override fun onDraw(canvas: Canvas) {
        if (!windowMode) return
        holePath.rewind()
        for (c in tiles) {
            if (c.visibility != View.VISIBLE) continue
            if (c.matrix.isIdentity) {
                holePath.addRect(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat(), Path.Direction.CW)
            } else {
                // Flipping or pressed: cut the hole in the tile's projected (3D-rotated) shape.
                tileRectPath.rewind()
                tileRectPath.addRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), Path.Direction.CW)
                tileRectPath.transform(c.matrix)
                tileRectPath.offset(c.left.toFloat(), c.top.toFloat())
                holePath.addPath(tileRectPath)
            }
        }
        val save = canvas.save()
        canvas.clipOutPath(holePath)
        canvas.drawColor(Color.BLACK)
        canvas.restoreToCount(save)
    }

    /** Places every tile; returns the number of rows used. */
    private fun pack(tileViews: List<TileView>): Int {
        positions = IntArray(tileViews.size * 2)
        val occupied = ArrayList<BooleanArray>()
        fun rowAt(r: Int): BooleanArray {
            while (occupied.size <= r) occupied += BooleanArray(columns)
            return occupied[r]
        }
        fun fits(col: Int, row: Int, span: Int, rowSpan: Int): Boolean {
            if (col + span > columns) return false
            for (r in row until row + rowSpan) {
                val line = rowAt(r)
                for (c in col until col + span) if (line[c]) return false
            }
            return true
        }

        var usedRows = 0
        tileViews.forEachIndexed { i, view ->
            val size = view.tile.size
            val span = size.span.coerceAtMost(columns)
            val rowSpan = size.rowSpan
            val aligned = span >= 2
            val alignRows = aligned && rowSpan >= 2
            var row = 0
            placing@ while (true) {
                if (alignRows && row % 2 != 0) {
                    row++
                    continue
                }
                var col = 0
                while (col + span <= columns) {
                    if (fits(col, row, span, rowSpan)) {
                        for (r in row until row + rowSpan) {
                            val line = rowAt(r)
                            for (c in col until col + span) line[c] = true
                        }
                        positions[i * 2] = col
                        positions[i * 2 + 1] = row
                        usedRows = maxOf(usedRows, row + rowSpan)
                        break@placing
                    }
                    col += if (aligned) 2 else 1
                }
                row++
            }
        }
        return usedRows
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
