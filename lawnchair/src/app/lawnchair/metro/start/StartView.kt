package app.lawnchair.metro.start

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Rect
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
import android.widget.TextView
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import app.lawnchair.metro.data.MetroTile
import app.lawnchair.metro.data.MetroTileStore
import app.lawnchair.metro.data.TileSize
import app.lawnchair.metro.live.LiveInfo
import app.lawnchair.metro.live.LiveTileData
import app.lawnchair.metro.theme.MetroTheme
import app.lawnchair.preferences.PreferenceManager
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

    // Android's stretch overscroll is replaced by the Windows Phone edge bounce, which moves
    // the tiles and the window-mode mask together.
    private val scroller = BounceScrollView(launcher).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        clipToPadding = false
    }
    private val prefs = PreferenceManager.getInstance(launcher)
    private var parallax: ParallaxBackgroundView? = null
    private val grid = TileGridView(launcher)
    private val arrow = TextView(launcher)

    private val background = MetroTheme.background(launcher)
    private val windowMode = background == MetroTheme.BG_WINDOW

    private var colorListener: MetroTheme.Listener? = null
    private val storeListener = Runnable { post { reload() } }
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val swipeDetector = GestureDetector(launcher, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            // Right-to-left swipe: open the app list.
            if (dx < -dp(60f) && abs(dx) > abs(dy) * 1.3f && vx < -dp(250f)) {
                openAppList()
                return true
            }
            return false
        }
    })

    init {
        id = View.generateViewId()
        when (background) {
            MetroTheme.BG_BLACK -> setBackgroundColor(Color.BLACK)
            else -> setBackgroundColor(Color.TRANSPARENT)
        }

        grid.columns = MetroTheme.columns(launcher)
        grid.windowMode = windowMode
        grid.isLongClickable = true
        grid.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            launcher.showDefaultOptions(lastTouchX, lastTouchY)
            true
        }
        // WP 8.1 parallax: our own background photo, drifting slower than the tiles.
        if (background != MetroTheme.BG_BLACK && prefs.metroBackgroundPhoto.get() > 0) {
            val bg = ParallaxBackgroundView(launcher)
            bg.load()
            if (bg.hasImage) {
                addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                parallax = bg
                scroller.onScrollFraction = { bg.setScrollFraction(it) }
            }
        }
        scroller.addView(grid, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // The app-list arrow scrolls with the tiles and only shows at the end of Start.
        setupArrow()
        grid.footer = arrow

        // Pulling down past the top opens the notification shade.
        scroller.onPullPastTop = { openNotifications() }

        reload()
    }

    /** Status bar and gesture bar insets, delivered by Launcher's DragLayer. */
    override fun setInsets(insets: Rect) {
        grid.topPadding = insets.top
        grid.bottomInset = insets.bottom
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
        LiveTileData.addListener(launcher, liveListener)
        removeCallbacks(liveTicker)
        postDelayed(liveTicker, LIVE_TICK_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(liveTicker)
        LiveTileData.removeListener(liveListener)
        store.removeListener(storeListener)
        colorListener?.close()
        colorListener = null
        super.onDetachedFromWindow()
    }

    /** Re-reads tiles from storage and rebuilds the grid. */
    fun reload() {
        tiles = store.load()
        // Remove tiles only; the grid also holds the app-list arrow.
        (0 until grid.childCount).map { grid.getChildAt(it) }.filterIsInstance<TileView>().forEach(grid::removeView)
        tiles.forEach { grid.addView(createTileView(it), grid.childCount - if (grid.footer != null) 1 else 0) }
        applyLive(LiveTileData.snapshot)
        invalidate()
    }

    // ---- Live tiles -----------------------------------------------------------------------

    private val liveListener: (Map<String, LiveInfo>) -> Unit = { applyLive(it) }

    private fun applyLive(data: Map<String, LiveInfo>) {
        for (i in 0 until grid.childCount) {
            val tv = grid.getChildAt(i) as? TileView ?: continue
            tv.live = data[tv.tile.component.packageName]
        }
    }

    private val visibleRect = Rect()
    private val random = java.util.Random()

    /**
     * Flip scheduler. Every couple of seconds, at most one on-screen tile flips: one that has
     * shown its content long enough goes back to its icon, otherwise a random live tile that
     * hasn't flipped for a while turns over. Tiles never flip in sync, as on Windows Phone, and
     * nothing animates while Start is hidden.
     */
    private val liveTicker = object : Runnable {
        override fun run() {
            postDelayed(this, LIVE_TICK_MS + random.nextInt(900))
            if (!prefs.metroLiveTiles.get() || !isShown || !hasWindowFocus()) return
            val now = System.currentTimeMillis()
            val onScreen = (0 until grid.childCount)
                .mapNotNull { grid.getChildAt(it) as? TileView }
                .filter { it.getLocalVisibleRect(visibleRect) && visibleRect.height() > it.height / 2 }

            onScreen.filter { it.showingBack && now - it.lastFlipAt > it.backDwellMs }
                .randomOrNull()?.let {
                    it.flip()
                    return
                }
            onScreen.filter { !it.showingBack && it.hasBackFace && now - it.lastFlipAt > 4000 }
                .randomOrNull()?.let {
                    it.backDwellMs = 5000L + random.nextInt(4000)
                    it.flip()
                }
        }
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
            sizes.add(GROUP_SIZE, i, i, size.label).setCheckable(true).isChecked = size == tile.size
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

    private fun openAppList() = launcher.openMetroAppList()

    // Watched here rather than in onInterceptTouchEvent: once the tiles start scrolling, the
    // scroller blocks interception, which would hide a sideways swipe from us.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchX = ev.x
            lastTouchY = ev.y
        }
        if (swipeDetector.onTouchEvent(ev)) {
            // Swipe handled: cancel whatever the tiles were doing with this gesture.
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Expands the notification shade (Android lets launchers do this; no extra permission prompt). */
    @SuppressLint("WrongConstant")
    private fun openNotifications() {
        runCatching {
            val sbm = launcher.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        private const val MENU_RESIZE = 100
        private const val MENU_COLOR = 101
        private const val MENU_UNPIN = 102
        private const val MENU_INFO = 103
        private const val GROUP_SIZE = 1
        private const val GROUP_COLOR = 2
        private const val LIVE_TICK_MS = 2200L
    }
}
