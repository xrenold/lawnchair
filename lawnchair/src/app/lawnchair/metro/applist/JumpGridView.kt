package app.lawnchair.metro.applist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import app.lawnchair.metro.theme.MetroTheme

/**
 * Windows Phone 8.1 jump grid: tapping a letter header shows every letter as a square; tap one
 * to jump there. Letters with no apps are greyed out.
 */
@SuppressLint("ViewConstructor")
class JumpGridView(context: Context, private val onPick: (Char?) -> Unit) : View(context) {

    var available: Set<Char> = emptySet()
    private val letters = AlphabetScrubber.LETTERS
    private val columns = 4
    private val gap = dp(8f)
    private val margin = dp(20f)
    var topInset = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val rect = RectF()

    init {
        setBackgroundColor(0xF2000000.toInt())
        isClickable = true
        visibility = GONE
    }

    fun show() {
        visibility = VISIBLE
        alpha = 0f
        scaleX = 1.08f
        scaleY = 1.08f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    fun hide() {
        animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(150).withEndAction { visibility = GONE }.start()
    }

    private fun cell(): Float = (width - margin * 2 - gap * (columns - 1)) / columns

    private fun cellRect(i: Int, out: RectF) {
        val c = cell()
        val col = i % columns
        val row = i / columns
        val left = margin + col * (c + gap)
        val top = topInset + margin + row * (c + gap)
        out.set(left, top, left + c, top + c)
    }

    override fun onDraw(canvas: Canvas) {
        val accent = MetroTheme.accent(context)
        text.textSize = cell() * 0.42f
        letters.forEachIndexed { i, ch ->
            cellRect(i, rect)
            val has = ch in available
            fill.color = if (has) accent else 0x33FFFFFF
            canvas.drawRect(rect, fill)
            text.color = if (has) Color.WHITE else 0x66FFFFFF
            canvas.drawText(ch.lowercaseChar().toString(), rect.left + dp(10f), rect.bottom - dp(10f) - text.descent(), text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val hit = letters.indices.firstOrNull { i ->
                cellRect(i, rect)
                rect.contains(event.x, event.y)
            }
            val ch = hit?.let { letters[it] }
            onPick(if (ch != null && ch in available) ch else null)
        }
        return true
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
