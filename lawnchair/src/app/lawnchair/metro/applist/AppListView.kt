package app.lawnchair.metro.applist

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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.LawnchairLauncher
import app.lawnchair.metro.data.MetroTileStore
import app.lawnchair.metro.theme.MetroIcons
import app.lawnchair.metro.theme.MetroTheme
import com.android.launcher3.Insettable
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.model.data.AppInfo
import java.text.Collator
import kotlin.math.abs

/**
 * The Metro app list: a modern take on the Windows Phone 8.1 list.
 *
 * - Apps grouped under outlined letter headers, each app on its own accent square with the
 *   same white glyph used on Start.
 * - Niagara-style alphabet scrubber down the right edge, plus the 8.1 jump grid when a letter
 *   header is tapped.
 * - Search at the top filters as you type; Enter opens the first match.
 * - Opens with a horizontal pan from Start; swipe right, Back or Home to return.
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
    private val sectionPositions = HashMap<Char, Int>()

    private val search = EditText(launcher)
    private val list = RecyclerView(launcher)
    private val layoutManager = LinearLayoutManager(launcher)
    private val adapter = Adapter()
    private val scrubber = AlphabetScrubber(launcher) { jumpTo(it) }
    private val jumpGrid = JumpGridView(launcher) { letter ->
        hideJumpGrid()
        letter?.let { jumpTo(it) }
    }

    var isOpen = false
        private set

    /** The Start screen, panned aside while the list is open. */
    var startView: View? = null

    private val updateListener = AllAppsStore.OnUpdateListener { refreshApps() }

    private val swipeDetector = GestureDetector(launcher, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            if (start.x > width - dp(48f)) return false // scrubber column
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
        setBackgroundColor(0xF5000000.toInt())
        visibility = GONE
        isClickable = true

        setupSearch()
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f).toInt(), Gravity.TOP).apply {
            leftMargin = dp(16f).toInt()
            rightMargin = dp(48f).toInt()
        })

        list.layoutManager = layoutManager
        list.adapter = adapter
        list.clipToPadding = false
        list.overScrollMode = OVER_SCROLL_NEVER
        list.isVerticalScrollBarEnabled = false
        list.itemAnimator = null
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(scrubber, LayoutParams(dp(190f).toInt(), LayoutParams.MATCH_PARENT, Gravity.END))
        addView(jumpGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun setupSearch() {
        search.hint = "search apps"
        search.setHintTextColor(0x88FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        search.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
        search.background = GradientDrawable().apply {
            setColor(0x1AFFFFFF)
            setStroke(dp(1f).toInt(), 0x40FFFFFF)
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
                rows.firstNotNullOfOrNull { (it as? Row.App)?.info }?.let { launch(it, list) }
            }
            go
        }
    }

    override fun setInsets(insets: Rect) {
        val top = insets.top + dp(12f).toInt()
        (search.layoutParams as LayoutParams).topMargin = top
        search.requestLayout()
        val listTop = top + dp(48f + 8f).toInt()
        list.setPadding(0, listTop, dp(36f).toInt(), insets.bottom + dp(24f).toInt())
        scrubber.topInset = listTop.toFloat()
        scrubber.bottomInset = insets.bottom + dp(16f)
        scrubber.invalidate()
        jumpGrid.topInset = insets.top.toFloat()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        store.addUpdateListener(updateListener)
        refreshApps()
    }

    override fun onDetachedFromWindow() {
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
            .sortedWith(compareBy<AppInfo> { if (sectionOf(it) == '#') 0 else 1 }
                .thenComparator { a, b -> collator.compare(a.title?.toString() ?: "", b.title?.toString() ?: "") })
        rebuildRows()
    }

    private fun rebuildRows() {
        val query = search.text?.toString()?.trim().orEmpty()
        sectionPositions.clear()
        rows = if (query.isNotEmpty()) {
            allApps.filter { it.title?.toString()?.contains(query, ignoreCase = true) == true }.map { Row.App(it) }
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
        scrubber.available = sectionPositions.keys.toSet()
        scrubber.visibility = if (query.isEmpty()) VISIBLE else GONE
        jumpGrid.available = sectionPositions.keys.toSet()
        refreshList()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshList() = adapter.notifyDataSetChanged()

    private fun jumpTo(letter: Char) {
        val pos = sectionPositions[letter] ?: return
        layoutManager.scrollToPositionWithOffset(pos, 0)
    }

    // ---- Open / close -------------------------------------------------------------------

    /** Pans from Start to the list, as on Windows Phone. */
    fun open() {
        if (isOpen) return
        isOpen = true
        visibility = VISIBLE
        list.scrollToPosition(0)
        translationX = width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        alpha = 1f
        animate().translationX(0f).setDuration(PAN_MS).setInterpolator(DecelerateInterpolator(2f)).start()
        startView?.animate()?.translationX(-width * 0.3f)?.alpha(0f)?.setDuration(PAN_MS)
            ?.setInterpolator(DecelerateInterpolator(2f))?.start()
    }

    fun close(animate: Boolean) {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        search.setText("")
        jumpGrid.visibility = GONE
        if (animate) {
            animate().translationX(width.toFloat()).setDuration(PAN_MS).setInterpolator(DecelerateInterpolator(2f))
                .withEndAction { visibility = GONE }.start()
            startView?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(PAN_MS)
                ?.setInterpolator(DecelerateInterpolator(2f))?.start()
        } else {
            animate().cancel()
            visibility = GONE
            startView?.animate()?.cancel()
            startView?.translationX = 0f
            startView?.alpha = 1f
        }
    }

    /** Back: closes the jump grid first, then the list. */
    fun onBack(): Boolean {
        if (!isOpen) return false
        if (jumpGrid.visibility == VISIBLE) {
            hideJumpGrid()
        } else {
            close(animate = true)
        }
        return true
    }

    private fun showJumpGrid() {
        hideKeyboard()
        jumpGrid.show()
    }

    private fun hideJumpGrid() = jumpGrid.hide()

    private fun hideKeyboard() {
        search.clearFocus()
        launcher.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (jumpGrid.visibility != VISIBLE && swipeDetector.onTouchEvent(ev)) {
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---- Actions ------------------------------------------------------------------------

    private fun launch(info: AppInfo, view: View) {
        val launcherApps = launcher.getSystemService(LauncherApps::class.java) ?: return
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        val options = ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.width, view.height).toBundle()
        runCatching { launcherApps.startMainActivity(info.componentName, info.user, bounds, options) }
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

    // ---- Rows ---------------------------------------------------------------------------

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = if (viewType == 0) HeaderView(launcher) else AppRowView(launcher)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f).toInt())
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as HeaderView).apply {
                    letter = row.letter
                    setOnClickListener { showJumpGrid() }
                }
                is Row.App -> (holder.itemView as AppRowView).apply {
                    bind(row.info)
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
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val s = dp(46f)
            val left = dp(16f)
            val top = (height - s) / 2f
            r.set(left, top, left + s, top + s)
            stroke.color = MetroTheme.accent(context)
            val inset = stroke.strokeWidth / 2f
            canvas.drawRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, stroke)
            canvas.drawText(letter.lowercaseChar().toString(), r.left + dp(7f), r.bottom - dp(6f) - text.descent(), text)
        }
    }

    /** App row: the app's accent square with its glyph, then its name. */
    private inner class AppRowView(context: Context) : View(context) {
        private var info: AppInfo? = null
        private var icon: Bitmap? = null
        private var mono = false
        private val square = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = sp(17f)
            color = Color.WHITE
        }
        private val r = RectF()

        init {
            isClickable = true
            isLongClickable = true
        }

        fun bind(app: AppInfo) {
            info = app
            icon = null
            contentDescription = app.title
            val cn = app.componentName ?: return
            val apply = { loaded: MetroIcons.Icon ->
                if (info === app) {
                    icon = loaded.bitmap
                    mono = loaded.monochrome
                    invalidate()
                }
            }
            MetroIcons.get(context, cn, app.user) { apply(it) }?.let { apply(it) }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val app = info ?: return
            val s = dp(46f)
            val left = dp(16f)
            val top = (height - s) / 2f
            r.set(left, top, left + s, top + s)
            square.color = MetroTheme.tileColor(context, app.componentName?.flattenToShortString() ?: "", 0)
            canvas.drawRect(r, square)
            icon?.let { bmp ->
                val box = s * (if (mono) 0.56f else 0.5f)
                val scale = box / maxOf(bmp.width, bmp.height)
                val iw = bmp.width * scale
                val ih = bmp.height * scale
                val cx = r.centerX()
                val cy = r.centerY()
                iconPaint.colorFilter = if (mono) PorterDuffColorFilter(MetroTheme.onTileColor(square.color), PorterDuff.Mode.SRC_IN) else null
                canvas.drawBitmap(bmp, null, RectF(cx - iw / 2f, cy - ih / 2f, cx + iw / 2f, cy + ih / 2f), iconPaint)
            }
            val x = r.right + dp(16f)
            val title = TextUtils.ellipsize(app.title ?: "", name, width - x - dp(16f), TextUtils.TruncateAt.END)
            val fm = name.fontMetrics
            canvas.drawText(title, 0, title.length, x, height / 2f - (fm.ascent + fm.descent) / 2f, name)
        }

        // Windows Phone press feedback: the row sinks slightly.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            return super.onTouchEvent(event)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private const val PAN_MS = 280L
    }
}
