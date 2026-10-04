package app.lawnchair.metro.start

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import app.lawnchair.metro.data.TileSize

/**
 * Lays out [TileView]s on the Windows Phone grid.
 *
 * The grid is [columns] small-tile cells wide. Tiles are placed in list order, each at the
 * first free spot scanning top-to-bottom, left-to-right, so the Start screen never has gaps
 * that the user didn't create. As on Windows Phone, tiles two or more cells wide only start
 * on even columns and rows, keeping medium tiles lined up in 2×2 blocks.
 */
class TileGridView(context: Context) : ViewGroup(context) {

    /**
     * Windows Phone 8.1 window mode: everything except the tiles is painted black, so the
     * fixed wallpaper only shows through the tiles. The mask is drawn here, in the same view
     * as the tiles, so it scrolls with them in lockstep and is only redrawn on layout.
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

    private val gutter = dp(5f).toInt()
    private val sideMargin = dp(12f).toInt()
    /** Space after the last tile so the app-list arrow never covers it. */
    var bottomPadding = dp(96f).toInt()
    var topPadding = dp(36f).toInt()

    /** Cell positions from the last layout pass, parallel to child order: [col, row]. */
    private var positions = IntArray(0)
    private var cellSize = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellSize = ((width - sideMargin * 2 - gutter * (columns - 1)) / columns).coerceAtLeast(1)

        val rows = pack()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val size = sizeOf(child)
            val span = size.span.coerceAtMost(columns)
            val w = span * cellSize + (span - 1) * gutter
            val h = size.rowSpan * cellSize + (size.rowSpan - 1) * gutter
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        val contentHeight = if (rows == 0) 0 else rows * cellSize + (rows - 1) * gutter
        var height = topPadding + contentHeight + bottomPadding
        // ScrollView's fillViewport passes the screen height; never be shorter than that, so the
        // window-mode mask always covers the whole screen.
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            height = maxOf(height, MeasureSpec.getSize(heightMeasureSpec))
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val col = positions[i * 2]
            val row = positions[i * 2 + 1]
            val left = sideMargin + col * (cellSize + gutter)
            val top = topPadding + row * (cellSize + gutter)
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
        if (windowMode) invalidate() // tile holes moved
    }

    override fun onDraw(canvas: Canvas) {
        if (!windowMode) return
        val save = canvas.save()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.VISIBLE) {
                canvas.clipOutRect(c.left, c.top, c.right, c.bottom)
            }
        }
        canvas.drawColor(Color.BLACK)
        canvas.restoreToCount(save)
    }

    /** Places every child; returns the number of rows used. */
    private fun pack(): Int {
        positions = IntArray(childCount * 2)
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
        for (i in 0 until childCount) {
            val size = sizeOf(getChildAt(i))
            val span = size.span.coerceAtMost(columns)
            val rowSpan = size.rowSpan
            val aligned = span >= 2
            var row = 0
            placing@ while (true) {
                if (aligned && row % 2 != 0) {
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

    private fun sizeOf(child: View): TileSize = (child as? TileView)?.tile?.size ?: TileSize.MEDIUM

    /** Bounds of every laid-out tile, in this view's coordinates (used for window mode). */
    fun forEachTileBounds(block: (left: Float, top: Float, right: Float, bottom: Float) -> Unit) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            block(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat())
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
