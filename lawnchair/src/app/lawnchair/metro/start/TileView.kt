package app.lawnchair.metro.start

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.core.graphics.ColorUtils
import app.lawnchair.metro.data.MetroTile
import app.lawnchair.metro.data.TileSize
import app.lawnchair.metro.live.LiveInfo
import app.lawnchair.metro.theme.MetroTheme
import app.lawnchair.preferences.PreferenceManager
import java.util.concurrent.Executors

/**
 * A single Start screen tile: coloured square, centred icon and bottom-left label.
 *
 * Icons prefer the app's monochrome (themed) layer drawn in white, which matches the flat
 * Windows Phone look; apps without one fall back to their normal full-colour icon.
 */
@SuppressLint("ViewConstructor")
class TileView(context: Context, var tile: MetroTile) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = sp(13f)
    }

    /** Icon cropped to its visible artwork, so every app's glyph can be drawn at the same size. */
    private var icon: Bitmap? = null
    private var iconIsMonochrome = false
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconRect = RectF()
    var label: CharSequence = ""
        private set
    private var activityInfo: LauncherActivityInfo? = null

    /** Set by StartView in window mode: the tile is only a light tint over the wallpaper. */
    var windowMode = false

    // ---- Live tile state -------------------------------------------------------------------

    /** Live content for this app (notifications or music), or null. */
    var live: LiveInfo? = null
        set(value) {
            if (field == value) return
            field = value
            backLayouts = null
            if (showingBack && !hasBackFace) {
                // Content went away while showing it: return to the front without animating.
                showingBack = false
                rotationX = 0f
            }
            invalidate()
        }

    /** Whether the back (content) face is showing. */
    var showingBack = false
        private set

    /** When this tile last flipped, for the Start screen's flip scheduler. */
    var lastFlipAt = 0L
        private set

    /** How long the back face should stay before flipping home again. */
    var backDwellMs = 7000L

    private var flipping = false
    private var flipSpring: SpringAnimation? = null

    /** A back face exists for music always, and for messages when message peeks are allowed. */
    val hasBackFace: Boolean
        get() {
            val info = live ?: return false
            if (tile.size == TileSize.SMALL) return false
            if (info.isMusic) return info.title != null
            if (!PreferenceManager.getInstance(context).metroMessagePeek.get()) return false
            return info.title != null || info.text != null
        }

    private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint()
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private var backLayouts: Pair<StaticLayout?, StaticLayout?>? = null
    private var backLayoutsWidth = 0

    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
        loadIconAsync()
    }

    fun setTileData(newTile: MetroTile) {
        val componentChanged = newTile.component != tile.component
        tile = newTile
        backLayouts = null
        if (showingBack && !hasBackFace) resetFace()
        if (componentChanged) loadIconAsync()
        invalidate()
    }

    fun refreshColors() = invalidate()

    private fun loadIconAsync() {
        val target = tile.component
        ICON_EXECUTOR.execute {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            val info = runCatching {
                launcherApps?.getActivityList(target.packageName, Process.myUserHandle())
                    ?.firstOrNull { it.componentName == target }
                    ?: launcherApps?.getActivityList(target.packageName, Process.myUserHandle())?.firstOrNull()
            }.getOrNull()
            val density = resources.displayMetrics.densityDpi
            val full = runCatching { info?.getIcon(density) }.getOrNull()
            var mono: Drawable? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && full is AdaptiveIconDrawable) {
                mono = full.monochrome
            }
            val text = runCatching { info?.label }.getOrNull() ?: target.packageName
            val trimmed = runCatching { (mono ?: full)?.let(::renderTrimmed) }.getOrNull()
            MAIN.post {
                if (tile.component != target) return@post
                activityInfo = info
                iconIsMonochrome = mono != null
                icon = trimmed
                label = text
                contentDescription = text
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (showingBack && hasBackFace) drawBack(canvas) else drawFront(canvas)
    }

    private fun drawFill(canvas: Canvas): Int {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = MetroTheme.tileColor(context, tile.key, tile.color)
        val fill = if (windowMode) {
            ColorUtils.setAlphaComponent(base, if (MetroTheme.isTranslucent(context)) 0x55 else 0x00)
        } else {
            MetroTheme.tileFill(context, tile.key, tile.color)
        }
        if (Color.alpha(fill) > 0) {
            fillPaint.color = fill
            canvas.drawRect(0f, 0f, w, h, fillPaint)
        }
        return if (windowMode || MetroTheme.isTranslucent(context)) Color.WHITE else MetroTheme.onTileColor(base)
    }

    private fun drawFront(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val count = live?.count ?: 0
        val onColor = drawFill(canvas)

        // Icon: every glyph gets the same box for a given tile size, measured from its visible
        // artwork, so apps with padded or oversized icons line up with the rest.
        icon?.let { bmp ->
            val showsLabel = tile.size != TileSize.SMALL
            val cell = h / tile.size.rowSpan
            val userScale = PreferenceManager.getInstance(context).metroIconSize.get() / 100f
            val box = when (tile.size) {
                TileSize.SMALL -> cell * 0.42f
                TileSize.LARGE -> cell * 0.62f
                else -> cell * 0.46f
            } * userScale * (if (iconIsMonochrome) 1f else 0.9f) // full-colour shapes read heavier than glyphs
            val scale = box / maxOf(bmp.width, bmp.height)
            val iw = bmp.width * scale
            val ih = bmp.height * scale
            // Windows Phone puts the unread count beside the icon, both centred as a pair.
            val countText = if (count > 0 && tile.size != TileSize.SMALL) countLabel(count) else null
            countPaint.textSize = box * 0.62f
            val countW = countText?.let { countPaint.measureText(it) + dp(6f) } ?: 0f
            val cx = w / 2f - countW / 2f
            val cy = h / 2f - if (showsLabel) h * 0.05f else 0f
            iconRect.set(cx - iw / 2f, cy - ih / 2f, cx + iw / 2f, cy + ih / 2f)
            iconPaint.colorFilter = if (iconIsMonochrome) PorterDuffColorFilter(onColor, PorterDuff.Mode.SRC_IN) else null
            canvas.drawBitmap(bmp, null, iconRect, iconPaint)
            if (countText != null) {
                countPaint.color = onColor
                val fm = countPaint.fontMetrics
                canvas.drawText(countText, iconRect.right + dp(6f), cy - (fm.ascent + fm.descent) / 2f, countPaint)
            }
        }

        // Small tiles: count in the bottom-right corner.
        if (count > 0 && tile.size == TileSize.SMALL) {
            countPaint.textSize = sp(12f)
            countPaint.color = onColor
            val t = countLabel(count)
            canvas.drawText(t, w - dp(5f) - countPaint.measureText(t), h - dp(5f) - countPaint.descent(), countPaint)
        }

        // Label: bottom-left, hidden on small tiles (as on Windows Phone).
        if (tile.size != TileSize.SMALL && label.isNotEmpty()) {
            val pad = dp(8f)
            labelPaint.color = onColor
            val text = TextUtils.ellipsize(label, labelPaint, w - pad * 2, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, pad, h - pad - labelPaint.descent(), labelPaint)
        }
    }

    /**
     * Back face. Music: album art fills the tile with the track over a dark fade. Messages: the
     * sender in large light type and the message below, in Windows Phone's typographic style.
     */
    private fun drawBack(canvas: Canvas) {
        val info = live ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(10f)

        val art = info.image
        if (info.isMusic && art != null) {
            drawCover(canvas, art, 0f, 0f, w, h)
            scrimPaint.shader = LinearGradient(0f, h * 0.45f, 0f, h, 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h * 0.45f, w, h, scrimPaint)
            ensureBackLayouts(info, w - pad * 2, Color.WHITE, music = true)
        } else {
            val onColor = drawFill(canvas)
            ensureBackLayouts(info, w - pad * 2, onColor, music = false)
        }

        val (head, body) = backLayouts ?: return
        canvas.save()
        if (info.isMusic) {
            // Bottom-aligned: track title then artist.
            val total = (head?.height ?: 0) + (body?.height ?: 0)
            canvas.translate(pad, h - pad - total)
        } else {
            var left = pad
            var top = pad
            if (art != null && tile.size == TileSize.WIDE) {
                // Wide tiles: sender picture on the left, text beside it.
                val a = wideAvatarSize()
                drawCover(canvas, art, pad, pad, pad + a, pad + a, paint = avatarPaint)
                left += a + dp(10f)
            } else if (art != null) {
                val a = dp(36f)
                drawCover(canvas, art, pad, pad, pad + a, pad + a, paint = avatarPaint)
                top += a + dp(6f)
            }
            canvas.translate(left, top)
        }
        head?.draw(canvas)
        canvas.translate(0f, (head?.height ?: 0).toFloat())
        body?.draw(canvas)
        canvas.restore()

        // App name stays in the corner so you always know which tile it is.
        if (!info.isMusic) {
            labelPaint.color = bodyPaint.color
            val text = TextUtils.ellipsize(label, labelPaint, w - pad * 2 - dp(24f), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, dp(8f), h - dp(8f) - labelPaint.descent(), labelPaint)
            if (info.count > 0) {
                countPaint.textSize = sp(13f)
                countPaint.color = bodyPaint.color
                val t = countLabel(info.count)
                canvas.drawText(t, w - dp(8f) - countPaint.measureText(t), h - dp(8f) - countPaint.descent(), countPaint)
            }
        }
    }

    private fun ensureBackLayouts(info: LiveInfo, width: Float, color: Int, music: Boolean) {
        val wInt = width.toInt().coerceAtLeast(1)
        headPaint.color = color
        bodyPaint.color = color
        if (backLayouts != null && backLayoutsWidth == wInt) return
        backLayoutsWidth = wInt
        val cell = height.toFloat() / tile.size.rowSpan
        val textWidth = if (!music && info.image != null && tile.size == TileSize.WIDE) {
            (wInt - wideAvatarSize() - dp(10f)).toInt().coerceAtLeast(1)
        } else {
            wInt
        }
        val headLines: Int
        val bodyLines: Int
        if (music) {
            headPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            headPaint.textSize = sp(15f)
            bodyPaint.textSize = sp(13f)
            headLines = 2
            bodyLines = 1
        } else {
            headPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            headPaint.textSize = (cell * 0.24f).coerceIn(sp(16f), sp(26f))
            bodyPaint.textSize = sp(14f)
            headLines = 1
            bodyLines = when (tile.size) {
                TileSize.LARGE -> 7
                TileSize.WIDE -> 3
                else -> if (info.image != null) 2 else 3
            }
        }
        backLayouts = Pair(
            info.title?.let { layout(it, headPaint, textWidth, headLines) },
            info.text?.let { layout(it, bodyPaint, textWidth, bodyLines) },
        )
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .setIncludePad(false)
            .build()

    /** Draws [bmp] scaled to fill the rectangle, cropping the overflow (centre-crop). */
    private fun drawCover(canvas: Canvas, bmp: Bitmap, l: Float, t: Float, r: Float, b: Float, paint: Paint = artPaint) {
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf((r - l) / bmp.width, (b - t) / bmp.height)
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(l + ((r - l) - bmp.width * scale) / 2f, t + ((b - t) - bmp.height * scale) / 2f)
        shader.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        canvas.drawRect(l, t, r, b, paint)
        paint.shader = null
    }

    private fun wideAvatarSize() = height - dp(20f) - labelPaint.textSize - dp(6f)

    private fun countLabel(count: Int) = if (count > 99) "99+" else count.toString()

    /**
     * Windows Phone live-tile flip: the tile swings away around its horizontal axis, swaps face
     * at the edge-on point, then a stiff, slightly under-damped spring brings the new face down
     * with a small settle, giving it physical weight. Runs on the render thread's view
     * properties, so it stays smooth at 120 Hz.
     */
    fun flip() {
        if (flipping) return
        if (!showingBack && !hasBackFace) return
        flipping = true
        lastFlipAt = System.currentTimeMillis()
        cameraDistance = 9000f * resources.displayMetrics.density
        pivotX = width / 2f
        pivotY = height / 2f
        animate().rotationX(90f).setDuration(180).setInterpolator(AccelerateInterpolator(1.8f)).withEndAction {
            showingBack = !showingBack
            invalidate()
            rotationX = -90f
            flipSpring = SpringAnimation(this, DynamicAnimation.ROTATION_X, 0f).apply {
                spring.stiffness = 520f
                spring.dampingRatio = 0.62f
                addEndListener { _, _, _, _ -> flipping = false }
                start()
            }
        }.start()
    }

    /** Snaps back to the front face immediately (e.g. when Start is left). */
    fun resetFace() {
        animate().cancel()
        flipSpring?.cancel()
        flipping = false
        rotationX = 0f
        if (showingBack) {
            showingBack = false
            invalidate()
        }
    }

    // Press feedback: the tile sinks slightly, like the WP tilt effect (full 3D tilt comes later).
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private const val RENDER_SIZE = 256

        /**
         * Draws [d] into a bitmap and crops away transparent padding, leaving only the visible
         * artwork. Icons are then scaled by that artwork, not by their padded canvas.
         */
        private fun renderTrimmed(d: Drawable): Bitmap {
            val full = Bitmap.createBitmap(RENDER_SIZE, RENDER_SIZE, Bitmap.Config.ARGB_8888)
            val c = Canvas(full)
            d.setBounds(0, 0, RENDER_SIZE, RENDER_SIZE)
            d.draw(c)
            val px = IntArray(RENDER_SIZE * RENDER_SIZE)
            full.getPixels(px, 0, RENDER_SIZE, 0, 0, RENDER_SIZE, RENDER_SIZE)
            var minX = RENDER_SIZE
            var minY = RENDER_SIZE
            var maxX = -1
            var maxY = -1
            for (y in 0 until RENDER_SIZE) {
                val row = y * RENDER_SIZE
                for (x in 0 until RENDER_SIZE) {
                    if ((px[row + x] ushr 24) > 24) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < minX || maxY < minY) return full
            return Bitmap.createBitmap(full, minX, minY, maxX - minX + 1, maxY - minY + 1)
        }

        private val ICON_EXECUTOR = Executors.newSingleThreadExecutor()
        private val MAIN = Handler(Looper.getMainLooper())
    }
}
