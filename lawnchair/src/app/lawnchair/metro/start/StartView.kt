package app.lawnchair.metro.start

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import app.lawnchair.metro.data.MetroTile
import app.lawnchair.metro.data.MetroTileStore
import app.lawnchair.metro.data.TileSize
import app.lawnchair.metro.theme.MetroTheme
import com.android.launcher3.Insettable
import com.android.launcher3.LauncherState
import kotlin.math.abs

/**
 * The Metro Start screen: a vertically scrolling grid of tiles that replaces Launcher3's
 * workspace and dock.
 *
 * - Tap a tile to open the app; long-press for resize, colour, unpin and app info.
 * - Swipe left or tap the arrow to open the app list (Lawnchair's drawer for now).
 * - Long-press empty space for wallpaper, widgets and settings.
 */
@SuppressLint("ViewConstructor")
class StartView(private val launcher: LawnchairLauncher) : FrameLayout(launcher), Insettable {

    private val store = MetroTileStore.get(launcher)
    private var tiles: MutableList<MetroTile> = mutableListOf()

    private val scroller = ScrollView(launcher).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_ALWAYS
        isFillViewport = true
        clipToPadding = false
    }
    private val grid = TileGridView(launcher)
    private val arrow = TextView(launcher)

    private val background = MetroTheme.background(launcher)
    private val windowMode = background == MetroTheme.BG_WINDOW
    private val holePath = Path()
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val tmpRect = RectF()

    private var colorListener: MetroTheme.Listener? = null
    private val storeListener = Runnable { post { reload() } }
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val swipeDetector = GestureDetector(launcher, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (dx < -dp(80f) && abs(dx) > abs(dy) * 1.5f && abs(vx) > dp(300f)) {
                openAppList()
                return true
            }
            return false
        }
    })

    init {
        id = View.generateViewId()
        setWillNotDraw(!windowMode)
        when (background) {
            MetroTheme.BG_BLACK -> setBackgroundColor(Color.BLACK)
            else -> setBackgroundColor(Color.TRANSPARENT)
        }

        grid.columns = MetroTheme.columns(launcher)
        grid.isLongClickable = true
        grid.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            launcher.showDefaultOptions(lastTouchX, lastTouchY)
            true
        }
        scroller.addView(grid, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> if (windowMode) invalidate() }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setupArrow()
        addView(arrow, LayoutParams(dp(44f).toInt(), dp(44f).toInt(), Gravity.BOTTOM or Gravity.END))

        reload()
    }

    /** Status bar and gesture bar insets, delivered by Launcher's DragLayer. */
    override fun setInsets(insets: Rect) {
        grid.topPadding = insets.top + dp(24f).toInt()
        grid.bottomPadding = insets.bottom + dp(96f).toInt()
        (arrow.layoutParams as LayoutParams).apply {
            rightMargin = dp(16f).toInt()
            bottomMargin = insets.bottom + dp(20f).toInt()
        }
        arrow.requestLayout()
        grid.requestLayout()
    }

    private fun setupArrow() {
        arrow.text = "→" // →
        arrow.gravity = Gravity.CENTER
        arrow.setTextColor(Color.WHITE)
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        arrow.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        arrow.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2f).toInt(), Color.WHITE)
            setColor(Color.TRANSPARENT)
        }
        arrow.contentDescription = "All apps"
        arrow.setOnClickListener { openAppList() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        store.addListener(storeListener)
        colorListener = MetroTheme.Listener(launcher) { refreshColors() }
    }

    override fun onDetachedFromWindow() {
        store.removeListener(storeListener)
        colorListener?.close()
        colorListener = null
        super.onDetachedFromWindow()
    }

    /** Re-reads tiles from storage and rebuilds the grid. */
    fun reload() {
        tiles = store.load()
        grid.removeAllViews()
        tiles.forEach { grid.addView(createTileView(it)) }
        invalidate()
    }

    /** Repaints every tile, e.g. after the Monet palette changed. */
    fun refreshColors() {
        for (i in 0 until grid.childCount) (grid.getChildAt(i) as? TileView)?.refreshColors()
        arrow.invalidate()
        invalidate()
    }

    fun scrollToTop() = scroller.smoothScrollTo(0, 0)

    private fun createTileView(tile: MetroTile) = TileView(launcher, tile).apply {
        windowMode = this@StartView.windowMode
        setOnClickListener { launch(this) }
        setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showTileMenu(this)
            true
        }
    }

    private fun launch(view: TileView) {
        val launcherApps = launcher.getSystemService(LauncherApps::class.java) ?: return
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        val options = ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.width, view.height).toBundle()
        runCatching {
            launcherApps.startMainActivity(view.tile.component, Process.myUserHandle(), bounds, options)
        }.onFailure {
            Toast.makeText(launcher, "Couldn't open ${view.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTileMenu(view: TileView) {
        val tile = view.tile
        val menu = PopupMenu(launcher, view, Gravity.END)
        val sizes = menu.menu.addSubMenu(Menu.NONE, MENU_RESIZE, 0, "Resize")
        TileSize.entries.forEachIndexed { i, size ->
            sizes.add(GROUP_SIZE, i, i, size.name.lowercase().replaceFirstChar { it.uppercase() })
                .setCheckable(true).isChecked = size == tile.size
        }
        sizes.setGroupCheckable(GROUP_SIZE, true, true)

        val colors = menu.menu.addSubMenu(Menu.NONE, MENU_COLOR, 1, "Tile color")
        colors.add(GROUP_COLOR, 0, 0, "Theme (automatic)")
        MetroTheme.CLASSIC_ACCENTS.keys.forEachIndexed { i, name ->
            colors.add(GROUP_COLOR, i + 1, i + 1, name.replaceFirstChar { it.uppercase() })
        }
        menu.menu.add(Menu.NONE, MENU_UNPIN, 2, "Unpin from Start")
        menu.menu.add(Menu.NONE, MENU_INFO, 3, "App info")

        menu.setOnMenuItemClickListener { item ->
            when {
                item.groupId == GROUP_SIZE -> {
                    tile.size = TileSize.entries[item.itemId]
                    commit(view)
                }
                item.groupId == GROUP_COLOR -> {
                    tile.color = if (item.itemId == 0) 0 else MetroTheme.CLASSIC_ACCENTS.values.elementAt(item.itemId - 1)
                    commit(view)
                }
                item.itemId == MENU_UNPIN -> {
                    tiles.removeAll { it.id == tile.id }
                    store.save(tiles)
                    grid.removeView(view)
                    invalidate()
                }
                item.itemId == MENU_INFO -> {
                    launcher.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", tile.component.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.show()
    }

    private fun commit(view: TileView) {
        store.save(tiles)
        view.setTileData(view.tile)
        grid.requestLayout()
        invalidate()
    }

    private fun openAppList() {
        launcher.stateManager.goToState(LauncherState.ALL_APPS)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchX = ev.x
            lastTouchY = ev.y
        }
        swipeDetector.onTouchEvent(ev)
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * Window mode (Windows Phone 8.1): paint black everywhere except where tiles are, so the
     * fixed wallpaper only shows through the tiles and they slide across it as you scroll.
     */
    override fun onDraw(canvas: Canvas) {
        if (!windowMode) return
        holePath.reset()
        holePath.fillType = Path.FillType.EVEN_ODD
        holePath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        val dx = scroller.left + grid.left - scroller.scrollX
        val dy = scroller.top + grid.top - scroller.scrollY
        grid.forEachTileBounds { l, t, r, b ->
            tmpRect.set(l + dx, t + dy, r + dx, b + dy)
            if (tmpRect.bottom > 0 && tmpRect.top < height) holePath.addRect(tmpRect, Path.Direction.CW)
        }
        canvas.drawPath(holePath, blackPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        private const val MENU_RESIZE = 100
        private const val MENU_COLOR = 101
        private const val MENU_UNPIN = 102
        private const val MENU_INFO = 103
        private const val GROUP_SIZE = 1
        private const val GROUP_COLOR = 2
    }
}
