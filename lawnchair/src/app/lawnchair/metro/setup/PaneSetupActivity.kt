package app.lawnchair.metro.setup

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.lawnchair.metro.data.MetroUsage
import app.lawnchair.metro.info.InfoTiles
import app.lawnchair.metro.info.PhotoPicks
import app.lawnchair.metro.theme.MetroTheme
import app.lawnchair.metro.theme.PaneFonts
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.util.isDefaultLauncher

/**
 * Pane's first-run setup: a short story, one screen per thing Pane needs, each saying what it
 * does and why before Android asks. Everything can be skipped and done later from
 * Settings › Setup & permissions, which can also run this again.
 *
 *  1. welcome
 *  2. make it home       – the default home app
 *  3. live tiles         – notification access
 *  4. info tiles         – calendar, approximate location (weather), photos
 *  5. auto layout        – usage access
 *  6. make it yours      – background, tile colours, columns
 *  7. all set
 */
class PaneSetupActivity : Activity() {

    private enum class Step { WELCOME, HOME, LIVE, INFO, AUTO, STYLE, DONE }

    private lateinit var prefs: PreferenceManager
    private lateinit var root: FrameLayout
    private var page: View? = null
    private var step = Step.WELCOME
    private var insetTop = 0
    private var insetBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getInstance(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (bars.top != insetTop || bars.bottom != insetBottom) {
                insetTop = bars.top
                insetBottom = bars.bottom
                page?.setPadding(dp(28), insetTop + dp(40), dp(28), insetBottom + dp(24))
            }
            insets
        }
        step = savedInstanceState?.getString(KEY_STEP)?.let { runCatching { Step.valueOf(it) }.getOrNull() } ?: Step.WELCOME
        show(step, forward = true, animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STEP, step.name)
    }

    /** Back from Android's prompt or a settings screen: show what changed. */
    override fun onResume() {
        super.onResume()
        if (page != null && step in REFRESHED) show(step, forward = true, animate = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        // A home app was chosen (or not): the screen refreshes in onResume.
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (step == Step.INFO) show(step, forward = true, animate = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (step == Step.WELCOME || step == Step.DONE) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            show(Step.entries[step.ordinal - 1], forward = false)
        }
    }

    // ---- Steps ----------------------------------------------------------------------------------

    private fun show(target: Step, forward: Boolean, animate: Boolean = true) {
        step = target
        val next = when (target) {
            Step.WELCOME -> welcome()
            Step.HOME -> home()
            Step.LIVE -> live()
            Step.INFO -> info()
            Step.AUTO -> auto()
            Step.STYLE -> style()
            Step.DONE -> done()
        }
        next.setPadding(dp(28), insetTop + dp(40), dp(28), insetBottom + dp(24))
        val old = page
        page = next
        root.addView(next, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!animate || old == null) {
            old?.let(root::removeView)
            return
        }
        // Pages swing in from the side they come from, a little like turning a page.
        val dir = if (forward) 1 else -1
        old.animate().translationX(-dir * dp(48).toFloat()).alpha(0f).setDuration(140)
            .withEndAction { root.removeView(old) }.start()
        next.alpha = 0f
        next.translationX = dir * dp(72).toFloat()
        next.animate().translationX(0f).alpha(1f).setStartDelay(90).setDuration(260)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun goNext() = show(Step.entries[step.ordinal + 1], forward = true)

    private fun welcome() = page(
        title = "welcome",
        art = TilesArt(this, lit = TilesArt.ALL, landing = true),
        lead = "a start screen that's alive.",
        body = "Let's set it up. It takes a minute.",
        extra = link("Moving from another phone or build? Restore a backup") {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lawnchair://settings/backup-restore")).setPackage(packageName))
            }
            Unit
        },
        secondary = "skip setup" to { finishSetup() },
        primary = "get started" to { goNext() },
    )

    private fun home(): View {
        val isHome = isDefaultLauncher()
        return page(
            title = "make it home",
            art = TilesArt(this, lit = TilesArt.ALL),
            body = "Pane works as your home screen, so the home button and gesture bring you back to your tiles. " +
                "Your phone will ask which home app to use: choose Pane.",
            status = if (isHome) "Pane is your home app." else null,
            secondary = if (isHome) null else "not now" to { goNext() },
            primary = if (isHome) "next" to { goNext() } else "set as home" to { requestHome() },
        )
    }

    private fun live(): View {
        val on = hasNotificationAccess()
        return page(
            title = "live tiles",
            art = TilesArt(this, lit = setOf(0, 5)),
            body = "Tiles flip to show new messages, what's playing and how many you've missed. " +
                "For that, Pane needs notification access.\n\n" +
                "Pane only reads notifications to show them on your tiles. Nothing leaves your phone.",
            note = if (on || !isSideloaded()) null else
                "If Android says this setting is restricted: open App info, tap the menu (top right), " +
                    "choose \"Allow restricted settings\", then try again.",
            status = if (on) "Notification access is on." else null,
            secondary = if (on) null else "not now" to { goNext() },
            primary = if (on) "next" to { goNext() } else "allow" to { openNotificationAccess() },
        )
    }

    private fun info(): View {
        val needed = infoPermissions()
        val missing = needed.filter { !InfoTiles.hasPermission(this, it) }
        val picks = PhotoPicks.count(this)
        val photosLine = if (PhotoPicks.pickerOnly) {
            "For the Photos tile, pick some favourites. You can add more any time from the tile."
        } else {
            "Photos plays a slideshow of your recent camera shots."
        }
        val granted = buildList {
            if (InfoTiles.hasPermission(this@PaneSetupActivity, Manifest.permission.READ_CALENDAR)) add("calendar")
            if (InfoTiles.hasPermission(this@PaneSetupActivity, Manifest.permission.ACCESS_COARSE_LOCATION)) add("weather")
            if (PhotoPicks.pickerOnly) {
                if (picks > 0) add("$picks photo${if (picks == 1) "" else "s"}")
            } else if (InfoTiles.hasPhotoAccess(this@PaneSetupActivity)) {
                add("photos")
            }
        }
        return page(
            title = "info tiles",
            art = TilesArt(this, lit = setOf(1, 2, 3)),
            body = "Your calendar tile shows what's next, weather shows your area, and the clock shows your next alarm. " +
                "$photosLine\n\nWeather uses your approximate location, only while Pane is open.",
            status = if (granted.isEmpty()) null else "On: ${granted.joinToString(", ")}.",
            extra = if (PhotoPicks.pickerOnly) {
                button(if (picks > 0) "choose different photos" else "choose photos", primary = false) {
                    PhotoPicks.open(this, replace = true)
                }
            } else {
                null
            },
            secondary = if (missing.isEmpty()) null else "not now" to { goNext() },
            primary = if (missing.isEmpty()) "next" to { goNext() } else "allow" to {
                @Suppress("DEPRECATION")
                requestPermissions(missing.toTypedArray(), REQUEST_INFO)
            },
        )
    }

    private fun auto(): View {
        val on = MetroUsage.hasUsageAccess(this)
        return page(
            title = "auto layout",
            art = TilesArt(this, lit = setOf(0, 4, 5)),
            body = "Pane can arrange Start around the apps you use most: hold the arrow at the end of Start.\n\n" +
                "It works better with usage access, which tells Pane how often you open each app. That stays on your phone.",
            status = if (on) "Usage access is on." else null,
            secondary = if (on) null else "not now" to { goNext() },
            primary = if (on) "next" to { goNext() } else "allow" to {
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                Unit
            },
        )
    }

    private fun style(): View {
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bgValues = listOf(MetroTheme.BG_BLACK, MetroTheme.BG_WALLPAPER, MetroTheme.BG_WINDOW)
        options.addView(
            segmented("background", listOf("black", "wallpaper", "through tiles"), bgValues.indexOf(prefs.metroBackground.get()).coerceAtLeast(0)) {
                prefs.metroBackground.set(bgValues[it])
            },
        )
        val colorValues = listOf(MetroTheme.MODE_MONET, MetroTheme.MODE_MONET_TONAL, MetroTheme.MODE_CLASSIC)
        options.addView(
            segmented("tile colours", listOf("accent", "mix", "classic"), colorValues.indexOf(prefs.metroColorMode.get()).coerceAtLeast(0)) {
                prefs.metroColorMode.set(colorValues[it])
            },
        )
        options.addView(
            segmented("columns", listOf("4", "6"), if (prefs.metroShowMoreTiles.get()) 1 else 0) {
                prefs.metroShowMoreTiles.set(it == 1)
            },
        )
        return page(
            title = "make it yours",
            art = null,
            body = "Pick a look to start with. Accent and mix follow your wallpaper's colours. " +
                "There's more in Settings, like a background photo.",
            extra = options,
            primary = "next" to { goNext() },
        )
    }

    private fun done() = page(
        title = "all set",
        art = TilesArt(this, lit = TilesArt.ALL),
        lead = "your Start is ready.",
        body = "Hold a tile to resize, move or recolour it. Swipe left for all your apps.",
        primary = "go to start" to { finishSetup() },
    )

    private fun finishSetup() {
        prefs.paneOnboarded.set(true)
        finish()
    }

    // ---- What each step asks for -------------------------------------------------------------

    private fun requestHome() {
        val rm = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME) }
                .onFailure { openHomeSettings() }
        } else {
            openHomeSettings()
        }
    }

    private fun openHomeSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun hasNotificationAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openNotificationAccess() {
        val listener = ComponentName(this, "com.android.launcher3.notification.NotificationListener")
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
        } else {
            null
        }
        val opened = detail != null && runCatching { startActivity(detail) }.isSuccess
        if (!opened) runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun infoPermissions(): List<String> = listOfNotNull(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        InfoTiles.permissionFor(app.lawnchair.metro.info.InfoKind.PHOTOS),
    )

    /** Installed from outside Google Play: Android 13+ restricts some settings for such apps. */
    private fun isSideloaded(): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
        installer != "com.android.vending"
    }.getOrDefault(true)

    // ---- Building blocks ---------------------------------------------------------------------

    private fun page(
        title: String,
        art: View?,
        body: String,
        lead: String? = null,
        note: String? = null,
        status: String? = null,
        extra: View? = null,
        secondary: Pair<String, () -> Unit>? = null,
        primary: Pair<String, () -> Unit>,
    ): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text("PANE", 14f, PaneFonts.semibold, 0x99FFFFFF.toInt()).apply { letterSpacing = 0.12f })
        column.addView(text(title, 54f, PaneFonts.light, Color.WHITE).apply { includeFontPadding = false }, margins(top = 2))
        art?.let { column.addView(it, margins(top = 28, height = dp(140))) }
        lead?.let { column.addView(text(it, 26f, PaneFonts.light, Color.WHITE), margins(top = 28)) }
        column.addView(text(body, 17f, PaneFonts.regular, 0xD9FFFFFF.toInt()).apply { setLineSpacing(0f, 1.15f) }, margins(top = if (lead != null) 10 else 28))
        status?.let {
            column.addView(text(it, 17f, PaneFonts.semibold, MetroTheme.accent(this).let(::readableAccent)), margins(top = 18))
        }
        note?.let { column.addView(text(it, 14f, PaneFonts.regular, 0x99FFFFFF.toInt()).apply { setLineSpacing(0f, 1.1f) }, margins(top = 16)) }
        extra?.let { column.addView(it, margins(top = 22)) }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (secondary != null) {
            actions.addView(text(secondary.first, 16f, PaneFonts.regular, 0x99FFFFFF.toInt()).apply {
                setPadding(0, dp(12), dp(12), dp(12))
                setOnClickListener { secondary.second() }
            })
        }
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(button(primary.first, primary = true, onClick = primary.second))

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    private fun text(value: String, sp: Float, face: android.graphics.Typeface, color: Int) = TextView(this).apply {
        text = value
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
    }

    private fun link(value: String, onClick: () -> Unit) = text(value, 15f, PaneFonts.regular, 0xB3FFFFFF.toInt()).apply {
        paint.isUnderlineText = true
        setPadding(0, dp(6), 0, dp(6))
        setOnClickListener { onClick() }
    }

    /** Pane's button: a white outline (secondary) or filled white with black text (primary). */
    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        typeface = PaneFonts.semibold
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(11), dp(24), dp(11))
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply {
            if (primary) setColor(Color.WHITE) else setStroke(dp(2), Color.WHITE)
        }
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun segmented(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
        }
        box.addView(text(title, 15f, PaneFonts.regular, 0x99FFFFFF.toInt()))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val accent = MetroTheme.accent(this)
        val chips = options.map { label -> text(label, 16f, PaneFonts.regular, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
        } }
        fun paint(sel: Int) = chips.forEachIndexed { i, c ->
            c.background = GradientDrawable().apply { if (i == sel) setColor(accent) else setStroke(dp(1), 0x66FFFFFF) }
        }
        paint(selected)
        chips.forEachIndexed { i, c ->
            c.setOnClickListener {
                paint(i)
                onPick(i)
            }
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
        box.addView(row, margins(top = 6))
        return box
    }

    /** Dark accents are hard to read as text on black; lift them toward white. */
    private fun readableAccent(color: Int): Int {
        val lum = androidx.core.graphics.ColorUtils.calculateLuminance(color)
        return if (lum < 0.18) androidx.core.graphics.ColorUtils.blendARGB(color, Color.WHITE, 0.45f) else color
    }

    private fun margins(top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val KEY_STEP = "step"
        private const val REQUEST_HOME = 7501
        private const val REQUEST_INFO = 7502
        private val REFRESHED = setOf(Step.HOME, Step.LIVE, Step.INFO, Step.AUTO)

        @JvmStatic
        fun start(context: Context) {
            // Single top: if Start is recreated while setup is open (a look changed, or Pane
            // just became the home app), the open setup carries on instead of starting over.
            context.startActivity(
                Intent(context, PaneSetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }
}

/**
 * A small Start screen drawn as plain tiles: one medium, four small, one wide. [lit] tiles take
 * the accent colour; the rest are dark. With [landing], the tiles flip in one after another,
 * as Start does when you return to it.
 */
private class TilesArt(context: Context, private val lit: Set<Int>, landing: Boolean = false) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = MetroTheme.accent(context)
    private val rect = RectF()
    private var progress = if (landing) 0f else 1f

    init {
        if (landing) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                startDelay = 250
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val gap = height * 0.035f
        val cell = (height - gap) / 3f
        // Columns are as wide as rows are tall, so tiles stay square.
        val x = { col: Int -> col * (cell + gap) }
        val y = { row: Int -> row * (cell + gap) }
        // (col, row, cols, rows): medium, small ×4, wide.
        val tiles = listOf(
            intArrayOf(0, 0, 2, 2),
            intArrayOf(2, 0, 1, 1),
            intArrayOf(3, 0, 1, 1),
            intArrayOf(2, 1, 1, 1),
            intArrayOf(3, 1, 1, 1),
            intArrayOf(0, 2, 4, 1),
        )
        tiles.forEachIndexed { i, t ->
            // Each tile flips in a little after the one before.
            val p = ((progress - i * 0.09f) / 0.5f).coerceIn(0f, 1f)
            if (p <= 0f) return@forEachIndexed
            val l = x(t[0])
            val top = y(t[1])
            val r = l + t[2] * cell + (t[2] - 1) * gap
            val b = top + t[3] * cell + (t[3] - 1) * gap
            val cy = (top + b) / 2f
            val h = (b - top) / 2f * p
            rect.set(l, cy - h, r, cy + h)
            paint.color = if (i in lit) accent else 0xFF262626.toInt()
            paint.alpha = (255 * (0.4f + 0.6f * p)).toInt()
            canvas.drawRect(rect, paint)
        }
    }

    companion object {
        val ALL = setOf(0, 1, 2, 3, 4, 5)
    }
}
