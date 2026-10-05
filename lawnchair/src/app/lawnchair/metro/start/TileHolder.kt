package app.lawnchair.metro.start

import android.view.MotionEvent
import app.lawnchair.metro.data.MetroTile

/** What the Start grid needs from anything that sits on it: app tiles and widget tiles. */
interface TileHolder {
    val tile: MetroTile

    /** True while being dragged: framed in black, its window inset inside the frame. */
    var lifted: Boolean
    val liftBorder: Float

    /** Where the last touch went down, for picking the tile up under the finger. */
    val downX: Float
    val downY: Float
    val downRawX: Float
    val downRawY: Float

    /** Set by Start while dragging; it receives every touch event. */
    var dragHandler: ((MotionEvent) -> Boolean)?
}
