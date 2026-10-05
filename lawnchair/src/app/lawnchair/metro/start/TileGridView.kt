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
 * Gaps: Start is broken into loose groups by slightly wider spaces between some rows, purely
 * for rhythm. They're placed automatically wherever no tile crosses the row line: one between
 * the live tiles at the top of the first screen and the easy-reach apps below them, then at
 * uneven intervals further down. Because they follow the tiles, they re-settle on their own
 * after tiles are moved.
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

    /**
     * Display order of the tiles. Kept separately from child order so a tile can be moved
     * while it is being dragged (re-adding the dragged view would cancel the touch).
     */
    private var order: List<View>? = null

    /** App and widget tiles, in display order (excludes the footer arrow). */
    val tiles: List<View>
        get() {
            val children = (0 until childCount).map { getChildAt(it) }.filter { it is TileHolder }
            val o = order ?: return children
            return o.filter { it.parent === this } + children.filter { it !in o }
        }

    private fun View.holder() = this as TileHolder

    fun setOrder(views: List<View>) {
        order = views
    }

    /** The tile being dragged: it follows the finger, so reflow animation skips it. */
    var draggedView: View? = null

    private var pendingReflow: Map<View, Pair<Int, Int>>? = null

    /** Lays out again and slides tiles from their old spots to their new ones. */
    fun animateReflow() {
        pendingReflow = tiles.associateWith { it.left to it.top }
        requestLayout()
    }

    /** Height of one row of small tiles, including the gap. */
    val rowPitch: Int get() = cellSize + gutter

    /** Extra space of a group gap, about half a small tile. */
    val groupGap: Int get() = (cellSize * 0.42f).toInt()

    /** Rows before which a group gap sits. */
    private var gapRows = IntArray(0)

    /** Rows of small tiles that fit in [viewportHeight] below the top inset (one gap allowed). */
    fun rowsInViewport(viewportHeight: Int): Int =
        ((viewportHeight - topPadding - bottomInset - groupGap + gutter) / rowPitch.coerceAtLeast(1)).coerceAtLeast(2)

    /** Top of cell row [row], counting the group gaps above it. */
    private fun rowTop(row: Int): Int = topPadding + row * (cellSize + gutter) + groupGap * gapRows.count { it <= row }

    /** Picks group-gap rows from where the tiles sit (see the class comment). */
    private fun computeGaps(tileViews: List<View>, usedRows: Int) {
        val viewport = (parent as? View)?.height ?: 0
        if (viewport <= 0 || usedRows < 4) {
            gapRows = IntArray(0)
            return
        }
        val crossing = BooleanArray(usedRows + 1)
        tileViews.forEachIndexed { i, v ->
            val top = positions[i * 2 + 1]
            for (r in top + 1 until top + v.holder().tile.size.rowSpan) if (r <= usedRows) crossing[r] = true
        }
        fun clean(r: Int) = r in 2 until usedRows && !crossing[r]
        val out = ArrayList<Int>()
        val screenRows = rowsInViewport(viewport)
        val screenBlocks = maxOf(2, screenRows / 2)
        val thumbBlocks = maxOf(1, Math.round(screenBlocks * 0.4f))
        // Between the glance area and the easy-reach area of the first screen.
        val split = (screenBlocks - thumbBlocks) * 2
        intArrayOf(0, 1, -1, 2, -2).map { split + it }.firstOrNull(::clean)?.let { out += it }
        // Below the first screen, at uneven intervals so the groups don't become a pattern.
        val intervals = intArrayOf(2, 3, 2, 1, 3)
        var next = screenBlocks * 2
        var k = 0
        while (next < usedRows) {
            val r = (next until usedRows).firstOrNull { clean(it) && out.none { g -> kotlin.math.abs(g - it) < 2 } } ?: break
            out += r
            next = r + intervals[k++ % intervals.size] * 2
        }
        gapRows = out.sorted().toIntArray()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellSize = ((width - gutter * (columns - 1)) / columns).coerceAtLeast(1)

        val tileViews = tiles
        val rows = pack(tileViews)
        for (child in tileViews) {
            val size = child.holder().tile.size
            val span = size.span.coerceAtMost(columns)
            val w = span * cellSize + (span - 1) * gutter
            val h = size.rowSpan * cellSize + (size.rowSpan - 1) * gutter
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        footer?.measure(
            MeasureSpec.makeMeasureSpec(footerSize, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(footerSize, MeasureSpec.EXACTLY),
        )
        computeGaps(tileViews, rows)
        val contentHeight = if (rows == 0) 0 else rows * cellSize + (rows - 1) * gutter + groupGap * gapRows.size
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
            val top = rowTop(positions[i * 2 + 1])
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
        footer?.let {
            val right = width - footerMargin
            val top = contentBottom + footerMargin
            it.layout(right - footerSize, top, right, top + footerSize)
        }
        pendingReflow?.let { before ->
            pendingReflow = null
            for ((view, pos) in before) {
                if (view === draggedView || view.parent !== this) continue
                val dx = (pos.first - view.left).toFloat()
                val dy = (pos.second - view.top).toFloat()
                if (dx == 0f && dy == 0f) continue
                view.translationX = dx
                view.translationY = dy
                view.animate().translationX(0f).translationY(0f).setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                    .setUpdateListener { invalidate() }.start()
            }
        }
        if (windowMode) invalidate() // tile holes moved
    }

    override fun onDraw(canvas: Canvas) {
        if (!windowMode) return
        holePath.rewind()
        for (c in tiles) {
            if (c.visibility != View.VISIBLE) continue
            val h = c.holder()
            if (h.lifted) {
                // Dragged tile: its window sits inside its black frame, on top of the others.
                val b = h.liftBorder
                tileRectPath.rewind()
                tileRectPath.addRect(b, b, c.width - b, c.height - b, Path.Direction.CW)
                tileRectPath.transform(c.matrix)
                tileRectPath.offset(c.left.toFloat(), c.top.toFloat())
                holePath.addPath(tileRectPath)
            } else if (c.matrix.isIdentity) {
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
    private fun pack(tileViews: List<View>): Int {
        positions = IntArray(tileViews.size * 2)
        val packer = app.lawnchair.metro.data.TilePacker(columns)
        tileViews.forEachIndexed { i, view ->
            val (col, row) = packer.place(view.holder().tile.size)
            positions[i * 2] = col
            positions[i * 2 + 1] = row
        }
        return packer.usedRows
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
