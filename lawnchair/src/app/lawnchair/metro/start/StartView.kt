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
import app.lawnchair.metro.auto.AutoLayout
import app.lawnchair.metro.data.MetroUsage
import app.lawnchair.metro.live.LiveInfo
import app.lawnchair.metro.motion.Turnstile
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
    private val arrow = ArrowButton(launcher, onTap = { openAppList() }, onHoldComplete = { runAutoLayout() })
    /** Solid black behind the status bar: tiles never scroll underneath it. */
    private val statusStrip = View(launcher).apply { setBackgroundColor(Color.BLACK) }

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

        addView(statusStrip, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))

        // The app-list arrow scrolls with the tiles and only shows at the end of Start.
        grid.footerSize = dp(46f).toInt()
        grid.footer = arrow

        // Pulling down past the top opens the notification shade.
        scroller.onPullPastTop = { openNotifications() }

        reload()
    }

    /** Status bar and gesture bar insets, delivered by Launcher's DragLayer. */
    override fun setInsets(insets: Rect) {
        (statusStrip.layoutParams as LayoutParams).height = insets.top
        statusStrip.requestLayout()
        grid.topPadding = insets.top
        grid.bottomInset = insets.bottom
        grid.requestLayout()
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
    fun reload() = reload(animate = false)

    /**
     * Rebuilds the grid. With [animate], tiles glide from where they were to their new places
     * and newly pinned tiles grow in (used after auto layout and undo).
     */
    fun reload(animate: Boolean) {
        if (drag != null) return
        val oldSpots = if (animate) {
            grid.tiles.associate { it.tile.component to (it.left to it.top) }
        } else {
            emptyMap()
        }
        tiles = store.load()
        // Remove tiles only; the grid also holds the app-list arrow.
        (0 until grid.childCount).map { grid.getChildAt(it) }.filterIsInstance<TileView>().forEach(grid::removeView)
        val views = tiles.map { createTileView(it) }
        views.forEach { grid.addView(it, grid.childCount - if (grid.footer != null) 1 else 0) }
        grid.setOrder(views)
        if (animate) {
            grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    grid.viewTreeObserver.removeOnPreDrawListener(this)
                    views.forEachIndexed { i, v ->
                        val old = oldSpots[v.tile.component]
                        if (old != null) {
                            v.translationX = (old.first - v.left).toFloat()
                            v.translationY = (old.second - v.top).toFloat()
                            v.animate().translationX(0f).translationY(0f).setStartDelay(i * 12L).setDuration(320)
                                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                                .setUpdateListener { grid.invalidate() }.start()
                        } else {
                            v.scaleX = 0.6f
                            v.scaleY = 0.6f
                            v.alpha = 0f
                            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120 + i * 12L).setDuration(260)
                                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                                .setUpdateListener { grid.invalidate() }.start()
                        }
                    }
                    return true
                }
            })
        }
        // Live data arrives via the listener once attached (fields below aren't ready during init).
        if (isAttachedToWindow) applyLive(LiveTileData.snapshot)
        invalidate()
    }

    // ---- Live tiles -----------------------------------------------------------------------

    private val liveListener: (Map<String, LiveInfo>) -> Unit = { applyLive(it) }

    /** Newest notification time seen per app, to spot new arrivals. */
    private val seenTimes = HashMap<String, Long>()
    private var livePrimed = false

    /**
     * Applies new live data. A tile flips the moment a new notification arrives for its app,
     * rather than waiting for the scheduler, so tiles react in a lively, unsynchronised way.
     * Ongoing content (music, downloads) turns its tile over and keeps it there.
     */
    private fun applyLive(data: Map<String, LiveInfo>) {
        val visible = Rect()
        for (i in 0 until grid.childCount) {
            val tv = grid.getChildAt(i) as? TileView ?: continue
            val pkg = tv.tile.component.packageName
            val info = data[pkg]
            tv.live = info
            if (info == null) continue
            val onScreen = isShown && tv.getLocalVisibleRect(visible)
            val isNew = livePrimed && info.latestTime > (seenTimes[pkg] ?: 0L)
            when {
                tv.isPinned && !tv.showingBack -> if (onScreen && livePrimed) tv.flip() else tv.showBackNow()
                isNew && tv.hasBackFace && onScreen -> {
                    tv.backDwellMs = 6000L + random.nextInt(3000)
                    if (tv.showingBack) tv.flipRefresh() else tv.flip()
                }
            }
        }
        data.forEach { (pkg, info) -> if (info.latestTime > 0) seenTimes[pkg] = info.latestTime }
        livePrimed = true
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

            onScreen.filter { it.isPinned && !it.showingBack }.randomOrNull()?.let {
                it.flip()
                return
            }
            onScreen.filter { it.showingBack && !it.isPinned && now - it.lastFlipAt > it.backDwellMs }
                .randomOrNull()?.let {
                    it.flip()
                    return
                }
            onScreen.filter { !it.showingBack && !it.isPinned && it.hasBackFace && now - it.lastFlipAt > 4000 }
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

    private var panProgress = 0f

    /**
     * Called while the app list slides in (0 = Start, 1 = app list). Start and the list are one
     * wide surface: the tiles slide out exactly as the list slides in, over a background that
     * moves more slowly (photo) or not at all (system wallpaper), which gives the parallax.
     */
    fun setPanProgress(p: Float) {
        panProgress = p
        scroller.translationX = -width * p
        parallax?.setPanFraction(p)
        if (parallax == null) {
            runCatching {
                android.app.WallpaperManager.getInstance(launcher)
                    .setWallpaperOffsets(windowToken, 0.5f * p, 0.5f)
            }
        }
    }

    // ---- Turnstile ------------------------------------------------------------------------

    /** Tiles currently on screen, in grid order. */
    private fun visibleTiles(): List<TileView> = (0 until grid.childCount)
        .mapNotNull { grid.getChildAt(it) as? TileView }
        .filter { it.getLocalVisibleRect(visibleRect) }

    private fun allTiles(): List<TileView> = (0 until grid.childCount).mapNotNull { grid.getChildAt(it) as? TileView }

    /** True between launching an app and coming back, so the return plays the turnstile. */
    private var turnedAway = false

    /** Called when Start becomes visible again after an app: tiles swing back in. */
    fun playReturn() {
        if (!turnedAway && panProgress > 0f) return
        turnedAway = false
        val all = allTiles()
        Turnstile.reset(all)
        all.forEach { it.scaleX = 1f; it.scaleY = 1f }
        Turnstile.into(visibleTiles()) { grid.invalidate() }
    }

    private fun launchWithTurnstile(view: TileView) {
        turnedAway = true
        Turnstile.out(visibleTiles(), view, onFrame = { grid.invalidate() }) {
            startApp(view)
            // If we're still in front (launch failed or slow), bring the tiles back.
            postDelayed({ if (hasWindowFocus()) playReturn() }, 1200)
        }
    }

    private fun createTileView(tile: MetroTile) = TileView(launcher, tile).apply {
        windowMode = this@StartView.windowMode
        setOnClickListener { startApp(this) }
        // Long-press picks the tile up: drag to move it, or let go in place for its menu.
        setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            beginDrag(this)
            true
        }
    }

    private fun startApp(view: TileView) {
        val launcherApps = launcher.getSystemService(LauncherApps::class.java) ?: return
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        val options = ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.width, view.height).toBundle()
        runCatching {
            launcherApps.startMainActivity(view.tile.component, Process.myUserHandle(), bounds, options)
            MetroUsage.recordLaunch(launcher, view.tile.component.packageName)
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
        menu.menu.add(Menu.NONE, MENU_LOCK, 2, if (tile.locked) "Unlock tile" else "Lock tile (auto layout keeps it)")
        menu.menu.add(Menu.NONE, MENU_UNPIN, 3, "Unpin from Start")
        menu.menu.add(Menu.NONE, MENU_INFO, 4, "App info")

        menu.setOnMenuItemClickListener { item ->
            when {
                item.groupId == GROUP_SIZE -> {
                    tile.size = TileSize.entries[item.itemId]
                    MetroUsage.recordSize(launcher, tile.component.packageName, tile.size)
                    commit(view)
                }
                item.itemId == MENU_LOCK -> {
                    tile.locked = !tile.locked
                    store.save(tiles)
                }
                item.groupId == GROUP_COLOR -> {
                    tile.color = if (item.itemId == 0) 0 else MetroTheme.CLASSIC_ACCENTS.values.elementAt(item.itemId - 1)
                    commit(view)
                }
                item.itemId == MENU_UNPIN -> {
                    tiles.removeAll { it.id == tile.id }
                    store.save(tiles)
                    MetroUsage.recordUnpinned(launcher, tile.component.packageName)
                    grid.setOrder(grid.tiles.filter { it !== view })
                    grid.removeView(view)
                    grid.animateReflow()
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

    // ---- Drag to rearrange --------------------------------------------------------------

    private class Drag(val view: TileView, val grabX: Float, val grabY: Float, val downRawX: Float, val downRawY: Float) {
        var moved = false
        var rawX = downRawX
        var rawY = downRawY
        var lastTarget: TileView? = null
    }

    private var drag: Drag? = null
    private val touchSlop = android.view.ViewConfiguration.get(launcher).scaledTouchSlop
    private val gridLoc = IntArray(2)
    private val scrollerLoc = IntArray(2)

    /** Picks [view] up: it lifts slightly and follows the finger until released. */
    private fun beginDrag(view: TileView) {
        if (drag != null) return
        val d = Drag(view, view.downX, view.downY, view.downRawX, view.downRawY)
        drag = d
        view.parent?.requestDisallowInterceptTouchEvent(true)
        grid.draggedView = view
        view.translationZ = dp(8f)
        view.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.92f).setDuration(140)
            .setUpdateListener { grid.invalidate() }.start()
        view.dragHandler = { ev -> onDragEvent(d, ev) }
    }

    private fun onDragEvent(d: Drag, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                d.rawX = ev.rawX
                d.rawY = ev.rawY
                if (!d.moved && Math.hypot((ev.rawX - d.downRawX).toDouble(), (ev.rawY - d.downRawY).toDouble()) > touchSlop * 2) {
                    d.moved = true
                }
                if (d.moved) {
                    followFinger(d)
                    reorderUnderFinger(d)
                    autoScroll(d)
                }
            }
            MotionEvent.ACTION_UP -> endDrag(d, showMenu = !d.moved)
            MotionEvent.ACTION_CANCEL -> endDrag(d, showMenu = false)
        }
        return true
    }

    /** Finger position in grid coordinates. */
    private fun fingerInGrid(d: Drag): Pair<Float, Float> {
        grid.getLocationOnScreen(gridLoc)
        return (d.rawX - gridLoc[0]) to (d.rawY - gridLoc[1])
    }

    private fun followFinger(d: Drag) {
        val (gx, gy) = fingerInGrid(d)
        d.view.translationX = gx - d.grabX - d.view.left
        d.view.translationY = gy - d.grabY - d.view.top
        grid.invalidate()
    }

    /** Moves the dragged tile in the order to where the finger is; the rest reflow around it. */
    private fun reorderUnderFinger(d: Drag) {
        val (gx, gy) = fingerInGrid(d)
        val others = grid.tiles
        val target = others.firstOrNull { t ->
            if (t === d.view) return@firstOrNull false
            // Only the inner part of a tile counts, so tiles don't swap back and forth on edges.
            val ix = t.width * 0.18f
            val iy = t.height * 0.18f
            gx > t.left + ix && gx < t.right - ix && gy > t.top + iy && gy < t.bottom - iy
        }
        val lastBottom = others.filter { it !== d.view }.maxOfOrNull { it.bottom } ?: 0
        val newOrder: List<TileView>? = when {
            target != null && target !== d.lastTarget -> {
                val list = others.toMutableList()
                list.remove(d.view)
                val at = list.indexOf(target).let { if (others.indexOf(d.view) < others.indexOf(target)) it + 1 else it }
                list.add(at.coerceIn(0, list.size), d.view)
                list
            }
            target == null && gy > lastBottom && others.last() !== d.view -> {
                others.filter { it !== d.view } + d.view
            }
            else -> null
        }
        d.lastTarget = target
        if (newOrder != null && newOrder != others) {
            grid.setOrder(newOrder)
            tiles = newOrder.map { it.tile }.toMutableList()
            grid.animateReflow()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            // The dragged tile's slot moved; keep it under the finger after the relayout.
            grid.post { if (drag === d) followFinger(d) }
        }
    }

    private var scrolling = false

    /** Scrolls Start while the dragged tile is held near the top or bottom edge. */
    private fun autoScroll(d: Drag) {
        if (scrolling) return
        scroller.getLocationOnScreen(scrollerLoc)
        val edge = dp(72f)
        val top = scrollerLoc[1] + grid.topPadding
        val bottom = scrollerLoc[1] + scroller.height
        val speed = when {
            d.rawY < top + edge -> -dp(14f) * (1f - ((d.rawY - top) / edge).coerceIn(0f, 1f)).coerceAtLeast(0.3f)
            d.rawY > bottom - edge -> dp(14f) * (1f - ((bottom - d.rawY) / edge).coerceIn(0f, 1f)).coerceAtLeast(0.3f)
            else -> 0f
        }
        if (speed == 0f) return
        scrolling = true
        scroller.scrollBy(0, speed.toInt())
        followFinger(d)
        reorderUnderFinger(d)
        scroller.postOnAnimation {
            scrolling = false
            if (drag === d) autoScroll(d)
        }
    }

    private fun endDrag(d: Drag, showMenu: Boolean) {
        drag = null
        val v = d.view
        v.dragHandler = null
        grid.draggedView = null
        v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .setUpdateListener { grid.invalidate() }
            .withEndAction { v.translationZ = 0f; grid.invalidate() }
            .start()
        if (d.moved) {
            tiles = grid.tiles.map { it.tile }.toMutableList()
            store.save(tiles)
            MetroUsage.recordMoved(launcher, v.tile.component.packageName, regionOf(v))
        }
        if (showMenu) showTileMenu(v)
    }

    /** Which part of Start a tile sits in: top or bottom of the first screen, or below it. */
    private fun regionOf(v: TileView): MetroUsage.Region {
        val rows = grid.rowsInViewport(scroller.height)
        val row = (v.top - grid.topPadding) / grid.rowPitch.coerceAtLeast(1)
        return when {
            row >= rows -> MetroUsage.Region.BELOW
            row >= rows * 0.6 -> MetroUsage.Region.BOTTOM
            else -> MetroUsage.Region.TOP
        }
    }

    // ---- Auto layout --------------------------------------------------------------------

    private val undoBar = UndoBar(launcher)

    /** Rearranges Start from usage (hold the arrow). Undo restores the previous layout. */
    private fun runAutoLayout() {
        val before = tiles.map { it.copy() }
        val columns = grid.columns
        val rows = grid.rowsInViewport(scroller.height)
        val ctx = launcher.applicationContext
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            val result = runCatching { AutoLayout.compute(ctx, before, columns, rows) }.getOrNull()
            post {
                if (result == null) {
                    Toast.makeText(launcher, "Couldn't arrange Start", Toast.LENGTH_SHORT).show()
                    return@post
                }
                scroller.smoothScrollTo(0, 0)
                store.save(result.tiles)
                reload(animate = true)
                val parts = buildList {
                    if (result.added > 0) add("${result.added} added")
                    if (result.removed > 0) add("${result.removed} removed")
                }
                val msg = if (parts.isEmpty()) "Start arranged" else "Start arranged · " + parts.joinToString(", ")
                undoBar.show(
                    this,
                    message = msg,
                    hint = if (MetroUsage.hasUsageAccess(launcher)) null else "Allow usage access for better results",
                    onUndo = {
                        store.save(before)
                        reload(animate = true)
                    },
                    onHint = {
                        launcher.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
        }
    }

    // Watched here rather than in onInterceptTouchEvent: once the tiles start scrolling, the
    // scroller blocks interception, which would hide a sideways swipe from us.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchX = ev.x
            lastTouchY = ev.y
        }
        if (drag == null && swipeDetector.onTouchEvent(ev)) {
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
        private const val MENU_LOCK = 104
        private const val GROUP_SIZE = 1
        private const val GROUP_COLOR = 2
        private const val LIVE_TICK_MS = 2200L
    }
}
