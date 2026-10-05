package app.lawnchair.metro.start

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import app.lawnchair.metro.data.MetroTile
import app.lawnchair.metro.widgets.MetroWidgets
import kotlin.math.hypot

/**
 * An Android widget sitting on Start as a tile. It takes the tile's size and position; the widget
 * draws its own content. Long-press anywhere on it to pick it up, like any other tile.
 */
@SuppressLint("ViewConstructor")
class WidgetTileView(
    context: Context,
    override var tile: MetroTile,
    private val hostView: AppWidgetHostView?,
    private val onLongPress: (WidgetTileView) -> Unit,
) : FrameLayout(context), TileHolder {

    override var lifted = false
        set(value) {
            field = value
            invalidate()
        }
    override val liftBorder: Float get() = dp(4f)
    override var downX = 0f
        private set
    override var downY = 0f
        private set
    override var downRawX = 0f
        private set
    override var downRawY = 0f
        private set
    override var dragHandler: ((MotionEvent) -> Boolean)? = null

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private var stolen = false
    private val longPress = Runnable {
        stolen = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Cancel whatever the widget was doing with this touch; we take it from here.
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        hostView?.dispatchTouchEvent(cancel)
        cancel.recycle()
        onLongPress(this)
    }

    init {
        setWillNotDraw(false)
        clipChildren = true
        if (hostView != null) {
            addView(hostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        contentDescription = hostView?.appWidgetInfo?.loadLabel(context.packageManager) ?: "Widget"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hostView?.let { MetroWidgets.updateSize(it, w, h) }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stolen = false
                downX = ev.x
                downY = ev.y
                downRawX = ev.rawX
                downRawY = ev.rawY
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (hypot(ev.rawX - downRawX, ev.rawY - downRawY) >
                ViewConfiguration.get(context).scaledTouchSlop
            ) {
                removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return stolen
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        dragHandler?.let { return it(event) }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            stolen = false
        }
        return stolen || super.onTouchEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (lifted) {
            val b = liftBorder
            framePaint.strokeWidth = b
            canvas.drawRect(b / 2f, b / 2f, width - b / 2f, height - b / 2f, framePaint)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
