package app.lawnchair.metro.start

import android.annotation.SuppressLint
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
import app.lawnchair.metro.theme.MetroTheme
import app.lawnchair.metro.theme.PaneFonts
import app.lawnchair.metro.widgets.MetroWidgets
import app.lawnchair.metro.widgets.PaneWidgetHostView
import kotlin.math.hypot

/**
 * An app's widget shown as its tile. The tile keeps its size, place and colour; the widget draws
 * its content on the tile's colour, in colours made from it. Buttons and lists in the widget
 * work as usual; tapping anywhere else opens the app. Long-press anywhere to pick it up, like
 * any other tile. A widget tile of its own shows no notifications (an app tile showing its
 * widget does: see TileView.widgetView).
 */
@SuppressLint("ViewConstructor")
class WidgetTileView(
    context: Context,
    override var tile: MetroTile,
    private val hostView: PaneWidgetHostView?,
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

    /** 8.1 window mode: see-through unless the tile has a colour of its own. */
    var windowMode = false
        set(value) {
            field = value
            refreshColors()
        }

    /** Unread notifications for the widget's app. */
    var count = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The app the widget belongs to, so its tile has the same colour the app's tile had. */
    private val app = MetroWidgets.appFor(context, tile.component)
    private val colorKey = app?.flattenToShortString() ?: tile.key

    private val fillPaint = Paint()
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = PaneFonts.semibold
        textAlign = Paint.Align.RIGHT
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
        clipToPadding = true
        if (hostView != null) {
            addView(hostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        contentDescription = hostView?.appWidgetInfo?.loadLabel(context.packageManager) ?: "Widget"
        hostView?.onContentChanged = { invalidate() }
        refreshColors()
    }

    private val choice: Int get() = tile.colorFor(windowMode)

    /** True when the tile shows the background through it rather than a colour. */
    private val isWindow: Boolean
        get() = windowMode && choice.let { it == 0 || it == MetroTile.COLOR_BRAND || it == MetroTile.COLOR_WINDOW }

    private val keepsOwnLook: Boolean get() = choice == MetroTile.COLOR_WIDGET

    private fun fillColor(): Int = MetroTheme.tileColor(context, colorKey, if (keepsOwnLook) 0 else choice)

    /** Repaints the tile and recolours the widget, e.g. after the tile colour or palette changed. */
    fun refreshColors() {
        hostView?.ownBackground = keepsOwnLook
        val seed = if (isWindow) MetroTheme.accent(context) else fillColor()
        if (!keepsOwnLook) {
            MetroWidgets.paletteFor(context, seed)?.let { colors ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) hostView?.setColorResources(colors)
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            hostView?.resetColorResources()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hostView?.let { MetroWidgets.updateSize(it, w, h) }
    }

    override fun onDraw(canvas: Canvas) {
        if (!isWindow && !keepsOwnLook) {
            fillPaint.color = fillColor()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        }
    }

    /**
     * Every touch is followed here, not in onInterceptTouchEvent: that stops being called once
     * something in the widget asks its parents to keep out (a list scrolling, a button pressed),
     * and the long press must still be cancelled by a move, and, once it has picked the tile
     * up, get the rest of the gesture (the drag, or the menu on release).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stolen = false
                removeCallbacks(longPress)
                downX = ev.x
                downY = ev.y
                downRawX = ev.rawX
                downRawY = ev.rawY
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                // A list inside the widget (notes, mail) scrolls itself rather than Start.
                if (scrollableAt(this, ev.x, ev.y)) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (hypot(ev.rawX - downRawX, ev.rawY - downRawY) >
                ViewConfiguration.get(context).scaledTouchSlop
            ) {
                removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        if (stolen && ev.actionMasked != MotionEvent.ACTION_DOWN) return onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = stolen

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        dragHandler?.let { return it(event) }
        // Touches the widget didn't take (outside its buttons and lists) land here: a tap opens
        // the app, and a hold still picks the tile up.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!stolen) {
                    downX = event.x
                    downY = event.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.rawX - downRawX, event.rawY - downRawY) > ViewConfiguration.get(context).scaledTouchSlop) {
                    removeCallbacks(longPress)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val wasStolen = stolen
                stolen = false
                if (!wasStolen && hypot(event.rawX - downRawX, event.rawY - downRawY) <= ViewConfiguration.get(context).scaledTouchSlop) {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                stolen = false
                return true
            }
        }
        return true
    }

    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = PaneFonts.regular
        textAlign = Paint.Align.CENTER
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (hostView == null || !hostView.hasContent) {
            // Nothing from the widget yet (or it couldn't be shown): say what it is, quietly.
            notePaint.textSize = sp(14f)
            notePaint.color = if (isWindow || keepsOwnLook) 0xCCFFFFFF.toInt() else MetroTheme.onTileColor(fillColor())
            val name = contentDescription?.toString()?.lowercase() ?: "widget"
            val text = android.text.TextUtils.ellipsize(name, android.text.TextPaint(notePaint), width - dp(24f), android.text.TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, width / 2f, height / 2f - (notePaint.ascent() + notePaint.descent()) / 2f, notePaint)
        }
        if (count > 0 && !lifted) {
            // The unread count, top right, like the number on an app tile.
            val text = if (count > 99) "99+" else count.toString()
            countPaint.textSize = sp(15f)
            countPaint.color = if (isWindow || keepsOwnLook) Color.WHITE else MetroTheme.onTileColor(fillColor())
            countPaint.setShadowLayer(dp(2f), 0f, 0f, 0x66000000)
            canvas.drawText(text, width - dp(8f), dp(8f) - countPaint.ascent(), countPaint)
        }
        if (lifted) {
            val b = liftBorder
            framePaint.strokeWidth = b
            canvas.drawRect(b / 2f, b / 2f, width - b / 2f, height - b / 2f, framePaint)
        }
    }

    /** Whether a view under ([x], [y]) inside [group] can scroll up or down. */
    private fun scrollableAt(group: android.view.ViewGroup, x: Float, y: Float): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val c = group.getChildAt(i)
            if (c.visibility != VISIBLE) continue
            val cx = x + group.scrollX - c.left
            val cy = y + group.scrollY - c.top
            if (cx < 0 || cy < 0 || cx >= c.width || cy >= c.height) continue
            if (c.canScrollVertically(1) || c.canScrollVertically(-1)) return true
            if (c is android.view.ViewGroup && scrollableAt(c, cx, cy)) return true
        }
        return false
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
