package app.lawnchair.metro.start

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.lawnchair.metro.data.MetroTileStore
import app.lawnchair.metro.theme.BackgroundDim
import app.lawnchair.metro.theme.DepthLayers
import app.lawnchair.metro.theme.LayeredImage
import app.lawnchair.metro.theme.MetroIcons
import app.lawnchair.metro.theme.MetroTheme
import app.lawnchair.preferences.PreferenceManager
import java.text.Collator
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Full-screen preview shown after picking a Start background photo.
 *
 * Your real Start tiles sit over the photo. Drag and pinch to position it; the dim updates live
 * as you move it, and you can try the dim strength and solid or 8.1 window tiles in place. The
 * "App list" view slides across as on the phone, showing how much of the photo's extra width
 * the sideways drift uses. Apply saves exactly the part you chose; Cancel keeps the current
 * background.
 */
class BackgroundPreviewActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: PreferenceManager

    private lateinit var root: TouchRoot
    private lateinit var photo: PhotoView
    private lateinit var dim: View
    private lateinit var startLayer: FrameLayout
    private lateinit var grid: TileGridView
    private lateinit var list: ListPeekView
    private lateinit var controls: LinearLayout
    private lateinit var hint: TextView

    private var windowStyle = false
    private var level = 1
    private var showList = false
    private var pan = 0f
    private var luminance = 0f
    private var topInset = 0
    private var applying = false
    private var setWallpaper = true
    @Volatile private var wallpaperFailed = false
    @Volatile private var depthSaved = false

    // ---- Depth ----
    private var depthOn = true
    /** Depth of the loaded photo (worked out once, on the worker thread). */
    @Volatile private var depthMap: DepthLayers.DepthMap? = null
    private var depthBusy = false
    /** Layers for showing the photo here (built on a smaller copy). */
    private var previewLayers: LayeredImage? = null
    /** The generated gradient with its layers, at full size. */
    @Volatile private var gradientLayers: LayeredImage? = null
    private lateinit var scroll: android.widget.ScrollView
    private var scrollMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getInstance(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        windowStyle = MetroTheme.background(this) == MetroTheme.BG_WINDOW
        level = prefs.metroLegibility.get()
        depthOn = prefs.metroBackgroundDepth.get()

        root = TouchRoot(this)
        root.setBackgroundColor(Color.BLACK)
        photo = PhotoView(this)
        dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        startLayer = FrameLayout(this)
        grid = TileGridView(this)
        list = ListPeekView(this)
        controls = buildControls()
        hint = TextView(this).apply {
            text = "Drag and pinch to position the photo"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()) }
        }

        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(photo, FrameLayout.LayoutParams(mp, mp))
        root.addView(dim, FrameLayout.LayoutParams(mp, mp))
        // The tiles scroll (in "scroll tiles" mode), with the background drifting as on Start.
        scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(grid, ViewGroup.LayoutParams(mp, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnScrollChangeListener { _, _, y, _, _ ->
                val range = (grid.height - height).coerceAtLeast(1)
                photo.scroll = (y.toFloat() / range).coerceIn(0f, 1f)
            }
        }
        startLayer.addView(scroll, FrameLayout.LayoutParams(mp, mp))
        root.addView(startLayer, FrameLayout.LayoutParams(mp, mp))
        root.addView(list, FrameLayout.LayoutParams(mp, mp))
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(controls, FrameLayout.LayoutParams(mp, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.photo = photo
        root.controls = controls
        root.toPhoto = { !scrollMode }
        // Tiles can scroll clear of the controls at the bottom.
        controls.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (grid.bottomInset != bottom - top) {
                grid.bottomInset = bottom - top
                grid.requestLayout()
            }
        }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            topInset = bars.top
            grid.topPadding = bars.top
            grid.requestLayout()
            list.topInset = bars.top.toFloat()
            controls.setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), bars.bottom + dp(12f).toInt())
            insets
        }

        setupTiles()
        photo.onChanged = { scheduleMeasure() }
        list.post { setPan(0f) }
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(400).start() }, 2600)

        if (gradientMode) {
            hint.text = "Shuffle for a new gradient"
            shuffleGradient()
            return
        }
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        loadPhoto(uri)
    }

    /** Generated gradient instead of a photo (Background › Generate gradient). */
    private val gradientMode: Boolean get() = intent.getBooleanExtra(EXTRA_GRADIENT, false)

    /** Makes a new random gradient exactly the size Start's background needs. */
    private fun shuffleGradient() {
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds
        } else {
            android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        val w = (bounds.width() * (1 + ParallaxBackgroundView.TRAVEL_X)).toInt()
        val h = (bounds.height() * (1 + ParallaxBackgroundView.TRAVEL_Y)).toInt()
        val seed = System.nanoTime()
        worker.execute {
            val layered = runCatching { app.lawnchair.metro.theme.GradientGen.random(w, h, seed) }.getOrNull()
            val flat = layered?.flatten()
            gradientLayers = layered
            main.post {
                if (layered != null && flat != null) {
                    photo.setImage(flat)
                    photo.layered = if (depthOn) layered else null
                    scheduleMeasure()
                }
            }
        }
    }

    private fun setupTiles() {
        grid.columns = MetroTheme.columns(this)
        grid.windowMode = windowStyle
        val tiles = MetroTileStore.get(this).load()
        val views = tiles.filter { it.kind != app.lawnchair.metro.data.MetroTile.Kind.WIDGET }.map { t ->
            TileView(this, t).apply {
                windowMode = windowStyle
                isClickable = false
                isLongClickable = false
                onIconLoaded = { scheduleBrands() }
            }
        }
        views.forEach { grid.addView(it) }
        grid.setOrder(views)
        grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleBrands() }
        startLayer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> grid.viewportHeight = bottom - top }
    }

    private val brandRunnable = Runnable {
        if (BrandTiles.assign(grid, startLayer.height)) grid.invalidate()
    }

    private fun scheduleBrands() {
        main.removeCallbacks(brandRunnable)
        main.postDelayed(brandRunnable, 120)
    }

    private fun loadPhoto(uri: Uri) {
        val dm = resources.displayMetrics
        val targetW = (dm.widthPixels * (1 + ParallaxBackgroundView.TRAVEL_X) * 2).toInt()
        val targetH = (dm.heightPixels * (1 + ParallaxBackgroundView.TRAVEL_Y) * 2).toInt()
        worker.execute {
            val bmp = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    var sample = 1
                    while (info.size.width / (sample * 2) >= targetW / 2 && info.size.height / (sample * 2) >= targetH / 2) sample *= 2
                    decoder.setTargetSampleSize(sample)
                }
            }.getOrNull()
            main.post {
                if (bmp == null) {
                    Toast.makeText(this, "Couldn't open that photo", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    photo.setImage(bmp)
                    scheduleMeasure()
                    if (depthOn) showDepth()
                }
            }
        }
    }

    // ---- Depth -----------------------------------------------------------------------------

    /** Shows the photo's depth layers, working them out first if needed (a few seconds, once). */
    private fun showDepth() {
        if (gradientMode) {
            photo.layered = gradientLayers
            return
        }
        previewLayers?.let {
            photo.layered = it
            return
        }
        val src = photo.bitmap ?: return
        if (depthBusy) return
        depthBusy = true
        hint.animate().cancel()
        hint.text = "Preparing depth…"
        hint.alpha = 1f
        worker.execute {
            val map = depthMap ?: DepthLayers.estimate(this, src)
            depthMap = map
            val layers = map?.let { m ->
                runCatching {
                    // A copy about the size of the screen is plenty for the preview.
                    val k = (2400f / maxOf(src.width, src.height)).coerceAtMost(1f)
                    val small = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
                    DepthLayers.build(small, m)
                }.getOrNull()
            }
            main.post {
                depthBusy = false
                previewLayers = layers
                if (layers == null) {
                    hint.text = "Depth isn't available for this photo"
                } else {
                    if (depthOn) photo.layered = layers
                    hint.text = if (layers.layers.isEmpty()) "This photo is flat: no depth layers" else "Depth ready: scroll the tiles to see it"
                }
                hint.animate().alpha(0f).setStartDelay(2200).setDuration(400).start()
            }
        }
    }

    // ---- Dim -------------------------------------------------------------------------------

    private val measureRunnable = Runnable { measureNow() }

    private fun scheduleMeasure() {
        main.removeCallbacks(measureRunnable)
        main.postDelayed(measureRunnable, 120)
    }

    /** Measures the part of the photo that will actually be used, then sets the dim. */
    private fun measureNow() {
        val src = photo.bitmap ?: return
        val r = photo.cropRect() ?: return
        val crop = runCatching {
            Bitmap.createBitmap(src, r.left.toInt(), r.top.toInt(), r.width().toInt().coerceAtLeast(1), r.height().toInt().coerceAtLeast(1))
        }.getOrNull() ?: return
        BackgroundDim.measure(this, crop) { lum ->
            luminance = lum
            applyDim()
        }
    }

    private fun applyDim() {
        dim.animate().alpha(BackgroundDim.dimFor(luminance, level)).setDuration(200).start()
    }

    // ---- Start / app list pan --------------------------------------------------------------

    private fun animatePan(to: Float) {
        android.animation.ValueAnimator.ofFloat(pan, to).apply {
            duration = 300
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { setPan(it.animatedValue as Float) }
            start()
        }
    }

    private fun setPan(p: Float) {
        pan = p
        val w = root.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        startLayer.translationX = -w * p
        list.translationX = w * (1f - p)
        list.visibility = if (p <= 0f) View.INVISIBLE else View.VISIBLE
        photo.pan = p
    }

    // ---- Controls --------------------------------------------------------------------------

    private fun buildControls(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6000000.toInt())
            isClickable = true
        }
        box.addView(segmented("tiles", listOf("solid", "8.1 window"), if (windowStyle) 1 else 0) { i ->
            windowStyle = i == 1
            grid.windowMode = windowStyle
            grid.tiles.forEach { (it as? TileView)?.apply { windowMode = windowStyle; invalidate() } }
            list.windowMode = windowStyle
            grid.invalidate()
        })
        box.addView(segmented("dim", listOf("off", "auto", "stronger"), level.coerceIn(0, 2)) { i ->
            level = i
            applyDim()
        })
        setWallpaper = prefs.metroSetSystemWallpaper.get()
        box.addView(segmented("phone", listOf("keep wallpaper", "set home screen"), if (setWallpaper) 1 else 0) { i ->
            setWallpaper = i == 1
            prefs.metroSetSystemWallpaper.set(setWallpaper)
        })
        box.addView(segmented("view", listOf("start", "app list"), 0) { i ->
            showList = i == 1
            animatePan(if (showList) 1f else 0f)
        })
        box.addView(segmented("depth", listOf("off", "on"), if (depthOn) 1 else 0) { i ->
            depthOn = i == 1
            if (depthOn) showDepth() else photo.layered = null
        })
        box.addView(segmented("touch", listOf("move photo", "scroll tiles"), 0) { i ->
            scrollMode = i == 1
            if (!scrollMode) scroll.smoothScrollTo(0, 0)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8f).toInt(), 0, 0)
        }
        if (gradientMode) {
            actions.addView(actionButton("shuffle", primary = false) { shuffleGradient() })
        }
        actions.addView(actionButton("cancel", primary = false) { finish() })
        actions.addView(actionButton("apply", primary = true) { apply() })
        box.addView(actions)
        return box
    }

    private fun segmented(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(dp(56f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        val chips = options.map { label ->
            TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(12f).toInt(), dp(7f).toInt(), dp(12f).toInt(), dp(7f).toInt())
            }
        }
        val accent = MetroTheme.accent(this)
        fun paint(sel: Int) = chips.forEachIndexed { i, c ->
            c.setTextColor(Color.WHITE)
            c.background = GradientDrawable().apply {
                if (i == sel) setColor(accent) else setStroke(dp(1f).toInt(), 0x66FFFFFF)
            }
        }
        paint(selected)
        chips.forEachIndexed { i, c ->
            c.setOnClickListener {
                paint(i)
                onPick(i)
            }
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6f).toInt()
            })
        }
        return row
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        setPadding(dp(22f).toInt(), dp(10f).toInt(), dp(22f).toInt(), dp(10f).toInt())
        background = GradientDrawable().apply {
            if (primary) setColor(MetroTheme.accent(context)) else setStroke(dp(1.5f).toInt(), Color.WHITE)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10f).toInt()
        }
    }

    /** Saves the chosen part of the photo and the chosen settings, then returns. */
    private fun apply() {
        val src = photo.bitmap ?: return
        val r = photo.cropRect() ?: return
        val dm = resources.displayMetrics
        val outW = (dm.widthPixels * (1 + ParallaxBackgroundView.TRAVEL_X)).toInt()
        if (applying) return
        applying = true
        worker.execute {
            val ok = runCatching {
                val crop = Bitmap.createBitmap(src, r.left.toInt(), r.top.toInt(), r.width().toInt().coerceAtLeast(1), r.height().toInt().coerceAtLeast(1))
                val scale = (outW.toFloat() / crop.width).coerceAtMost(1f)
                val out = if (scale < 1f) {
                    Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true)
                } else {
                    crop
                }
                // Old layers go first, so a failure below can never leave them with the new photo.
                LayeredImage.clear(this)
                ParallaxBackgroundView.file(this).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, if (gradientMode) 96 else 92, it) }
                // Depth layers for exactly the saved part, at the saved size. If this fails the
                // background is simply saved without depth.
                depthSaved = false
                if (depthOn) runCatching {
                    val layered = if (gradientMode) {
                        gradientLayers?.crop(r, scale)
                    } else {
                        depthMap?.let { m ->
                            DepthLayers.build(out, m, RectF(r.left / src.width, r.top / src.height, r.right / src.width, r.bottom / src.height))
                        }
                    }
                    if (layered != null && layered.layers.isNotEmpty()) {
                        LayeredImage.save(this, layered)
                        depthSaved = true
                    }
                }.onFailure { LayeredImage.clear(this) }
                // The wallpaper is a bonus: if Android refuses it, the background still applies.
                if (setWallpaper) wallpaperFailed = runCatching { setHomeWallpaper(crop) }.isFailure
            }.isSuccess
            main.post {
                if (!ok) {
                    Toast.makeText(this, "Couldn't save the photo", Toast.LENGTH_SHORT).show()
                    applying = false
                    return@post
                }
                if (wallpaperFailed) Toast.makeText(this, "Couldn't set the phone wallpaper", Toast.LENGTH_SHORT).show()
                prefs.metroBackgroundKind.set(if (gradientMode) "gradient" else "photo")
                prefs.metroBackgroundDepth.set(depthOn)
                if (depthOn && !depthSaved && !gradientMode) Toast.makeText(this, "Saved without depth", Toast.LENGTH_SHORT).show()
                prefs.metroLegibility.set(level)
                prefs.metroBackground.set(if (windowStyle) MetroTheme.BG_WINDOW else MetroTheme.BG_WALLPAPER)
                prefs.metroBackgroundPhoto.set(prefs.metroBackgroundPhoto.get() + 1)
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    /**
     * Sets the phone's home screen wallpaper to exactly what Start shows at rest: the
     * screen-sized top-left part of the saved crop (the rest is parallax room), at full screen
     * resolution, with the same dim Start applies. The return-home flash then matches Start.
     */
    private fun setHomeWallpaper(crop: Bitmap) {
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds
        } else {
            android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        val sw = crop.width / (1 + ParallaxBackgroundView.TRAVEL_X)
        val sh = crop.height / (1 + ParallaxBackgroundView.TRAVEL_Y)
        val screenPart = Bitmap.createBitmap(crop, 0, 0, sw.toInt().coerceIn(1, crop.width), sh.toInt().coerceIn(1, crop.height))
        val out = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(screenPart, null, android.graphics.Rect(0, 0, out.width, out.height), Paint(Paint.FILTER_BITMAP_FLAG))
        val dimAlpha = BackgroundDim.dimFor(luminance, level)
        if (dimAlpha > 0f) c.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(Color.BLACK, (dimAlpha * 255).toInt()))
        android.app.WallpaperManager.getInstance(this).setBitmap(out, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // ---- Views -----------------------------------------------------------------------------

    /** Sends every touch outside the controls to the photo, so tiles don't get in the way. */
    private class TouchRoot(context: Context) : FrameLayout(context) {
        var photo: PhotoView? = null
        var controls: View? = null
        /** False while the tiles scroll instead (touches then go to them). */
        var toPhoto: () -> Boolean = { true }
        private var routeToPhoto = false

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val c = controls
                routeToPhoto = (c == null || ev.y < c.top) && toPhoto()
            }
            return if (routeToPhoto) photo?.onTouchEvent(ev) ?: false else super.dispatchTouchEvent(ev)
        }
    }

    /**
     * The photo, positioned by dragging and pinching. The part that will be saved is the screen
     * plus the extra width and height the parallax drift uses; it always stays covered.
     */
    private class PhotoView(context: Context) : View(context) {
        var bitmap: Bitmap? = null
            private set
        private val m = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var scale = 1f
        private var minScale = 1f
        private var tx = 0f
        private var ty = 0f
        var onChanged: (() -> Unit)? = null
        var pan = 0f
            set(value) {
                field = value
                invalidate()
            }

        /** How far the tiles have scrolled (0..1): the background drifts as on Start. */
        var scroll = 0f
            set(value) {
                field = value
                invalidate()
            }

        /** Depth layers to show instead of the flat photo (null = depth off). Their base may be smaller than [bitmap]. */
        var layered: LayeredImage? = null
            set(value) {
                field = value
                invalidate()
            }
        private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val lm = Matrix()
        private val lr = RectF()

        private val regionW get() = width * (1 + ParallaxBackgroundView.TRAVEL_X)
        private val regionH get() = height * (1 + ParallaxBackgroundView.TRAVEL_Y)

        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val bmp = bitmap ?: return false
                val newScale = (scale * d.scaleFactor).coerceIn(minScale, minScale * 4f)
                val f = newScale / scale
                // Zoom around the fingers.
                tx = d.focusX - (d.focusX - tx) * f
                ty = d.focusY - (d.focusY - ty) * f
                scale = newScale
                clamp(bmp)
                return true
            }
        })
        private var lastX = 0f
        private var lastY = 0f
        private var pointer = -1

        fun setImage(bmp: Bitmap) {
            bitmap = bmp
            if (width > 0) fit()
        }

        private fun fit() {
            val bmp = bitmap ?: return
            minScale = max(regionW / bmp.width, regionH / bmp.height)
            scale = minScale
            tx = (regionW - bmp.width * scale) / 2f
            ty = (regionH - bmp.height * scale) / 2f
            clamp(bmp)
        }

        private fun clamp(bmp: Bitmap) {
            // min() keeps the range valid when rounding leaves the photo a hair short.
            tx = tx.coerceIn(minOf(regionW - bmp.width * scale, 0f), 0f)
            ty = ty.coerceIn(minOf(regionH - bmp.height * scale, 0f), 0f)
            invalidate()
            onChanged?.invoke()
        }

        /** The part of the bitmap that will be saved, in bitmap pixels. */
        fun cropRect(): RectF? {
            val bmp = bitmap ?: return null
            if (width == 0) return null
            val l = (-tx / scale).coerceIn(0f, maxOf(bmp.width - 1f, 0f))
            val t = (-ty / scale).coerceIn(0f, maxOf(bmp.height - 1f, 0f))
            val r = (l + regionW / scale).coerceAtMost(bmp.width.toFloat())
            val b = (t + regionH / scale).coerceAtMost(bmp.height.toFloat())
            return RectF(l, t, r, b)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            fit()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bmp = bitmap ?: return true
            scaler.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    // Re-anchor on the remaining finger so the photo doesn't jump.
                    val keep = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == 0) 1 else 0
                    pointer = event.getPointerId(keep)
                    lastX = event.getX(keep)
                    lastY = event.getY(keep)
                }
                MotionEvent.ACTION_MOVE -> {
                    val i = event.findPointerIndex(pointer).takeIf { it >= 0 } ?: return true
                    if (!scaler.isInProgress) {
                        tx += event.getX(i) - lastX
                        ty += event.getY(i) - lastY
                        clamp(bmp)
                    }
                    lastX = event.getX(i)
                    lastY = event.getY(i)
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = bitmap ?: return
            val shiftX = width * ParallaxBackgroundView.TRAVEL_X * pan
            val shiftY = height * ParallaxBackgroundView.TRAVEL_Y * scroll
            val ox = tx - shiftX
            val oy = ty - shiftY
            val li = layered
            if (li == null) {
                m.setScale(scale, scale)
                m.postTranslate(ox, oy)
                canvas.drawBitmap(bmp, m, paint)
                return
            }
            // Layer pixels to screen: the layers' base may be a smaller copy of the photo.
            val s = scale * bmp.width / li.base.width
            m.setScale(s, s)
            m.postTranslate(ox, oy)
            canvas.drawBitmap(li.base, m, paint)
            for (l in li.layers) {
                val extra = l.depth * ParallaxBackgroundView.NEAR_EXTRA
                val lx = ox - shiftX * extra
                val ly = oy - shiftY * extra
                lr.set(lx + l.rect.left * s, ly + l.rect.top * s, lx + l.rect.right * s, ly + l.rect.bottom * s)
                val shader = android.graphics.BitmapShader(l.bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                lm.setScale(lr.width() / l.bitmap.width, lr.height() / l.bitmap.height)
                lm.postTranslate(lr.left, lr.top)
                shader.setLocalMatrix(lm)
                layerPaint.shader = shader
                val margin = maxOf(width, height) * 0.12f * extra + 2f
                canvas.drawRect(lr.left - margin, lr.top - margin, lr.right + margin, lr.bottom + margin, layerPaint)
                layerPaint.shader = null
            }
        }
    }

    /** A glimpse of the app list over the photo: the first apps, as on the phone. */
    private class ListPeekView(context: Context) : View(context) {
        private class Row(val letter: Char?, val label: CharSequence, val component: android.content.ComponentName?)

        var windowMode = false
            set(value) {
                field = value
                invalidate()
            }
        var topInset = 0f
            set(value) {
                field = value
                invalidate()
            }
        private val rows = ArrayList<Row>()
        private val icons = HashMap<android.content.ComponentName, MetroIcons.Icon>()
        private val square = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val hole = Path()
        private val r = RectF()

        init {
            val la = context.getSystemService(LauncherApps::class.java)
            val collator = Collator.getInstance()
            val apps = la?.getActivityList(null, Process.myUserHandle()).orEmpty()
                .filter { it.componentName.packageName != context.packageName }
                .sortedWith { a, b -> collator.compare(a.label.toString(), b.label.toString()) }
                .take(24)
            var section: Char? = null
            for (a in apps) {
                val c = a.label.toString().trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
                if (c != section) {
                    section = c
                    rows += Row(c, "", null)
                }
                rows += Row(null, a.label, a.componentName)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val s = dp(46f)
            val left = dp(16f)
            var y = topInset + dp(8f)
            val accent = MetroTheme.accent(context)
            hole.rewind()
            val drawn = ArrayList<Pair<Row, Float>>()
            for (row in rows) {
                val h = if (row.letter != null) dp(58f) else dp(68f)
                if (y > height) break
                drawn += row to y
                if (row.letter == null) hole.addRect(left, y + (h - s) / 2f, left + s, y + (h + s) / 2f, Path.Direction.CW)
                y += h
            }
            if (windowMode) {
                val save = canvas.save()
                canvas.clipOutPath(hole)
                canvas.drawColor(Color.BLACK)
                canvas.restoreToCount(save)
            }
            for ((row, top) in drawn) {
                val h = if (row.letter != null) dp(58f) else dp(68f)
                r.set(left, top + (h - s) / 2f, left + s, top + (h + s) / 2f)
                if (row.letter != null) {
                    stroke.color = accent
                    stroke.strokeWidth = dp(1.5f)
                    canvas.drawRect(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f, stroke)
                    text.textSize = sp(22f)
                    text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    canvas.drawText(row.letter.lowercaseChar().toString(), r.left + dp(7f), r.bottom - dp(6f) - text.descent(), text)
                    continue
                }
                if (!windowMode) {
                    square.color = accent
                    canvas.drawRect(r, square)
                }
                val cn = row.component
                val icon = cn?.let { icons[it] ?: MetroIcons.get(context, it) { loaded -> icons[it] = loaded; invalidate() }?.also { i -> icons[it] = i } }
                icon?.bitmap?.let { bmp ->
                    val box = s * (if (icon.monochrome) 0.56f else 0.5f)
                    val k = box / maxOf(bmp.width, bmp.height)
                    val iw = bmp.width * k
                    val ih = bmp.height * k
                    iconPaint.colorFilter = if (icon.monochrome) PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) else null
                    canvas.drawBitmap(bmp, null, RectF(r.centerX() - iw / 2, r.centerY() - ih / 2, r.centerX() + iw / 2, r.centerY() + ih / 2), iconPaint)
                }
                text.textSize = sp(17f)
                text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                val x = r.right + dp(16f)
                val label = TextUtils.ellipsize(row.label, text, width - x - dp(16f), TextUtils.TruncateAt.END)
                val fm = text.fontMetrics
                canvas.drawText(label, 0, label.length, x, r.centerY() - (fm.ascent + fm.descent) / 2f, text)
            }
        }

        private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
        private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    }

    companion object {
        private const val EXTRA_GRADIENT = "metro_gradient"

        /** Opens the preview with a freshly generated gradient. */
        @JvmStatic
        fun startGradient(context: Context) {
            context.startActivity(Intent(context, BackgroundPreviewActivity::class.java).putExtra(EXTRA_GRADIENT, true))
        }

        /** Opens the preview for a picked photo. */
        @JvmStatic
        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, BackgroundPreviewActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }
}
