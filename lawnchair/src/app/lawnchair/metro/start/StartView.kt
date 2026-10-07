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
import app.lawnchair.metro.data.LayoutLock
import app.lawnchair.metro.data.TileSize
import app.lawnchair.metro.auto.AutoLayout
import app.lawnchair.metro.data.MetroShortcuts
import app.lawnchair.metro.data.MetroUsage
import app.lawnchair.metro.widgets.MetroWidgets
import app.lawnchair.metro.widgets.WidgetPicker
import app.lawnchair.metro.live.LiveInfo
import app.lawnchair.metro.motion.Turnstile
import app.lawnchair.metro.live.LiveTileData
import app.lawnchair.metro.info.InfoKind
import app.lawnchair.metro.info.InfoTiles
import app.lawnchair.metro.notify.NotificationPanel
import app.lawnchair.metro.notify.PanelSwipe
import app.lawnchair.metro.theme.BackgroundDim
import app.lawnchair.metro.theme.MetroTheme
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.Insettable
import com.android.launcher3.LauncherState
import kotlin.math.abs
import kotlin.math.hypot

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
    private val arrow = ArrowButton(
        launcher,
        onTap = { openAppList() },
        onHoldComplete = { onHoldDone() },
        onHoldProgress = { p, releasing -> showHold(p, releasing) },
        holdAllowed = { !LayoutLock.isLocked(launcher) },
        onHoldBlocked = { showLockedBar(null) },
    )
    /**
     * Legibility: an even black layer over the background (photo or wallpaper), stronger the
     * brighter the background. It sits behind the tiles and doesn't move with them, so it also
     * covers the app list when that slides in.
     */
    private val dimView = View(launcher).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
    /** Black behind the status bar, fading out below it: tiles dissolve as they scroll up. */
    private val statusStrip = StatusFade(launcher)

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
        InfoTiles.loadOverrides(launcher)
        when (background) {
            MetroTheme.BG_BLACK -> setBackgroundColor(Color.BLACK)
            else -> setBackgroundColor(Color.TRANSPARENT)
        }

        grid.columns = MetroTheme.columns(launcher)
        grid.windowMode = windowMode
        grid.isLongClickable = true
        grid.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showStartMenu()
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
        if (background != MetroTheme.BG_BLACK) {
            addView(dimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            updateDim()
        }
        scroller.addView(grid, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(statusStrip, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))

        // The app-list arrow scrolls with the tiles and only shows at the end of Start.
        grid.footerSize = dp(46f).toInt()
        grid.footer = arrow

        // Group gaps depend on how many rows fill the first screen.
        scroller.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> grid.viewportHeight = bottom - top }

        // Brand colours depend on where tiles sit; re-check after every layout change.
        grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleBrands() }

        // Scrolling to tiles that got news while out of view starts their dwell.
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> markVisibleNews() }

        // Pulling down past the top opens the notification shade.
        scroller.onPullPastTop = { openNotifications() }

        reload()
    }

    private val systemInsets = Rect()

    /** Status bar and gesture bar insets, delivered by Launcher's DragLayer. */
    override fun setInsets(insets: Rect) {
        systemInsets.set(insets)
        (statusStrip.layoutParams as LayoutParams).height = StatusFade.heightFor(insets.top)
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
        InfoTiles.addListener(infoListener)
        InfoTiles.start(launcher)
        // After a crash: offer to share the report (it never leaves the phone otherwise).
        if (app.lawnchair.metro.CrashLog.takeUnseen(launcher)) {
            postDelayed({
                val latest = app.lawnchair.metro.CrashLog.reports(launcher).firstOrNull()?.first ?: return@postDelayed
                undoBar.show(
                    this,
                    message = "Metro stopped unexpectedly",
                    hint = null,
                    onUndo = { app.lawnchair.metro.CrashLog.share(launcher, latest) },
                    onHint = {},
                    actionLabel = "SHARE REPORT",
                )
            }, 1200)
        }
        post { askInfoPermissions() }
        LayoutLock.showLockedBar = { onUnlock -> showLockedBar(onUnlock) }
        removeCallbacks(liveTicker)
        postDelayed(liveTicker, LIVE_TICK_MS)
    }

    /**
     * Start stopped being shown (an app is in front, or the screen is off): the flip scheduler
     * and the info tiles' minute tick and weather checks stop entirely, and pick up again when
     * Start is back.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isAttachedToWindow) return
        removeCallbacks(liveTicker)
        if (visibility == View.VISIBLE) {
            InfoTiles.start(launcher)
            postDelayed(liveTicker, LIVE_TICK_MS)
        } else {
            InfoTiles.stop()
        }
    }

    override fun onDetachedFromWindow() {
        LayoutLock.showLockedBar = null
        removeCallbacks(liveTicker)
        LiveTileData.removeListener(liveListener)
        InfoTiles.removeListener(infoListener)
        InfoTiles.stop()
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
            grid.tiles.associate { (it as TileHolder).tile.id to (it.left to it.top) }
        } else {
            emptyMap()
        }
        tiles = store.load()
        // Remove tiles only; the grid also holds the app-list arrow.
        grid.tiles.forEach(grid::removeView)
        val views = tiles.map { createTileView(it) }
        views.forEach { grid.addView(it, grid.childCount - if (grid.footer != null) 1 else 0) }
        grid.setOrder(views)
        if (animate) {
            grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    grid.viewTreeObserver.removeOnPreDrawListener(this)
                    views.forEachIndexed { i, v ->
                        val old = oldSpots[(v as TileHolder).tile.id]
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

    // ---- Info tiles (calendar, weather, next alarm, photos) -------------------------------------

    private val infoListener: () -> Unit = {
        for (i in 0 until grid.childCount) {
            val tv = grid.getChildAt(i) as? TileView ?: continue
            if (tv.infoKind != null) tv.infoChanged()
        }
        panel?.refresh()
    }

    private var grantedInfo = emptySet<String>()

    /**
     * Asks once for what the info tiles on Start need (calendar, location for weather,
     * photos). Each permission is asked at most once; tiles fall back to their icon without it.
     */
    private fun askInfoPermissions() {
        val kinds = grid.tiles.mapNotNull { (it as? TileView)?.infoKind }.toSet()
        val store = launcher.getSharedPreferences("metro_info", Context.MODE_PRIVATE)
        val needed = kinds.mapNotNull { InfoTiles.permissionFor(it) }
            .filter { !InfoTiles.hasPermission(launcher, it) && !store.getBoolean("asked_$it", false) }
            .filter { it != InfoTiles.permissionFor(InfoKind.PHOTOS) || !InfoTiles.hasPhotoAccess(launcher) }
        grantedInfo = kinds.mapNotNull { InfoTiles.permissionFor(it) }.filter { InfoTiles.hasPermission(launcher, it) }.toSet()
        if (needed.isEmpty()) return
        store.edit().apply { needed.forEach { putBoolean("asked_$it", true) } }.apply()
        runCatching { launcher.requestPermissions(needed.toTypedArray(), REQUEST_INFO_PERMISSIONS) }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) return
        post { if (!landing) markVisibleNews() }
        // Back from the permission prompt (or settings): reload what was just allowed.
        val kinds = grid.tiles.mapNotNull { (it as? TileView)?.infoKind }.toSet()
        val now = kinds.mapNotNull { InfoTiles.permissionFor(it) }.filter { InfoTiles.hasPermission(launcher, it) }.toSet()
        if (now != grantedInfo) {
            grantedInfo = now
            InfoTiles.start(launcher)
        } else {
            InfoTiles.refreshAlarm()
            infoListener() // settings such as the photo slideshow may have changed
        }
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
        panel?.refresh()
        updateAlbumGradient(data)
        val visible = Rect()
        for (i in 0 until grid.childCount) {
            val tv = grid.getChildAt(i) as? TileView ?: continue
            if (!tv.tile.isApp) continue
            val pkg = tv.tile.component.packageName
            val info = data[pkg]
            val oldCount = tv.live?.count ?: 0
            tv.live = info
            if (info == null) continue
            val looking = isViewing()
            val visibleNow = looking && !panelOpen && tv.getLocalVisibleRect(visible)
            val onScreen = visibleNow && !landing
            val isNew = livePrimed && info.latestTime > (seenTimes[pkg] ?: 0L)
            when {
                tv.isPinned && !tv.showingBack -> if (onScreen && livePrimed) tv.flip() else tv.showBackNow()
                isNew && tv.hasBackFace && onScreen -> {
                    tv.pickBackDwell(random)
                    if (tv.showingBack) tv.flipRefresh() else tv.flip()
                }
                // Visible but the tiles are still landing: flip once they've settled.
                isNew && tv.hasBackFace && visibleNow && landing -> afterLanding += tv
                // Arrived while you weren't looking (screen off, in an app, or scrolled away):
                // the tile quietly turns to it, so it's already showing when you look.
                isNew && tv.hasBackFace -> {
                    tv.pickBackDwell(random)
                    tv.showUnseen()
                }
                // Small tiles have no content side: one quick turn as the count goes up.
                isNew && tv.tile.size == TileSize.SMALL && info.count > oldCount && onScreen -> flipSmall(tv, oldCount)
            }
        }
        data.forEach { (pkg, info) -> if (info.latestTime > 0) seenTimes[pkg] = info.latestTime }
        livePrimed = true
    }

    private val visibleRect = Rect()
    private val random = java.util.Random()

    /** True when you can actually see Start: screen on, Start in front and not covered. */
    private fun isViewing(): Boolean {
        val pm = launcher.getSystemService(android.os.PowerManager::class.java)
        return isShown && hasWindowFocus() && (pm?.isInteractive ?: true) && panProgress == 0f
    }

    /**
     * A small tile's single turn when its count rises: the new number appears at the edge-on
     * moment. At most once per tile every 10 seconds; if a neighbour is mid-flip it waits a beat.
     */
    private fun flipSmall(tv: TileView, oldCount: Int, retry: Boolean = true) {
        val now = System.currentTimeMillis()
        if (now - tv.lastCountFlip < 10_000L) return
        val busyNeighbour = grid.tiles.any { it !== tv && (it as? TileView)?.isFlipping == true && touches(it, tv) }
        if (busyNeighbour) {
            if (retry) {
                // Keep showing the old count while waiting, so the new one arrives with the turn.
                tv.holdCount(oldCount)
                postDelayed({
                    if (isViewing() && !landing) flipSmall(tv, oldCount, retry = false) else tv.releaseCount()
                }, 450)
            } else {
                tv.releaseCount()
            }
            return
        }
        tv.flipCount(oldCount)
    }

    /** Tiles that got news while landing; they flip once the tiles have settled. */
    private val afterLanding = LinkedHashSet<TileView>()

    /**
     * Tiles showing news that came while you weren't looking start their dwell once they're
     * actually in view (after the app list closes, a panel closes, or you scroll to them).
     */
    private fun markVisibleNews() {
        if (!isViewing()) return
        for (i in 0 until grid.childCount) {
            val tv = grid.getChildAt(i) as? TileView ?: continue
            if (tv.unseen && tv.getLocalVisibleRect(visibleRect)) tv.markShownNow(0)
        }
    }

    // ---- Landing: tiles settle onto Start when it comes into view ----------------------------

    private var landing = false

    /**
     * After unlocking, or coming back from an app, the tiles on screen descend onto Start: each
     * starts slightly enlarged (108%, as if just above the surface) and transparent, and settles
     * at 100% in a quick wave from the top-left. Live flips wait until it's done.
     */
    private var landingAnim: android.animation.ValueAnimator? = null

    fun playLanding() {
        if (panProgress > 0f || drag != null || panel != null || width == 0) return
        // Tiles mid-flip finish their flip instead.
        val tiles = grid.tiles.filter { it.getLocalVisibleRect(visibleRect) && (it as? TileView)?.isFlipping != true }
        if (tiles.isEmpty()) return
        landingAnim?.cancel()
        landing = true
        val pitch = grid.rowPitch.coerceAtLeast(1)
        val delays = tiles.associateWith { v ->
            (((v.left / pitch) + (v.top - scroller.scrollY) / pitch).coerceAtLeast(0) * 12L)
        }
        val duration = 350L
        val longest = (delays.values.maxOrNull() ?: 0L) + duration
        val ease = android.view.animation.DecelerateInterpolator(2f)
        fun apply(elapsed: Long) {
            for ((v, delay) in delays) {
                if (v === grid.draggedView || v.parent == null) continue
                val p = ease.getInterpolation(((elapsed - delay).toFloat() / duration).coerceIn(0f, 1f))
                val s = 1.08f - 0.08f * p
                v.scaleX = s
                v.scaleY = s
                v.alpha = p
            }
            grid.invalidate()
        }
        apply(0)
        // One animator for the whole wave, so nothing lingers on the tiles' own animators.
        landingAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = longest
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { apply((it.animatedFraction * longest).toLong()) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    apply(longest + duration)
                    landing = false
                    // News that came in while landing: flip now, one by one through the rules.
                    afterLanding.forEach { tv ->
                        if (tv.parent != null && tv.hasBackFace && !tv.isFlipping) {
                            tv.pickBackDwell(random)
                            if (tv.showingBack) tv.flipRefresh() else tv.flip()
                        }
                    }
                    afterLanding.clear()
                }
            })
            start()
        }
        // Tiles already showing news count their dwell from now, not from when it arrived.
        tiles.forEach { (it as? TileView)?.let { t -> if (t.showingBack && !t.isPinned) t.markShownNow(longest) } }
    }

    /**
     * Flip scheduler. Every second or so, at most one on-screen tile flips: one that has shown
     * its content long enough goes back to its icon, otherwise a live tile that has shown its
     * icon long enough (3–4s) turns over. At most two tiles are ever mid-flip, and never two
     * neighbours, so Start stays calm and tiles never flip in sync, as on Windows Phone.
     * Nothing animates while Start is hidden.
     */
    private val liveTicker = object : Runnable {
        override fun run() {
            turnPhotoTiles()
            // Sleeps (checks every few seconds) while nothing on Start is live; stops altogether
            // while Start isn't shown (see onWindowVisibilityChanged).
            val idle = !prefs.metroLiveTiles.get() || !isShown || !hasWindowFocus() ||
                (0 until grid.childCount).none { (grid.getChildAt(it) as? TileView)?.let { t -> t.hasBackFace || t.showingBack } == true }
            postDelayed(this, if (idle) 5000L else LIVE_TICK_MS + random.nextInt(600))
            if (idle || panelOpen || holdShown || landing) return
            val now = System.currentTimeMillis()
            val onScreen = (0 until grid.childCount)
                .mapNotNull { grid.getChildAt(it) as? TileView }
                .filter { it.getLocalVisibleRect(visibleRect) && visibleRect.height() > it.height / 2 }
            val busy = onScreen.filter { it.isFlipping }
            if (busy.size >= 2) return
            val free = onScreen.filter { t -> !t.isFlipping && busy.none { b -> touches(t, b) } }

            free.filter { it.isPinned && !it.showingBack }.randomOrNull()?.let {
                it.flip()
                return
            }
            free.filter { it.showingBack && !it.isPinned && now - it.lastFlipAt > it.backDwellMs }
                .randomOrNull()?.let {
                    it.frontDwellMs = 3000L + random.nextInt(1000)
                    it.flip()
                    return
                }
            free.filter { !it.showingBack && !it.isPinned && it.hasBackFace && now - it.lastFlipAt > it.frontDwellMs }
                .randomOrNull()?.let {
                    it.pickBackDwell(random)
                    it.flip()
                }
        }
    }

    /**
     * The Photos tile's turns (to the next photo, to its icon and back) go through here, so
     * they follow the same rules as live-tile flips: never next to a tile that's flipping.
     */
    private fun turnPhotoTiles() {
        if (!prefs.metroLiveTiles.get() || !isShown || !hasWindowFocus() || panelOpen || holdShown || landing) return
        val tiles = (0 until grid.childCount).mapNotNull { grid.getChildAt(it) as? TileView }
        val photo = tiles.filter { it.photoWantsTurn() && it.getLocalVisibleRect(visibleRect) }
        if (photo.isEmpty()) return
        val busy = tiles.filter { it.isFlipping }
        if (busy.size >= 2) return
        photo.firstOrNull { p -> busy.none { touches(p, it) } }?.turnPhoto()
    }

    private val touchA = Rect()
    private val touchB = Rect()

    /** True when two tiles share an edge or corner (or overlap). */
    private fun touches(a: View, b: View): Boolean {
        val slack = dp(8f).toInt()
        touchA.set(a.left - slack, a.top - slack, a.right + slack, a.bottom + slack)
        touchB.set(b.left, b.top, b.right, b.bottom)
        return Rect.intersects(touchA, touchB)
    }

    // ---- Brand-coloured tiles -----------------------------------------------------------

    private val brandRunnable = Runnable { assignBrands() }

    private fun scheduleBrands() {
        removeCallbacks(brandRunnable)
        postDelayed(brandRunnable, 120)
    }

    /** Brand colours depend on where tiles sit (see BrandTiles). */
    private fun assignBrands() {
        if (drag != null) return
        if (BrandTiles.assign(grid, scroller.height) && windowMode) grid.invalidate()
    }

    // ---- Gradient background: album colours while music plays -------------------------------

    private val gradientBackground: Boolean
        get() = background != MetroTheme.BG_BLACK && prefs.metroBackgroundKind.get() == "gradient" && parallax != null
    private var albumKey: String? = null
    private var lastPlayingAt = 0L
    private val albumWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** A pause of more than a minute counts as stopped: back to the saved gradient. */
    private val albumStopCheck = Runnable {
        val playing = LiveTileData.snapshot.values.any { it.isMusic && it.isPlaying }
        if (!playing && System.currentTimeMillis() - lastPlayingAt >= ALBUM_HOLD_MS - 500) {
            albumKey = null
            parallax?.showOverlay(null)
        }
    }

    /**
     * With a gradient background, playing music turns Start's background into a gradient made
     * from the album art (a new one for each song); it goes back to your saved gradient once the
     * music has stopped for a minute. The phone wallpaper isn't touched.
     */
    private fun updateAlbumGradient(data: Map<String, LiveInfo>) {
        val bg = parallax ?: return
        if (!gradientBackground) return
        val music = data.values.firstOrNull { it.isMusic && it.isPlaying && it.image != null }
        if (music == null) {
            if (albumKey != null) {
                removeCallbacks(albumStopCheck)
                postDelayed(albumStopCheck, ALBUM_HOLD_MS)
            }
            return
        }
        lastPlayingAt = System.currentTimeMillis()
        removeCallbacks(albumStopCheck)
        val key = music.packageName + "|" + music.title
        if (key == albumKey) return
        albumKey = key
        val art = music.image ?: return
        // Half resolution is plenty for soft colour; the view scales it up smoothly.
        val w = (bg.width / 2).coerceAtLeast(64)
        val h = (bg.height / 2).coerceAtLeast(64)
        val level = prefs.metroLegibility.get()
        albumWorker.execute {
            val bmp = runCatching {
                app.lawnchair.metro.theme.GradientGen.fromArt(art, w, h).also { g ->
                    val dim = BackgroundDim.dimFor(BackgroundDim.luminanceOf(g), level)
                    if (dim > 0f) android.graphics.Canvas(g).drawColor(android.graphics.Color.argb((dim * 255).toInt(), 0, 0, 0))
                }
            }.getOrNull() ?: return@execute
            post { if (albumKey == key) bg.showOverlay(bmp) }
        }
    }

    /** Measures the background's brightness and sets the dim to match. */
    private fun updateDim() {
        if (background == MetroTheme.BG_BLACK) return
        val level = prefs.metroLegibility.get()
        if (level == 0) {
            dimView.alpha = 0f
            return
        }
        val bg = parallax
        if (bg?.image != null) {
            // Your own photo: the dim is painted into it once (no extra layer per frame).
            if (bg.dimBaked) return
            dimView.alpha = 0f
            BackgroundDim.measure(launcher, bg.image) { lum -> bg.bakeDim(BackgroundDim.dimFor(lum, level)) }
            return
        }
        BackgroundDim.measure(launcher, null) { lum ->
            dimView.animate().alpha(BackgroundDim.dimFor(lum, level)).setDuration(250).start()
        }
    }

    /** Repaints every tile, e.g. after the Monet palette changed. */
    fun refreshColors() {
        updateDim()
        // Material You icon packs recolour with the wallpaper: load them again.
        if (prefs.metroIconPack.get().isNotEmpty()) {
            app.lawnchair.metro.theme.MetroIcons.clear()
            for (i in 0 until grid.childCount) (grid.getChildAt(i) as? TileView)?.reloadIcon()
        }
        for (i in 0 until grid.childCount) (grid.getChildAt(i) as? TileView)?.refreshColors()
        arrow.invalidate()
        invalidate()
    }

    fun scrollToTop() = scroller.smoothScrollTo(0, 0)

    private var panProgress = 0f

    /** True while a notification panel is open over Start: tiles hold still. */
    private var panelOpen = false

    /**
     * Called while the app list slides in (0 = Start, 1 = app list). Start and the list are one
     * wide surface: the tiles slide out exactly as the list slides in, over a background that
     * moves more slowly (photo) or not at all (system wallpaper), which gives the parallax.
     */
    fun setPanProgress(p: Float) {
        panProgress = p
        if (p == 0f) post { markVisibleNews() }
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

    private fun createTileView(tile: MetroTile): View {
        if (tile.kind == MetroTile.Kind.WIDGET) {
            val host = MetroWidgets.createView(launcher, tile.widgetId)
            return WidgetTileView(launcher, tile, host) { beginDrag(it) }
        }
        return createAppTile(tile)
    }

    private fun createAppTile(tile: MetroTile) = TileView(launcher, tile).apply {
        windowMode = this@StartView.windowMode
        if (tile.isApp) InfoTiles.kindOf(tile.component.packageName)?.let { infoKind = it }
        infoChanged()
        onIconLoaded = { scheduleBrands() }
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
        // Info tiles: tap an event on the calendar tile to open it; the clock tile opens the alarm.
        view.calendarEventAt(view.downX, view.downY)?.let {
            InfoTiles.openEvent(launcher, it)
            return
        }
        if (view.infoKind == InfoKind.CLOCK) {
            // The alarm's own screen when it's an activity; otherwise just open the clock app.
            val pi = InfoTiles.alarm?.showIntent?.takeIf { android.os.Build.VERSION.SDK_INT < 31 || it.isActivity }
            if (pi != null) {
                val opts = if (android.os.Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else {
                    null
                }
                if (runCatching { pi.send(launcher, 0, null, null, null, null, opts) }.isSuccess) return
            }
        }
        runCatching {
            val t = view.tile
            if (t.kind == MetroTile.Kind.SHORTCUT) {
                check(MetroShortcuts.start(launcher, t.component.packageName, t.shortcutId ?: "", bounds, options))
            } else {
                launcherApps.startMainActivity(t.component, Process.myUserHandle(), bounds, options)
            }
            MetroUsage.recordLaunch(launcher, t.component.packageName)
        }.onFailure {
            Toast.makeText(launcher, "Couldn't open ${view.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTileMenu(view: View) {
        val tile = (view as TileHolder).tile
        val isWidget = tile.kind == MetroTile.Kind.WIDGET
        val locked = LayoutLock.isLocked(launcher)
        val menu = PopupMenu(launcher, view, Gravity.END)
        if (!locked) {
            val sizes = menu.menu.addSubMenu(Menu.NONE, MENU_RESIZE, 0, "Resize")
            TileSize.entries.forEachIndexed { i, size ->
                if (isWidget && size == TileSize.SMALL) return@forEachIndexed // widgets need room
                sizes.add(GROUP_SIZE, i, i, size.label).setCheckable(true).isChecked = size == tile.size
            }
            sizes.setGroupCheckable(GROUP_SIZE, true, true)
        }

        if (!isWidget) {
            val colors = menu.menu.addSubMenu(Menu.NONE, MENU_COLOR, 1, "Tile color")
            colors.add(GROUP_COLOR, 0, 0, "Automatic")
            colors.add(GROUP_COLOR, 1, 1, "Brand color")
            colors.add(GROUP_COLOR, 2, 2, "Accent color")
            MetroTheme.CLASSIC_ACCENTS.keys.forEachIndexed { i, name ->
                colors.add(GROUP_COLOR, i + 3, i + 3, name.replaceFirstChar { it.uppercase() })
            }
        }
        if (tile.isApp) {
            // Make this app's tile one of the live info tiles.
            val current = (view as? TileView)?.infoKind
            val live = menu.menu.addSubMenu(Menu.NONE, MENU_LIVE, 2, "Live tile")
            listOf(InfoKind.CALENDAR to "Calendar", InfoKind.PHOTOS to "Photos", InfoKind.WEATHER to "Weather", InfoKind.CLOCK to "Next alarm")
                .forEachIndexed { i, (kind, name) ->
                    live.add(GROUP_LIVE, i, i, name).setCheckable(true).isChecked = current == kind
                }
            live.setGroupCheckable(GROUP_LIVE, true, true)
        }
        val isPhotos = (view as? TileView)?.infoKind == InfoKind.PHOTOS
        if (isPhotos) {
            menu.menu.add(Menu.NONE, MENU_SLIDESHOW, 2, if (prefs.metroPhotoSlideshow.get()) "Turn photo slideshow off" else "Turn photo slideshow on")
        }
        if (!locked) {
            menu.menu.add(Menu.NONE, MENU_LOCK, 2, if (tile.locked) "Unlock tile" else "Lock tile (auto layout keeps it)")
            menu.menu.add(Menu.NONE, MENU_UNPIN, 3, if (isWidget) "Remove widget" else "Unpin from Start")
        }
        if (!isWidget) menu.menu.add(Menu.NONE, MENU_INFO, 4, "App info")
        if (locked) menu.menu.add(Menu.NONE, MENU_UNLOCK_LAYOUT, 5, "Unlock layout")

        menu.setOnMenuItemClickListener { item ->
            when {
                item.groupId == GROUP_SIZE -> {
                    tile.size = TileSize.entries[item.itemId]
                    if (tile.isApp) MetroUsage.recordSize(launcher, tile.component.packageName, tile.size)
                    commit(view)
                }
                item.groupId == GROUP_LIVE -> {
                    val kind = listOf(InfoKind.CALENDAR, InfoKind.PHOTOS, InfoKind.WEATHER, InfoKind.CLOCK)[item.itemId]
                    InfoTiles.setApp(launcher, kind, tile.component.packageName) // Start reloads
                }
                item.itemId == MENU_SLIDESHOW -> {
                    prefs.metroPhotoSlideshow.set(!prefs.metroPhotoSlideshow.get())
                    (view as? TileView)?.infoChanged()
                }
                item.itemId == MENU_LOCK -> {
                    tile.locked = !tile.locked
                    store.save(tiles)
                }
                item.groupId == GROUP_COLOR -> {
                    tile.color = when (item.itemId) {
                        0 -> 0
                        1 -> MetroTile.COLOR_BRAND
                        2 -> MetroTile.COLOR_ACCENT
                        else -> MetroTheme.CLASSIC_ACCENTS.values.elementAt(item.itemId - 3)
                    }
                    commit(view)
                    scheduleBrands()
                }
                item.itemId == MENU_UNPIN -> {
                    tiles.removeAll { it.id == tile.id }
                    store.save(tiles)
                    if (tile.isApp) MetroUsage.recordUnpinned(launcher, tile.component.packageName)
                    if (isWidget) MetroWidgets.delete(launcher, tile.widgetId)
                    grid.setOrder(grid.tiles.filter { it !== view })
                    grid.removeView(view)
                    grid.animateReflow()
                    invalidate()
                }
                item.itemId == MENU_UNLOCK_LAYOUT -> {
                    LayoutLock.setLocked(launcher, false)
                    Toast.makeText(launcher, "Start unlocked", Toast.LENGTH_SHORT).show()
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

    private fun commit(view: View) {
        store.save(tiles)
        (view as? TileView)?.setTileData(view.tile)
        grid.animateReflow()
        invalidate()
    }

    /** Long-press on empty Start space: add a widget, wallpaper, settings. */
    private fun showStartMenu() {
        val anchor = View(launcher)
        addView(anchor, LayoutParams(1, 1).apply {
            leftMargin = lastTouchX.toInt()
            topMargin = lastTouchY.toInt()
        })
        val menu = PopupMenu(launcher, anchor)
        val locked = LayoutLock.isLocked(launcher)
        menu.menu.add(0, 1, 0, "Add widget")
        menu.menu.add(0, 4, 1, if (locked) "Unlock Start layout" else "Lock Start layout")
        menu.menu.add(0, 2, 2, "Wallpaper")
        menu.menu.add(0, 3, 3, "Metro settings")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> LayoutLock.guard(launcher) { WidgetPicker.show(launcher) { info -> MetroWidgets.add(launcher, info) } }
                4 -> {
                    LayoutLock.setLocked(launcher, !locked)
                    Toast.makeText(launcher, if (locked) "Start unlocked" else "Start layout locked", Toast.LENGTH_SHORT).show()
                }
                2 -> runCatching {
                    launcher.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                }
                3 -> launcher.startActivity(
                    app.lawnchair.ui.preferences.PreferenceActivity.createIntent(
                        launcher,
                        app.lawnchair.ui.preferences.navigation.HomeScreen,
                    ),
                )
            }
            true
        }
        menu.setOnDismissListener { removeView(anchor) }
        menu.show()
    }

    private fun openAppList() = launcher.openMetroAppList()

    // ---- Drag to rearrange --------------------------------------------------------------

    private class Drag(val view: View, val grabX: Float, val grabY: Float, val downRawX: Float, val downRawY: Float) {
        var moved = false
        var rawX = downRawX
        var rawY = downRawY
        var lastTarget: View? = null
        val holder: TileHolder get() = view as TileHolder
    }

    private var drag: Drag? = null
    private val touchSlop = android.view.ViewConfiguration.get(launcher).scaledTouchSlop
    private val gridLoc = IntArray(2)
    private val scrollerLoc = IntArray(2)

    /** Picks [view] up: it lifts slightly and follows the finger until released. */
    private fun beginDrag(view: View) {
        if (drag != null) return
        // Locked layout: a long press opens the tile's menu instead of picking it up.
        if (LayoutLock.isLocked(launcher)) {
            showTileMenu(view)
            return
        }
        val h = view as TileHolder
        val d = Drag(view, h.downX, h.downY, h.downRawX, h.downRawY)
        drag = d
        view.parent?.requestDisallowInterceptTouchEvent(true)
        grid.draggedView = view
        h.lifted = true
        view.translationZ = dp(8f)
        view.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.92f).setDuration(140)
            .setUpdateListener { grid.invalidate() }.start()
        h.dragHandler = { ev -> onDragEvent(d, ev) }
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
        val newOrder: List<View>? = when {
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
            tiles = newOrder.map { (it as TileHolder).tile }.toMutableList()
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
        d.holder.dragHandler = null
        grid.draggedView = null
        v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .setUpdateListener { grid.invalidate() }
            .withEndAction {
                v.translationZ = 0f
                d.holder.lifted = false
                grid.invalidate()
            }
            .start()
        if (d.moved) {
            tiles = grid.tiles.map { (it as TileHolder).tile }.toMutableList()
            store.save(tiles)
            val t = d.holder.tile
            if (t.isApp) MetroUsage.recordMoved(launcher, t.component.packageName, regionOf(v))
        }
        if (showMenu) showTileMenu(v)
    }

    /** Which part of Start a tile sits in: top or bottom of the first screen, or below it. */
    private fun regionOf(v: View): MetroUsage.Region {
        val rows = grid.rowsInViewport(scroller.height)
        val row = grid.cellRowOf(v).coerceAtLeast(0)
        return when {
            row >= rows -> MetroUsage.Region.BELOW
            row >= rows * 0.6 -> MetroUsage.Region.BOTTOM
            else -> MetroUsage.Region.TOP
        }
    }

    /**
     * "Start is locked" at the bottom of whatever is on screen (Start or the app list), with an
     * Unlock button. [onUnlock] finishes what was blocked (a pin) after unlocking; without it,
     * the button just unlocks.
     */
    private fun showLockedBar(onUnlock: (() -> Unit)?) {
        val host: FrameLayout = launcher.metroAppList?.takeIf { it.isOpen } ?: this
        undoBar.show(
            host,
            message = "Start is locked",
            hint = null,
            onUndo = {
                if (onUnlock != null) {
                    onUnlock()
                } else {
                    LayoutLock.setLocked(launcher, false)
                    Toast.makeText(launcher, "Start unlocked", Toast.LENGTH_SHORT).show()
                }
            },
            onHint = {},
            actionLabel = "UNLOCK",
        )
    }

    // ---- Auto layout --------------------------------------------------------------------

    /** True while the hold on the arrow is being shown on the tiles. */
    private var holdShown = false
    private val holdLoc = IntArray(2)
    private val tileLoc = IntArray(2)

    /**
     * Shows the arrow hold across Start, so it's visible past the thumb: every tile on screen
     * sinks back (to ~92%) and dims slightly, led by the tiles nearest the arrow. From a third
     * of the way, tiles start to wiggle, each at its own rhythm, growing to about 3°. Winding
     * back (let go, or done) the wiggle stops at once and the tiles rise again.
     */
    private fun showHold(p: Float, releasing: Boolean) {
        if (!releasing) holdCompleted = false
        if (releasing && holdCompleted) return // onHoldDone restores the tiles itself
        holdShown = p > 0f
        arrow.getLocationOnScreen(holdLoc)
        val ax = holdLoc[0] + arrow.width / 2f
        val ay = holdLoc[1] + arrow.height / 2f
        val reach = hypot(width.toFloat(), height.toFloat()).coerceAtLeast(1f)
        val now = android.os.SystemClock.uptimeMillis()
        for (v in grid.tiles) {
            if (v === grid.draggedView) continue
            v.getLocationOnScreen(tileLoc)
            val d = (hypot(tileLoc[0] + v.width / 2f - ax, tileLoc[1] + v.height / 2f - ay) / reach).coerceIn(0f, 1f)
            // Nearest tiles lead, farther ones follow a little behind.
            val local = ((p * 1.3f) - d * 0.3f).coerceIn(0f, 1f)
            val s = 1f - 0.08f * local
            v.scaleX = s
            v.scaleY = s
            v.alpha = 1f - 0.22f * local
            v.rotation = if (releasing || p < 1f / 3f) {
                0f
            } else {
                val w = ((p - 1f / 3f) / (2f / 3f)).coerceIn(0f, 1f)
                val id = (v as TileHolder).tile.id
                val freq = 0.018f + (id % 7) * 0.0025f // radians per ms: a few wobbles a second
                val phase = (id * 1.7f) % 6.28f
                (1f + 2f * w) * kotlin.math.sin(now * freq + phase)
            }
        }
        grid.invalidate()
    }

    /** Hold finished: the wiggle stops, a beat of stillness, then the tiles rearrange. */
    private fun onHoldDone() {
        holdCompleted = true
        holdShown = false
        grid.tiles.forEach {
            it.rotation = 0f
            it.postDelayed({
                it.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160)
                    .setUpdateListener { grid.invalidate() }.start()
            }, 120)
        }
        grid.invalidate()
        postDelayed({ runAutoLayout() }, 200)
    }

    private var holdCompleted = false

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
        if (drag == null && (panel == null || panelSwipe.active) && panelSwipe.onTouch(ev)) return true
        if (panel != null && !panelSwipe.active) return super.dispatchTouchEvent(ev)
        if (drag == null && swipeDetector.onTouchEvent(ev)) {
            // Swipe handled: cancel whatever the tiles were doing with this gesture.
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---- Notification panel (swipe right on a tile) ------------------------------------------

    private var panel: NotificationPanel? = null
    private val hostLoc = IntArray(2)
    private val viewLoc = IntArray(2)

    private val panelSwipe = PanelSwipe(this, find = { x, y -> panelTargetAt(x, y) }, create = { openPanel(it) })

    val isPanelOpen: Boolean get() = panel != null

    /** Closes the notification panel; returns false if none was open. */
    fun closePanel(): Boolean {
        val p = panel ?: return false
        p.close(animate = true)
        return true
    }

    /** The tile under (x, y), in this view's coordinates, if it has notifications to act on. */
    private fun panelTargetAt(x: Float, y: Float): NotificationPanel.Target? {
        if (drag != null || panProgress > 0f) return null
        getLocationOnScreen(hostLoc)
        grid.getLocationOnScreen(viewLoc)
        // Layout positions, not the tiles' current transforms (a tile may be mid-flip).
        val gx = viewLoc[0] - hostLoc[0] - grid.scrollX
        val gy = viewLoc[1] - hostLoc[1] - grid.scrollY
        for (v in grid.tiles) {
            val tv = v as? TileView ?: continue
            if (!tv.tile.isApp) continue
            val l = gx + tv.left
            val t = gy + tv.top
            if (x < l || x >= l + tv.width || y < t || y >= t + tv.height) continue
            val pkg = tv.tile.component.packageName
            if (tv.infoKind == InfoKind.CALENDAR && InfoTiles.hasPermission(launcher, android.Manifest.permission.READ_CALENDAR)) {
                return NotificationPanel.Target(pkg, tv.label, tv.iconInfo?.brandColor ?: 0, Rect(l, t, l + tv.width, t + tv.height), tv, agenda = true)
            }
            if ((tv.live?.count ?: 0) == 0 || !LiveTileData.hasPanelContent(pkg)) return null
            val brand = tv.iconInfo?.brandColor ?: 0
            return NotificationPanel.Target(pkg, tv.label, brand, Rect(l, t, l + tv.width, t + tv.height), tv)
        }
        return null
    }

    private fun openPanel(target: NotificationPanel.Target): NotificationPanel {
        panel?.close(animate = false)
        val p = NotificationPanel(launcher, target, panelCallbacks)
        addView(p, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel = p
        panelOpen = true
        launcher.setMetroBackEnabled(true)
        return p
    }

    private val panelCallbacks = object : NotificationPanel.Callbacks {
        override fun onClosed(panel: NotificationPanel, emptied: Boolean) {
            if (this@StartView.panel === panel) {
                this@StartView.panel = null
                panelOpen = false
                launcher.setMetroBackEnabled(false)
                markVisibleNews()
            }
            // Last card gone: the tile goes back to its icon side.
            val tv = panel.target.view as? TileView
            if (emptied && tv != null && tv.showingBack && !tv.isPinned) tv.flip()
        }

        override fun targetAt(x: Float, y: Float) = panelTargetAt(x, y)

        override fun switchTo(target: NotificationPanel.Target) {
            openPanel(target).animateOpen()
        }

        override fun insets() = systemInsets
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
        private const val MENU_SLIDESHOW = 105
        private const val MENU_LIVE = 106
        private const val MENU_UNLOCK_LAYOUT = 107
        private const val GROUP_LIVE = 3
        private const val REQUEST_INFO_PERMISSIONS = 7400
        private const val GROUP_SIZE = 1
        private const val GROUP_COLOR = 2
        private const val LIVE_TICK_MS = 1100L
        private const val ALBUM_HOLD_MS = 60_000L
    }
}
