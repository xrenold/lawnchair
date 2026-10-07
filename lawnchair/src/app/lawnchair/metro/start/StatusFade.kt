package app.lawnchair.metro.start

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Behind the status bar: solid black where the clock and icons sit, fading to clear below
 * them, so tiles and app rows dissolve as they scroll up instead of being cut off by a hard
 * edge. Size it to about one and a half status bars ([heightFor]).
 */
class StatusFade(context: Context) : View(context) {

    private val paint = Paint()
    private var shaderH = -1

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height
        if (h <= 0) return
        if (shaderH != h) {
            shaderH = h
            // Solid for the top third (behind the status icons), then an eased fade to clear.
            paint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xE0000000.toInt(), 0x99000000.toInt(), 0x4D000000, 0x14000000, 0),
                floatArrayOf(0f, 0.36f, 0.5f, 0.64f, 0.78f, 0.9f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), h.toFloat(), paint)
    }

    companion object {
        /** Strip height for a status bar of [statusBar] px: the bar plus half again for the fade. */
        fun heightFor(statusBar: Int) = (statusBar * 1.5f).toInt()
    }
}
