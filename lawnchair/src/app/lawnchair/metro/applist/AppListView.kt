package app.lawnchair.metro.applist

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import app.lawnchair.metro.ui.showPane
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.metro.motion.AppOpen
import app.lawnchair.LawnchairLauncher
import app.lawnchair.metro.data.MetroTileStore
import app.lawnchair.metro.live.LiveInfo
import app.lawnchair.metro.live.LiveTileData
import app.lawnchair.metro.motion.Turnstile
import app.lawnchair.metro.start.StartView
import app.lawnchair.metro.theme.MetroIcons
import app.lawnchair.metro.theme.MetroTheme
import com.android.launcher3.Insettable
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.model.data.AppInfo
import java.text.Collator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Metro app list: a modern take on the Windows Phone 8.1 list.
 *
 * - A pane of frosted glass: the home wallpaper stays faintly visible through a heavy blur, so
 *   sliding the list in feels like drawing a glass curtain across Start.
 * - Apps under outlined letter headers, each on its accent square with the same white glyph as
 *   on Start, with a soft glow in the app's own brand colour.
 * - If an app has a notification, a muted one-line snippet sits under its name.
 * - Search sits at the bottom, within thumb reach, and rides above the keyboard; results
 *   gather next to it. Enter opens the first match.
 * - Slides in from Start; swipe left-to-right (or press Home) to return.
 * - Long-press an app to pin it to Start, see App info or uninstall.
 *
 * Apps come from Launcher3's all-apps store, so hidden apps and work profiles behave as in the
 * rest of the launcher.
 */
@SuppressLint("ViewConstructor")
class AppListView(private val launcher: LawnchairLauncher) : FrameLayout(launcher), Insettable {

    private sealed class Row {
        data class Header(val letter: Char) : Row()
        data class App(val info: AppInfo) : Row()
    }

    private val store: AllAppsStore<*> get() = launcher.appsView.appsStore
    private var allApps: List<AppInfo> = emptyList()
    private var rows: List<Row> = emptyList()
    private var live: Map<String, LiveInfo> = emptyMap()

