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
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private val list = RecyclerView(launcher)
    private val scrubber = AlphabetScrubber(launcher) { jumpTo(it) }
    private val sectionPositions = HashMap<Char, Int>()

    /** Black behind the status bar, and behind the search bar at the bottom. */
    private val statusStrip = View(launcher).apply { setBackgroundColor(Color.BLACK) }
    private val searchPanel = View(launcher).apply { setBackgroundColor(Color.BLACK) }

    private val background = MetroTheme.background(launcher)
    /** WP 8.1 window mode: each app square is a window onto the same wallpaper as Start. */
    private val windowMode = background == MetroTheme.BG_WINDOW
    private val layoutManager = LinearLayoutManager(launcher)
    private val adapter = Adapter()

    var isOpen = false
        private set

    /** The Start screen, which slides aside while the list is open. */
    var startView: StartView? = null

    private val updateListener = AllAppsStore.OnUpdateListener { refreshApps() }
    private val liveListener: (Map<String, LiveInfo>) -> Unit = {
        live = it
        refreshList()
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
        // the app squares), the wallpaper itself in wallpaper mode.
        setBackgroundColor(if (background == MetroTheme.BG_WALLPAPER) 0x66000000 else Color.TRANSPARENT)
        visibility = GONE
        isClickable = true

        list.layoutManager = layoutManager
        list.adapter = adapter
        list.clipToPadding = true // rows never slide under the status bar
        list.overScrollMode = OVER_SCROLL_NEVER
        list.isVerticalScrollBarEnabled = false
        list.itemAnimator = null
        list.addItemDecoration(WindowMask())
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
        search.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
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
            override fun afterTextChanged(s: Editable?) = rebuildRows()
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

    override fun setInsets(insets: Rect) {
        navInset = insets.bottom
        (statusStrip.layoutParams as LayoutParams).height = insets.top
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
            scrubber.bottomInset = reserved.toFloat()
            (scrubber.layoutParams as LayoutParams).bottomMargin = 0
            list.requestLayout()
            searchPanel.requestLayout()
            scrubber.invalidate()
        }
        search.requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        store.addUpdateListener(updateListener)
        LiveTileData.addListener(launcher, liveListener)
        refreshApps()
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

    private fun refreshApps() {
        allApps = store.apps
            .sortedWith(
                compareBy<AppInfo> { if (sectionOf(it) == '#') 0 else 1 }
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
                .sortedBy { if (it.title?.toString()?.startsWith(query, ignoreCase = true) == true) 1 else 0 }
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
        layoutManager.stackFromEnd = query.isNotEmpty()
        scrubber.available = sectionPositions.keys.toSet()
        scrubber.visibility = if (query.isEmpty()) VISIBLE else GONE
        refreshList()
        if (query.isNotEmpty()) list.scrollToPosition((rows.size - 1).coerceAtLeast(0))
    }

    private fun jumpTo(letter: Char) {
        val pos = sectionPositions[letter] ?: return
        layoutManager.scrollToPositionWithOffset(pos, 0)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshList() = adapter.notifyDataSetChanged()

    // ---- Open / close -------------------------------------------------------------------

    /** Slides from Start to the list, frosting the wallpaper as it comes in. */
    fun open() {
        if (isOpen) return
        isOpen = true
        visibility = VISIBLE
        list.scrollToPosition(0)
        animatePan(1f)
    }

    fun close(animate: Boolean) {
        if (!isOpen) return
        isOpen = false
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

    /** Called when the launcher comes back from an app: rows swing back in. */
    fun playReturn() {
        if (!isOpen) return
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (swipeDetector.onTouchEvent(ev)) {
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---- Actions ------------------------------------------------------------------------

    private fun launch(info: AppInfo, view: View) {
        hideKeyboard()
        startApp(info, view)
    }

    private fun startApp(info: AppInfo, view: View) {
        val launcherApps = launcher.getSystemService(LauncherApps::class.java) ?: return
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        val options = ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.width, view.height).toBundle()
        runCatching {
            launcherApps.startMainActivity(info.componentName, info.user, bounds, options)
            info.componentName?.packageName?.let { app.lawnchair.metro.data.MetroUsage.recordLaunch(launcher, it) }
        }
            .onFailure { Toast.makeText(launcher, "Couldn't open ${info.title}", Toast.LENGTH_SHORT).show() }
    }

    private fun showAppMenu(info: AppInfo, anchor: View) {
        val menu = PopupMenu(launcher, anchor, Gravity.START)
        menu.menu.add(0, 1, 0, "Pin to Start")
        menu.menu.add(0, 2, 1, "App info")
        menu.menu.add(0, 3, 2, "Uninstall")
        menu.setOnMenuItemClickListener { item ->
            val pkg = info.componentName?.packageName
            when (item.itemId) {
                1 -> {
                    val cn = info.componentName ?: return@setOnMenuItemClickListener true
                    if (MetroTileStore.get(launcher).pin(cn)) {
                        close(animate = true)
                    } else {
                        Toast.makeText(launcher, "Already on Start", Toast.LENGTH_SHORT).show()
                    }
                }
                2 -> launcher.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                3 -> launcher.startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            true
        }
        menu.show()
    }

    /** One-line notification snippet for the list, e.g. "John: See you at 5!". */
    private fun snippetFor(pkg: String?): CharSequence? {
        val info = live[pkg ?: return null] ?: return null
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
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as HeaderView).letter = row.letter
                is Row.App -> (holder.itemView as AppRowView).apply {
                    bind(row.info, snippetFor(row.info.componentName?.packageName))
                    setOnClickListener { launch(row.info, this) }
                    setOnLongClickListener {
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
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
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
        private var snippet: CharSequence? = null
        private var icon: Bitmap? = null
        private var mono = false
        private var brand = 0
        private val square = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = sp(17f)
            color = Color.WHITE
        }
        private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
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
                brand = 0
                val cn = app.componentName
                if (cn != null) {
                    val apply = { loaded: MetroIcons.Icon ->
                        if (info === app) {
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
            square.color = when {
                windowMode -> ColorUtils.setAlphaComponent(base, if (MetroTheme.isTranslucent(context)) 0x55 else 0)
                else -> MetroTheme.tileFill(context, key, 0)
            }
            if (Color.alpha(square.color) > 0) canvas.drawRect(r, square)
            icon?.let { bmp ->
                val box = s * (if (mono) 0.56f else 0.5f)
                val scale = box / maxOf(bmp.width, bmp.height)
                val iw = bmp.width * scale
                val ih = bmp.height * scale
                iconRect.set(r.centerX() - iw / 2f, r.centerY() - ih / 2f, r.centerX() + iw / 2f, r.centerY() + ih / 2f)
                val glyph = if (windowMode || MetroTheme.isTranslucent(context)) Color.WHITE else MetroTheme.onTileColor(base)
                iconPaint.colorFilter = if (mono) PorterDuffColorFilter(glyph, PorterDuff.Mode.SRC_IN) else null
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
    }
}
