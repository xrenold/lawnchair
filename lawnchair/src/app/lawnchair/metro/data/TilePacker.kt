package app.lawnchair.metro.data

/**
 * The Windows Phone placement rule, shared by the Start grid and auto layout.
 *
 * Tiles are placed in order, each at the first free spot scanning top-to-bottom,
 * left-to-right. Tiles two or more cells wide start on even columns, and tiles two or more
 * cells tall on even rows, so medium tiles line up in 2×2 blocks.
 */
class TilePacker(private val columns: Int) {

    private val occupied = ArrayList<BooleanArray>()

    /** Rows touched so far. */
    var usedRows = 0
        private set

    private fun rowAt(r: Int): BooleanArray {
        while (occupied.size <= r) occupied += BooleanArray(columns)
        return occupied[r]
    }

    private fun fits(col: Int, row: Int, span: Int, rowSpan: Int): Boolean {
        if (col + span > columns) return false
        for (r in row until row + rowSpan) {
            val line = rowAt(r)
            for (c in col until col + span) if (line[c]) return false
        }
        return true
    }

    /** Where [size] would go next, as (col, row), without placing it. */
    fun peek(size: TileSize): Pair<Int, Int> = find(size)

    /** Places [size] at the first free spot and returns (col, row). */
    fun place(size: TileSize): Pair<Int, Int> {
        val (col, row) = find(size)
        val span = size.span.coerceAtMost(columns)
        for (r in row until row + size.rowSpan) {
            val line = rowAt(r)
            for (c in col until col + span) line[c] = true
        }
        usedRows = maxOf(usedRows, row + size.rowSpan)
        return col to row
    }

    /** Free cells in rows [0, rows). */
    fun freeCells(rows: Int): Int {
        var free = 0
        for (r in 0 until rows) free += rowAt(r).count { !it }
        return free
    }

    private fun find(size: TileSize): Pair<Int, Int> {
        val span = size.span.coerceAtMost(columns)
        val rowSpan = size.rowSpan
        val aligned = span >= 2
        val alignRows = aligned && rowSpan >= 2
        var row = 0
        while (true) {
            if (alignRows && row % 2 != 0) {
                row++
                continue
            }
            var col = 0
            while (col + span <= columns) {
                if (fits(col, row, span, rowSpan)) return col to row
                col += if (aligned) 2 else 1
            }
            row++
        }
    }

    fun copy(): TilePacker = TilePacker(columns).also { other ->
        occupied.forEach { other.occupied += it.copyOf() }
        other.usedRows = usedRows
    }
}