    private val search = EditText(launcher)
    /**
     * The list. Its section's letter header sticks to the top (see [StickyHeader]); rows scroll
     * under it and are hidden there, so the background shows around the sticky letter.
     */
    private val list = object : RecyclerView(launcher) {
        override fun draw(c: Canvas) {
            measureSticky()
            super.draw(c)
        }

        override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
            if (stickyLetter == null) return super.drawChild(canvas, child, drawingTime)
            val save = canvas.save()
            canvas.clipRect(0f, stickyBottom, width.toFloat(), 100_000f)
            val r = super.drawChild(canvas, child, drawingTime)
            canvas.restoreToCount(save)
            return r
        }
    }

    // ---- Sticky letter header -------------------------------------------------------------

    /** The letter shown stuck to the top, or null (searching, or no headers yet). */
    private var stickyLetter: Char? = null
    /** Where the sticky header sits this frame (list coordinates); rows are hidden above [stickyBottom]. */
    private var stickyTop = 0f
    private var stickyBottom = 0f
    private val headerHeight = dp(58f)
    private val stickyView by lazy { HeaderView(launcher) }
    /** Section letter of each row (null for search results), parallel to [rows]. */
    private var rowLetters: List<Char?> = emptyList()

    /** Works out the sticky header for this frame: the section at the top, pushed up by the next one. */
    private fun measureSticky() {
        val top = list.paddingTop.toFloat()
        if (sectionPositions.isEmpty() || list.childCount == 0) {
            stickyLetter = null
            return
        }
        var letter: Char? = null
        var pushUp = Float.MAX_VALUE
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val pos = list.getChildAdapterPosition(child)
            if (pos < 0) continue
            val y = child.top + child.translationY
            if (y <= top + 1f && y + child.height > top) letter = rowLetters.getOrNull(pos)
            if (child is HeaderView && y > top + 1f) pushUp = minOf(pushUp, y)
        }
        if (letter == null) {
            // Nothing straddles the top edge (a gap): use the first row below it.
            val first = layoutManager.findFirstVisibleItemPosition()
            letter = rowLetters.getOrNull(first)
        }
        stickyLetter = letter
        stickyTop = if (pushUp < top + headerHeight) pushUp - headerHeight else top
        stickyBottom = stickyTop + headerHeight
    }

    /** Draws the sticky header over the list; it has no background, so the wallpaper shows. */
    private inner class StickyHeader : RecyclerView.ItemDecoration() {
        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val letter = stickyLetter ?: return
            // While the rows swing away, the sticky letter goes with them.
            if (turnedAway) return
            val v = stickyView
            val w = parent.width
            if (v.width != w || v.height != headerHeight.toInt()) {
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(headerHeight.toInt(), View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, headerHeight.toInt())
            }
            v.letter = letter
            // While scrubbing, a sticky letter other than the picked one fades with the rest.
            val dim = if (focusLetter != null && letter != focusLetter) 1f - 0.3f * veil else 1f
            val save = if (dim < 1f) {
                c.saveLayerAlpha(0f, stickyTop, w.toFloat(), stickyBottom, (255 * dim).toInt())
            } else {
                c.save()
            }
            c.translate(0f, stickyTop)
            v.draw(c)
            c.restoreToCount(save)
        }
    }

    /** True when the touch went down on the sticky header, where the rows underneath are hidden. */
    private fun touchedSticky(): Boolean = stickyLetter != null && lastDownY < stickyBottom
    private var lastDownY = 0f

    // ---- Greying while scrubbing ------------------------------------------------------------

    /**
     * While a finger is on the letters, the section being picked. Everything above and below it
     * sits under one smooth dark veil with soft edges (not row by row, so no seams), which fades
     * in as the finger lands and out as it lets go.
     */
    private var focusLetter: Char? = null
    private var veil = 0f
    private var veilAnim: ValueAnimator? = null

    private fun setFocusLetter(letter: Char?) {
        if (letter != null) focusLetter = letter
        val to = if (letter == null) 0f else 1f
        if (veilAnim?.isRunning != true && veil == to) {
            list.invalidate()
            return
        }
        veilAnim?.cancel()
        veilAnim = ValueAnimator.ofFloat(veil, to).apply {
            duration = if (to > 0f) 160 else 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                veil = it.animatedValue as Float
                list.invalidate()
            }
            if (to == 0f) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (veil == 0f) focusLetter = null
                    }
                })
            }
            start()
        }
    }

    private inner class ScrubVeil : RecyclerView.ItemDecoration() {
        private val paint = android.graphics.Paint()
        private val fadePaint = android.graphics.Paint()
        private val feather = dp(72f)

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val letter = focusLetter ?: return
            if (veil <= 0f) return
            // The picked section's extent on screen: from its header to the next header.
            var top = Float.NaN
            var bottom = Float.NaN
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos < 0 || rowLetters.getOrNull(pos) != letter) continue
                val t = child.top + child.translationY
                val b = t + child.height
                if (top.isNaN() || t < top) top = t
                if (bottom.isNaN() || b > bottom) bottom = b
            }
            val w = parent.width.toFloat()
            val h = parent.height.toFloat()
            val alpha = (0x48 * veil).toInt()
            paint.color = Color.argb(alpha, 0, 0, 0)
            if (top.isNaN()) {
                c.drawRect(0f, 0f, w, h, paint)
                return
            }
            // Above the section, fading out just before its header…
            if (top > 0f) {
                c.drawRect(0f, 0f, w, (top - feather).coerceAtLeast(0f), paint)
                drawFade(c, w, top - feather, top, alpha, down = true)
            }
            // …and below it, fading in just after its last app.
            if (bottom < h) {
                drawFade(c, w, bottom, bottom + feather, alpha, down = false)
                c.drawRect(0f, bottom + feather, w, h, paint)
            }
        }

        private fun drawFade(c: Canvas, w: Float, from: Float, to: Float, alpha: Int, down: Boolean) {
            val solid = Color.argb(alpha, 0, 0, 0)
            val clear = Color.argb(0, 0, 0, 0)
            fadePaint.shader = android.graphics.LinearGradient(
                0f, from, 0f, to,
                if (down) solid else clear, if (down) clear else solid,
                android.graphics.Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, from, w, to, fadePaint)
        }
    }
    private val scrubber = AlphabetScrubber(launcher) { letter, y -> jumpTo(letter, y) }
    private val sectionPositions = HashMap<Char, Int>()

    /** Black behind the status bar, and behind the search bar at the bottom. */
    private val statusStrip = app.lawnchair.metro.start.StatusFade(launcher)
    private val searchPanel = View(launcher).apply { setBackgroundColor(Color.BLACK) }

    private val background = MetroTheme.background(launcher)
    /**
     * WP 8.1 window mode: each app square is a window onto the same wallpaper as Start, on black.
     * With "background behind the app list" on, the list instead sits over the background (as in
     * wallpaper mode), with solid squares.
     */
    private val windowMode = background == MetroTheme.BG_WINDOW &&
        !app.lawnchair.preferences.PreferenceManager.getInstance(launcher).metroAppListPhoto.get()
    private val layoutManager = LinearLayoutManager(launcher)
    private val adapter = Adapter()

    var isOpen = false
        private set

    /** The Start screen, which slides aside while the list is open. */
    var startView: StartView? = null

    private val updateListener = AllAppsStore.OnUpdateListener { refreshApps() }
    private val liveListener: (Map<String, LiveInfo>) -> Unit = { next ->
        // Only rows whose snippet actually changed are redrawn.
        val old = live
        live = next
        val changed = (old.keys + next.keys).filterTo(HashSet()) { pkg -> snippetFor(pkg, old)?.toString() != snippetFor(pkg, next)?.toString() }
        if (changed.isNotEmpty()) {
            rows.forEachIndexed { i, r ->
                if (r is Row.App && r.info.componentName?.packageName in changed) adapter.notifyItemChanged(i)
            }
        }
        panel?.refresh()
    }

    private var navInset = 0
    private var imeLift = 0
    private val searchHeight = dp(52f).toInt()
    private val searchMargin = dp(12f).toInt()

    private var pan = 0f
    private var panAnim: ValueAnimator? = null

    private val swipeDetector = GestureDetector(launcher, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            if (scrubber.visibility == VISIBLE && start.x > width - dp(40f)) return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (dx > dp(60f) && abs(dx) > abs(dy) * 1.3f && vx > dp(250f)) {
                close(animate = true)
                return true
            }
            return false
        }
    })

    init {
        // Same surface as Start: black in black and window modes (window mode cuts holes for
        // the app squares), the dimmed wallpaper itself in wallpaper mode.
        setBackgroundColor(Color.TRANSPARENT) // Start's background dim (behind) keeps the list readable
        visibility = GONE
        isClickable = true

        list.layoutManager = layoutManager
        list.adapter = adapter
        list.clipToPadding = false // rows slide under the status bar's fade
        list.overScrollMode = OVER_SCROLL_NEVER
        list.isVerticalScrollBarEnabled = false
        list.itemAnimator = null
        list.addItemDecoration(WindowMask())
        list.addItemDecoration(ScrubVeil())
        list.addItemDecoration(StickyHeader())
        // Scrolling the results, or tapping empty space, puts the keyboard away.
        // A finger on the letters stops a moving list before it jumps.
        scrubber.onTouchStart = { list.stopScroll() }
        // Letting go of the letters brings the other apps back.
        scrubber.onTouchEnd = { setFocusLetter(null) }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateScrolledLetter()

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && search.hasFocus()) {
                    hideKeyboard()
                    updateBack()
                }
            }
        })
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        lastDownY = e.y
                    }
                    MotionEvent.ACTION_UP -> if (search.hasFocus() && abs(e.x - downX) < dp(10f) && abs(e.y - downY) < dp(10f) &&
                        rv.findChildViewUnder(e.x, e.y) == null
                    ) {
                        hideKeyboard()
                        updateBack()
                    }
                }
                return false
            }
        })
        // Tapping the top puts the keyboard away; otherwise touches go through to the rows.
        statusStrip.setOnTouchListener { _, e ->
            if (!search.hasFocus()) return@setOnTouchListener false
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                hideKeyboard()
                updateBack()
            }
            true
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(searchPanel, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
        addView(statusStrip, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        addView(scrubber, LayoutParams(dp(190f).toInt(), LayoutParams.MATCH_PARENT, Gravity.END))

        setupSearch()
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, searchHeight, Gravity.BOTTOM).apply {
            leftMargin = dp(16f).toInt()
            rightMargin = dp(16f).toInt()
        })

        // Keep the search bar above the keyboard as it slides up and down.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                    applyIme(insets, relayout = false)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimation) {
                    rootWindowInsets?.let { applyIme(it, relayout = true) }
                }
            })
        }
    }

    private fun setupSearch() {
        search.hint = "search apps"
        search.setHintTextColor(0x99FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        search.typeface = app.lawnchair.metro.theme.PaneFonts.light
        search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
        search.background = GradientDrawable().apply {
            cornerRadius = dp(4f)
            setColor(0x26FFFFFF)
            setStroke(dp(1f).toInt(), 0x33FFFFFF)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                rebuildRows()
                updateBack()
            }
        })
        search.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (go) {
                firstResult()?.let { launch(it, search) }
            }
            go
        }
        search.setOnFocusChangeListener { _, focused ->
            updateBack()
            // Fallback for keyboards that don't animate their insets.
            if (focused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                postDelayed({ rootWindowInsets?.let { applyIme(it, relayout = true) } }, 350)
            }
        }
    }

    private fun firstResult(): AppInfo? {
        val apps = rows.mapNotNull { (it as? Row.App)?.info }
        // With a query, results are stacked from the bottom, so the best match is the last row.
        return if (isSearching()) apps.lastOrNull() else apps.firstOrNull()
    }

    private fun isSearching() = search.text?.isNotBlank() == true

    private val systemInsets = Rect()

    override fun setInsets(insets: Rect) {
        systemInsets.set(insets)
        navInset = insets.bottom
        (statusStrip.layoutParams as LayoutParams).height = app.lawnchair.metro.start.StatusFade.heightFor(insets.top)
        statusStrip.requestLayout()
        list.setPadding(0, insets.top + dp(8f).toInt(), dp(36f).toInt(), 0)
        scrubber.topInset = insets.top + dp(8f)
        layoutForKeyboard(relayout = true)
    }

    private fun applyIme(insets: WindowInsets, relayout: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        imeLift = (ime - navInset).coerceAtLeast(0)
        layoutForKeyboard(relayout)
    }

    /** Search bar sits above the gesture bar, or above the keyboard while typing; the list ends above it. */
    private fun layoutForKeyboard(relayout: Boolean) {
        (search.layoutParams as LayoutParams).bottomMargin = navInset + searchMargin
        search.translationY = -imeLift.toFloat()
        if (relayout) {
            val reserved = navInset + searchMargin * 2 + searchHeight + imeLift
            (list.layoutParams as LayoutParams).bottomMargin = reserved
            (searchPanel.layoutParams as LayoutParams).height = reserved
            // The letters keep their size; while the keyboard is up they're hidden instead.
            scrubber.bottomInset = (navInset + searchMargin * 2 + searchHeight).toFloat()
            updateScrubberVisibility()
            (scrubber.layoutParams as LayoutParams).bottomMargin = 0
            list.requestLayout()
            searchPanel.requestLayout()
            scrubber.invalidate()
        }
        search.requestLayout()
        updateScrubberVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        store.addUpdateListener(updateListener)
        LiveTileData.addListener(launcher, liveListener)
        refreshApps()
        refreshUsage()
    }

    override fun onDetachedFromWindow() {
        LiveTileData.removeListener(liveListener)
        store.removeUpdateListener(updateListener)
        super.onDetachedFromWindow()
    }

    // ---- Data ---------------------------------------------------------------------------

    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    private fun sectionOf(info: AppInfo): Char {
        val c = info.title?.toString()?.trim()?.firstOrNull()?.uppercaseChar() ?: return '#'
        return if (c in 'A'..'Z') c else '#'
    }

    /** How much each app is used (Metro launches + Android usage), refreshed on each open. */
    private var usage: Map<String, Double> = emptyMap()
    private val usageWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun usageOf(info: AppInfo): Double = usage[info.componentName?.packageName] ?: 0.0

    /** Re-reads usage in the background, then re-sorts (only while the list is closed or opening). */
    private fun refreshUsage() {
        val ctx = launcher.applicationContext
        val pkgs = store.apps.mapNotNull { it.componentName?.packageName }.distinct()
        usageWorker.execute {
            val opens = runCatching { app.lawnchair.metro.data.MetroUsage.appOpens(ctx) }.getOrDefault(emptyMap())
            val scores = pkgs.associateWith { pkg ->
                (opens[pkg] ?: 0.0) + runCatching { app.lawnchair.metro.data.MetroUsage.launchScore(ctx, pkg) }.getOrDefault(0.0)
            }
            post {
                usage = scores
                if (!isOpen) refreshApps()
            }
        }
    }

    /**
     * Alphabetical sections; within each letter, the apps you use most come first, and apps you
     * rarely or never open stay alphabetical after them.
     */
    private fun refreshApps() {
        allApps = store.apps
            .sortedWith(
                compareBy<AppInfo> { if (sectionOf(it) == '#') 0 else 1 }
                    .thenBy { sectionOf(it) }
                    .thenByDescending { if (usageOf(it) >= 0.5) usageOf(it) else 0.0 }
                    .thenComparator { a, b -> collator.compare(a.title?.toString() ?: "", b.title?.toString() ?: "") },
            )
        rebuildRows()
    }

    private fun rebuildRows() {
        val query = search.text?.toString()?.trim().orEmpty()
        sectionPositions.clear()
        rows = if (query.isNotEmpty()) {
            // Best matches (name starts with the query) closest to the search bar.
            allApps.filter { it.title?.toString()?.contains(query, ignoreCase = true) == true }
                // …and among those, the ones you use more sit closer to the search bar.
                .sortedWith(
                    compareBy<AppInfo> { if (it.title?.toString()?.startsWith(query, ignoreCase = true) == true) 1 else 0 }
                        .thenBy { usageOf(it) },
                )
                .map { Row.App(it) }
        } else {
            val out = ArrayList<Row>()
            var section: Char? = null
            for (app in allApps) {
                val s = sectionOf(app)
                if (s != section) {
                    section = s
                    sectionPositions[s] = out.size
                    out += Row.Header(s)
                }
                out += Row.App(app)
            }
            out
        }
        rowLetters = if (query.isNotEmpty()) {
            emptyList()
        } else {
            var cur: Char? = null
            rows.map { r -> (r as? Row.Header)?.letter?.also { cur = it } ?: cur }
        }
        if (query.isNotEmpty()) stickyLetter = null
        layoutManager.stackFromEnd = query.isNotEmpty()
        scrubber.available = sectionPositions.keys.toSet()
        updateScrubberVisibility()
        refreshList()
        if (query.isNotEmpty()) list.scrollToPosition((rows.size - 1).coerceAtLeast(0))
    }

    /** Where a jumped-to letter's heading lands: a third of the way down the screen. */
    private val jumpLine: Float get() = height / 3f

    /**
     * Jumps to [letter], with its heading a third of the way down the screen, so the apps land
     * within thumb reach rather than at the top. Near the ends the list can't move that far and
     * simply stops at the edge. While the finger stays on the letters, the other apps grey out.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun jumpTo(letter: Char, fingerY: Float) {
        val pos = sectionPositions[letter] ?: return
        val headerHalf = dp(20f)
        val offset = (jumpLine - list.top - list.paddingTop - headerHalf).toInt().coerceAtLeast(0)
        layoutManager.scrollToPositionWithOffset(pos, offset)
        scrubber.scrolledTo = letter
        setFocusLetter(letter)
    }

    /** Lights the letter of the section at the jump line as the list scrolls. */
    private fun updateScrolledLetter() {
        if (sectionPositions.isEmpty()) return
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first < 0) return
        var i = first.coerceAtMost(rows.size - 1)
        // The row at the jump line, where a jump puts its letter.
        list.findChildViewUnder(list.width / 2f, jumpLine - list.top)?.let { v ->
            list.getChildAdapterPosition(v).takeIf { it >= 0 }?.let { i = it }
        }
        while (i >= 0 && rows[i] !is Row.Header) i--
        scrubber.scrolledTo = (rows.getOrNull(i) as? Row.Header)?.letter
    }

    private fun updateScrubberVisibility() {
        val query = search.text?.toString()?.trim().orEmpty()
        scrubber.visibility = if (query.isEmpty() && imeLift == 0) VISIBLE else GONE
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshList() = adapter.notifyDataSetChanged()

    // ---- Open / close -------------------------------------------------------------------

    /** Slides from Start to the list, frosting the wallpaper as it comes in. */
    fun open() {
        if (isOpen) return
        isOpen = true
        settleRows()
        visibility = VISIBLE
        list.scrollToPosition(0)
        // The keyboard may have closed while we were away: measure again for how things are now.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootWindowInsets?.let { applyIme(it, relayout = true) }
        } else {
            imeLift = 0
            layoutForKeyboard(relayout = true)
        }
        post { updateScrolledLetter() }
        animatePan(1f)
        postDelayed({ if (isOpen) launcher.startView?.offerTip(app.lawnchair.metro.start.PaneTips.Tip.LETTERS) }, 700)
    }

    fun close(animate: Boolean) {
        if (!isOpen) return
        isOpen = false
        // Left for Start while the rows were swung away (opened an app, then went home): put
        // them back, so the list isn't half-missing next time.
        settleRows()
        // Re-sort for next time while the list is out of sight, so rows never move under you.
        postDelayed({ if (!isOpen) refreshUsage() }, 600)
        panel?.close(animate = false)
        hideKeyboard()
        search.setText("")
        if (animate) {
            animatePan(0f)
        } else {
            panAnim?.cancel()
            setPan(0f)
        }
    }

    private fun animatePan(to: Float) {
        panAnim?.cancel()
        panAnim = ValueAnimator.ofFloat(pan, to).apply {
            duration = PAN_MS
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { setPan(it.animatedValue as Float) }
            start()
        }
    }

    /** Start and the list are one surface: the list slides in exactly as Start slides out. */
    private fun setPan(p: Float) {
        pan = p
        val w = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
        translationX = w * (1f - p)
        visibility = if (p <= 0f) GONE else VISIBLE
        startView?.setPanProgress(p)
    }

    // ---- Turnstile ------------------------------------------------------------------------

    private var turnedAway = false

    private fun visibleRows(): List<View> = (0 until list.childCount).map { list.getChildAt(it) }

    /** Puts every row back at rest (no swing), e.g. when the list was left while turned away. */
    private fun settleRows() {
        if (!turnedAway) return
        turnedAway = false
        val rows = visibleRows()
        app.lawnchair.metro.motion.Turnstile.reset(rows)
        rows.forEach { it.scaleX = 1f; it.scaleY = 1f }
        list.invalidate()
    }

    /** Called when the launcher comes back from an app: rows swing back in. */
    fun playReturn() {
        if (!isOpen || !turnedAway) return
        turnedAway = false
        val rows = visibleRows()
        Turnstile.reset(rows)
        rows.forEach { it.scaleX = 1f; it.scaleY = 1f }
        Turnstile.into(rows) { list.invalidate() }
    }

    private fun hideKeyboard() {
        search.clearFocus()
        launcher.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    /** True while search is in use (typing, or text still in the field): back works then. */
    val isSearchActive: Boolean get() = isOpen && (search.hasFocus() || isSearching())

    /**
     * Back while searching: the first back hides the keyboard (your text stays), the next one
     * clears the search. After that back is off again, as on the rest of the home screen.
     */
    fun handleSearchBack(): Boolean {
        if (!isOpen) return false
        if (search.hasFocus()) {
            hideKeyboard()
            updateBack()
            return true
        }
        if (isSearching()) {
            search.setText("")
            updateBack()
            return true
        }
        return false
    }

    /** Lets the system back gesture through only while there's something for it to do. */
    private fun updateBack() {
        launcher.setMetroBackEnabled(isSearchActive || panel != null)
    }

    // ---- Left-edge scrubbing: slide up or down along the left edge to drive the scrubber ----

    private val edgeSlop = android.view.ViewConfiguration.get(launcher).scaledTouchSlop
    private var edgeCandidate = false
    private var edgeScrubbing = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f

    /**
     * A vertical slide starting at the left edge (lower two thirds) drives the right-hand
     * alphabet scrubber, which bulges and previews exactly as if touched. Taps and sideways
     * swipes from the edge behave as usual.
     */
    private fun handleLeftEdge(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeScrubbing = false
                edgeCandidate = panel == null && scrubber.visibility == VISIBLE && ev.x < dp(24f) && ev.y > height / 3f
                edgeDownX = ev.x
                edgeDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> if (edgeCandidate && !edgeScrubbing) {
                val dx = abs(ev.x - edgeDownX)
                val dy = abs(ev.y - edgeDownY)
                // Decided once, at the first real movement: clearly vertical scrubs, anything
                // else is a normal scroll (or swipe) for the rest of this touch.
                if (dy > edgeSlop && dy > dx * 1.5f) {
                    edgeScrubbing = true
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    forwardToScrubber(ev, MotionEvent.ACTION_DOWN)
                } else if (dx > edgeSlop || dy > edgeSlop) {
                    edgeCandidate = false
                }
            }
        }
        if (!edgeScrubbing) return false
        if (ev.actionMasked == MotionEvent.ACTION_MOVE || ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            forwardToScrubber(ev, ev.actionMasked)
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            edgeScrubbing = false
            edgeCandidate = false
        }
        return true
    }

    private fun forwardToScrubber(ev: MotionEvent, action: Int) {
        val e = MotionEvent.obtain(ev)
        e.action = action
        e.setLocation(scrubber.width - dp(15f), ev.y - scrubber.top)
        scrubber.dispatchTouchEvent(e)
        e.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (handleLeftEdge(ev)) return true
        // Swipe right-to-left on an app with notifications opens its panel (mirroring Start);
        // left-to-right still closes the list.
        if ((panel == null || panelSwipe.active) && panelSwipe.onTouch(ev)) return true
        if (panel != null) return super.dispatchTouchEvent(ev)
        if (swipeDetector.onTouchEvent(ev)) {
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---- Notification panel -------------------------------------------------------------

    private var panel: app.lawnchair.metro.notify.NotificationPanel? = null
    private val panelSwipe = app.lawnchair.metro.notify.PanelSwipe(this, find = { x, y -> panelTargetAt(x, y) }, create = { openPanel(it) }, direction = -1)

    val isPanelOpen: Boolean get() = panel != null

    fun closePanel(): Boolean {
        val p = panel ?: return false
        p.close(animate = true)
        return true
    }

    private fun panelTargetAt(x: Float, y: Float): app.lawnchair.metro.notify.NotificationPanel.Target? {
        if (!isOpen) return null
        // The right edge belongs to the alphabet scrubber.
        if (scrubber.visibility == VISIBLE && x > width - dp(44f)) return null
        val row = list.findChildViewUnder(x - list.left, y - list.top) as? AppRowView ?: return null
        val appInfo = row.appInfo ?: return null
        val pkg = appInfo.componentName?.packageName ?: return null
        if (live[pkg]?.isMusic == true || !LiveTileData.hasPanelContent(pkg)) return null
        val l = list.left + row.left
        val t = list.top + row.top + row.translationY.toInt()
        return app.lawnchair.metro.notify.NotificationPanel.Target(
            pkg, appInfo.title ?: pkg, row.brandColor, Rect(l, t, l + row.width, t + row.height), row,
        )
    }

    private fun openPanel(target: app.lawnchair.metro.notify.NotificationPanel.Target): app.lawnchair.metro.notify.NotificationPanel {
        panel?.close(animate = false)
        hideKeyboard()
        val p = app.lawnchair.metro.notify.NotificationPanel(launcher, target, panelCallbacks, mirrored = true)
        addView(p, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel = p
        launcher.setMetroBackEnabled(true)
        return p
    }

    private val panelCallbacks = object : app.lawnchair.metro.notify.NotificationPanel.Callbacks {
        override fun onClosed(panel: app.lawnchair.metro.notify.NotificationPanel, emptied: Boolean) {
            if (this@AppListView.panel === panel) {
                this@AppListView.panel = null
                launcher.setMetroBackEnabled(false)
            }
        }

        override fun targetAt(x: Float, y: Float) = panelTargetAt(x, y)

        override fun switchTo(target: app.lawnchair.metro.notify.NotificationPanel.Target) {
            openPanel(target).animateOpen()
        }

        override fun insets(): Rect = Rect(0, systemInsets.top, 0, navInset)
    }

    // ---- Actions ------------------------------------------------------------------------

    private fun launch(info: AppInfo, view: View) {
        hideKeyboard()
        if (!AppOpen.turnstile(launcher) || view === search) {
            startApp(info, view)
            return
        }
        turnedAway = true
        app.lawnchair.metro.motion.Turnstile.out(visibleRows(), view, onFrame = { list.invalidate() }, onLaunch = {
            startApp(info, view)
            postDelayed({ if (hasWindowFocus()) playReturn() }, 1200)
        })
    }

    private fun startApp(info: AppInfo, view: View) {
        val launcherApps = launcher.getSystemService(LauncherApps::class.java) ?: return
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        val options = AppOpen.options(launcher, view)
        runCatching {
            launcherApps.startMainActivity(info.componentName, info.user, bounds, options)
            info.componentName?.packageName?.let { app.lawnchair.metro.data.MetroUsage.recordLaunch(launcher, it) }
        }
            .onFailure { Toast.makeText(launcher, "Couldn't open ${info.title}", Toast.LENGTH_SHORT).show() }
    }

    private fun showAppMenu(info: AppInfo, anchor: View) {
        val menu = PopupMenu(launcher, anchor, Gravity.START)
        menu.menu.add(0, 1, 0, "pin to start")
        // App shortcuts ("New chat", "Incognito tab"…) can be pinned as their own tiles.
        val shortcuts = info.componentName?.packageName?.let {
            app.lawnchair.metro.data.MetroShortcuts.forApp(launcher, it)
        }.orEmpty()
        if (shortcuts.isNotEmpty()) {
            val sub = menu.menu.addSubMenu(0, 4, 1, "pin shortcut to start")
            shortcuts.forEachIndexed { i, sc ->
                sub.add(GROUP_SHORTCUT, i, i, sc.shortLabel ?: sc.longLabel ?: sc.id)
            }
        }
        // The app's widgets can go on Start as tiles of their own.
        val widgets = info.componentName?.packageName?.let { app.lawnchair.metro.widgets.MetroWidgets.providers(launcher, it) }.orEmpty()
        if (widgets.isNotEmpty()) menu.menu.add(0, 5, 1, "pin widget to start")
        menu.menu.add(0, 2, 2, "app info")
        menu.menu.add(0, 3, 3, "uninstall")
        menu.showPane(anchor, Gravity.START) { item ->
            val pkg = info.componentName?.packageName
            if (item.itemId == 5) {
                app.lawnchair.metro.data.LayoutLock.guard(launcher) {
                    val add = { w: android.appwidget.AppWidgetProviderInfo ->
                        close(animate = true)
                        app.lawnchair.metro.widgets.MetroWidgets.add(launcher, w)
                    }
                    if (widgets.size == 1) add(widgets[0]) else app.lawnchair.metro.widgets.WidgetPicker.show(launcher, widgets) { add(it) }
                }
                return@showPane true
            }
            if (item.groupId == GROUP_SHORTCUT) {
                shortcuts.getOrNull(item.itemId)?.let {
                    app.lawnchair.metro.data.LayoutLock.guard(launcher) {
                        app.lawnchair.metro.data.MetroShortcuts.pinToStart(launcher, it)
                        close(animate = true)
                    }
                }
                return@showPane true
            }
            when (item.itemId) {
                1 -> {
                    val cn = info.componentName ?: return@showPane true
                    if (MetroTileStore.get(launcher).isPinned(cn)) {
                        Toast.makeText(launcher, "Already on Start", Toast.LENGTH_SHORT).show()
                    } else {
                        app.lawnchair.metro.data.LayoutLock.guard(launcher) {
                            MetroTileStore.get(launcher).pin(cn)
                            close(animate = true)
                        }
                    }
                }
                2 -> launcher.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                3 -> runCatching {
                    launcher.startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure {
                    // Some phones refuse; the app's info page has its own uninstall button.
                    launcher.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            true
        }
    }

    /** One-line notification snippet for the list, e.g. "John: See you at 5!". */
    private fun snippetFor(pkg: String?, from: Map<String, LiveInfo> = live): CharSequence? {
        val info = from[pkg ?: return null] ?: return null
        if (info.isMusic) {
            val title = info.title ?: return null
            return if (info.text != null) "♪ $title · ${info.text}" else "♪ $title"
        }
        val item = info.items.firstOrNull()
        val title = item?.title ?: info.title
        val text = item?.text ?: info.text
        return when {
            title != null && text != null -> "$title: $text"
            else -> text ?: title
        }
    }

    // ---- Rows ---------------------------------------------------------------------------

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = if (viewType == 0) HeaderView(launcher) else AppRowView(launcher)
            val h = if (viewType == 0) dp(58f) else dp(68f)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h.toInt())
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // A recycled row may still be turned away from the turnstile.
            if (!turnedAway) app.lawnchair.metro.motion.Turnstile.reset(listOf(holder.itemView))
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as HeaderView).letter = row.letter
                is Row.App -> (holder.itemView as AppRowView).apply {
                    bind(row.info, snippetFor(row.info.componentName?.packageName))
                    // Rows under the sticky letter are hidden: a tap there isn't on them.
                    setOnClickListener { if (!touchedSticky()) launch(row.info, this) }
                    setOnLongClickListener {
                        if (touchedSticky()) return@setOnLongClickListener true
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showAppMenu(row.info, this)
                        true
                    }
                }
            }
        }
    }

    /**
     * Window mode: paints the list black except for each app's square, which becomes a window
     * onto the wallpaper, exactly like the tiles on Start. Holes follow the rows' transforms,
     * so they swing with the turnstile and sink when pressed.
     */
    private inner class WindowMask : RecyclerView.ItemDecoration() {
        private val hole = android.graphics.Path()
        private val tmp = android.graphics.Path()
        private val visible = android.graphics.Path()
        private val rect = RectF()

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            if (!windowMode) return
            hole.rewind()
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i) as? AppRowView ?: continue
                child.squareRect(rect)
                tmp.rewind()
                tmp.addRect(rect, android.graphics.Path.Direction.CW)
                if (!child.matrix.isIdentity) tmp.transform(child.matrix)
                tmp.offset(child.left.toFloat(), child.top.toFloat())
                hole.addPath(tmp)
            }
            // Rows under the sticky letter are hidden, so their windows are closed too.
            if (stickyLetter != null) {
                visible.rewind()
                visible.addRect(0f, stickyBottom, parent.width.toFloat(), 100_000f, android.graphics.Path.Direction.CW)
                hole.op(visible, android.graphics.Path.Op.INTERSECT)
            }
            val save = c.save()
            c.clipOutPath(hole)
            c.drawColor(Color.BLACK)
            c.restoreToCount(save)
        }
    }

    /** 8.1 letter header: outlined accent square with the lowercase letter in its corner. */
    private inner class HeaderView(context: Context) : View(context) {
        var letter: Char = 'A'
            set(value) {
                field = value
                contentDescription = value.toString()
                invalidate()
            }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = app.lawnchair.metro.theme.PaneFonts.light
            textSize = sp(22f)
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            val s = dp(46f)
            val left = dp(16f)
            val top = (height - s) / 2f
            stroke.color = MetroTheme.accent(context)
            val inset = stroke.strokeWidth / 2f
            canvas.drawRect(left + inset, top + inset, left + s - inset, top + s - inset, stroke)
            canvas.drawText(letter.lowercaseChar().toString(), left + dp(7f), top + s - dp(6f) - text.descent(), text)
        }
    }

    /**
     * App row: a soft glow in the app's brand colour, its accent square and glyph, its name,
     * and a muted notification snippet when it has one.
     */
    private inner class AppRowView(context: Context) : View(context) {
        private var info: AppInfo? = null
        val appInfo: AppInfo? get() = info
        val brandColor: Int get() = brand
        private var snippet: CharSequence? = null
        private var icon: Bitmap? = null
        private var mono = false
        private var loadedIcon: MetroIcons.Icon? = null
        private var brand = 0
        private val square = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = app.lawnchair.metro.theme.PaneFonts.regular
            textSize = sp(17f)
            color = Color.WHITE
        }
        private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = app.lawnchair.metro.theme.PaneFonts.regular
            textSize = sp(13f)
            color = 0x99FFFFFF.toInt()
        }
        private val r = RectF()
        private val iconRect = RectF()

        init {
            isClickable = true
            isLongClickable = true
        }

        fun bind(app: AppInfo, snippet: CharSequence?) {
            val changed = info !== app
            info = app
            this.snippet = snippet
            contentDescription = if (snippet != null) "${app.title}, $snippet" else app.title
            if (changed) {
                icon = null
                loadedIcon = null
                brand = 0
                val cn = app.componentName
                if (cn != null) {
                    val apply = { loaded: MetroIcons.Icon ->
                        if (info === app) {
                            loadedIcon = loaded
                            icon = loaded.bitmap
                            mono = loaded.monochrome
                            brand = loaded.brandColor
                            invalidate()
                        }
                    }
                    MetroIcons.get(context, cn, app.user) { apply(it) }?.let { apply(it) }
                }
            }
            invalidate()
        }

        /** The app's square, in this row's coordinates. */
        fun squareRect(out: RectF) {
            val s = dp(46f)
            val left = dp(16f)
            val top = (height - s) / 2f
            out.set(left, top, left + s, top + s)
        }

        override fun onDraw(canvas: Canvas) {
            val app = info ?: return
            squareRect(r)
            val s = r.width()

            // A faint aura in the app's brand colour around its square.
            if (brand != 0) {
                val radius = dp(50f)
                glow.shader = RadialGradient(
                    r.centerX(), r.centerY(), radius,
                    intArrayOf(ColorUtils.setAlphaComponent(brand, 0x2A), ColorUtils.setAlphaComponent(brand, 0x0A), 0),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(r.centerX(), r.centerY(), radius, glow)
            }

            // Same look as a Start tile: a window onto the wallpaper (window mode), or the tile
            // colour, solid or translucent.
            val key = app.componentName?.flattenToShortString() ?: ""
            val base = MetroTheme.tileColor(context, key, 0)
            square.color = if (windowMode) 0 else base
            if (Color.alpha(square.color) > 0) canvas.drawRect(r, square)
            icon?.let { bmp ->
                val box = s * (if (mono) 0.56f else 0.5f)
                val scale = box / maxOf(bmp.width, bmp.height)
                val iw = bmp.width * scale
                val ih = bmp.height * scale
                iconRect.set(r.centerX() - iw / 2f, r.centerY() - ih / 2f, r.centerX() + iw / 2f, r.centerY() + ih / 2f)
                val glyph = if (windowMode) Color.WHITE else MetroTheme.onTileColor(base)
                iconPaint.colorFilter = loadedIcon?.filterFor(if (windowMode) null else base, glyph)
                    ?: if (mono) PorterDuffColorFilter(glyph, PorterDuff.Mode.SRC_IN) else null
                canvas.drawBitmap(bmp, null, iconRect, iconPaint)
            }

            val x = r.right + dp(16f)
            val avail = width - x - dp(16f)
            // The name carries a faint halo of the brand colour.
            if (brand != 0) {
                name.setShadowLayer(dp(6f), 0f, 0f, ColorUtils.setAlphaComponent(brand, 0x50))
            } else {
                name.clearShadowLayer()
            }
            val title = TextUtils.ellipsize(app.title ?: "", name, avail, TextUtils.TruncateAt.END)
            val nfm = name.fontMetrics
            val snip = snippet
            if (snip == null) {
                canvas.drawText(title, 0, title.length, x, height / 2f - (nfm.ascent + nfm.descent) / 2f, name)
            } else {
                val sfm = sub.fontMetrics
                val nameH = nfm.descent - nfm.ascent
                val subH = sfm.descent - sfm.ascent
                val gap = dp(2f)
                val top0 = (height - nameH - subH - gap) / 2f
                canvas.drawText(title, 0, title.length, x, top0 - nfm.ascent, name)
                val line = TextUtils.ellipsize(snip.toString().replace('\n', ' '), sub, avail, TextUtils.TruncateAt.END)
                canvas.drawText(line, 0, line.length, x, top0 + nameH + gap - sfm.ascent, sub)
            }
        }

        // Windows Phone press feedback: the row sinks slightly.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animate().scaleX(0.97f).scaleY(0.97f).setDuration(90)
                    .setInterpolator(DecelerateInterpolator()).setUpdateListener { list.invalidate() }.start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(120)
                    .setInterpolator(DecelerateInterpolator()).setUpdateListener { list.invalidate() }.start()
            }
            return super.onTouchEvent(event)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private const val PAN_MS = 300L
        private const val GROUP_SHORTCUT = 9
    }
}
