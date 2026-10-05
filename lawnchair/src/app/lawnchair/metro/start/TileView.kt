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
import android.view.animation.DecelerateInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.core.graphics.ColorUtils
import app.lawnchair.metro.data.MetroTile
import app.lawnchair.metro.data.TileSize
import app.lawnchair.metro.live.LiveInfo
import app.lawnchair.metro.live.LiveItem
import app.lawnchair.metro.theme.MetroIcons
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
    /** Laid-out text for the back face; rebuilt when content or size changes. */
    private var backLayouts: List<BackRow>? = null
    private var backLayoutsWidth = 0

    private class BackRow(val title: StaticLayout?, val body: StaticLayout?, val image: Bitmap?)

    /**
     * Type and spacing scale for this tile, from its cell size: 1.0 on the 4-column grid,
     * about 0.7 on the 6-column grid, so live content keeps its proportions on both.
     */
    private val k: Float
        get() {
            val cellDp = height.toFloat() / tile.size.rowSpan / resources.displayMetrics.density
            return (cellDp / 99f).coerceIn(0.68f, 1.1f)
        }

    private fun spK(v: Float, min: Float) = maxOf(sp(v) * k, sp(min))

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

    fun refreshColors() {
        backLayouts = null // text colours are baked into the cached layouts
        invalidate()
    }

    private fun loadIconAsync() {
        val target = tile.component
        val apply = { loaded: MetroIcons.Icon ->
            if (tile.component == target) {
                iconIsMonochrome = loaded.monochrome
                icon = loaded.bitmap
                label = loaded.label
                contentDescription = loaded.label
                backLayouts = null
                invalidate()
            }
        }
        MetroIcons.get(context, target) { apply(it) }?.let { apply(it) }
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

        labelPaint.textSize = spK(13f, 11.5f)

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
     * Back face.
     *  - Music: album art fills the tile, track and artist over a dark fade.
     *  - Messages: newest first. Medium tiles show one message with the sender in light type;
     *    wide and large tiles list several, each with the sender's picture.
     * The bottom row always shows the app's icon and name (and the count), so the tile stays
     * identifiable while flipped.
     */
    private fun drawBack(canvas: Canvas) {
        val info = live ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(10f) * k

        val art = info.image
        val onColor: Int
        if (info.isMusic && art != null) {
            drawCover(canvas, art, 0f, 0f, w, h)
            scrimPaint.shader = LinearGradient(0f, h * 0.4f, 0f, h, 0x00000000, 0xD0000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h * 0.4f, w, h, scrimPaint)
            onColor = Color.WHITE
        } else {
            onColor = drawFill(canvas)
        }
        headPaint.color = onColor
        bodyPaint.color = onColor
        labelPaint.textSize = spK(13f, 11f)

        // Footer: app icon + name, count on the right.
        val footerIcon = dp(16f) * k
        val footerBaseline = h - pad - labelPaint.descent()
        val footerTop = h - pad - maxOf(footerIcon, labelPaint.textSize)
        drawAppIcon(canvas, pad, h - pad - footerIcon, footerIcon, onColor)
        val labelX = pad + footerIcon + dp(6f) * k
        var countW = 0f
        if (info.count > 0 && !info.isMusic) {
            countPaint.textSize = spK(13f, 11f)
            countPaint.color = onColor
            val t = countLabel(info.count)
            countW = countPaint.measureText(t) + dp(6f)
            canvas.drawText(t, w - pad - countPaint.measureText(t), footerBaseline, countPaint)
        }
        labelPaint.color = onColor
        val labelText = TextUtils.ellipsize(label, labelPaint, w - labelX - pad - countW, TextUtils.TruncateAt.END)
        canvas.drawText(labelText, 0, labelText.length, labelX, footerBaseline, labelPaint)

        // Content area above the footer.
        val contentTop = pad
        val contentBottom = footerTop - dp(8f) * k
        val rows = ensureBackLayouts(info, (w - pad * 2).toInt())
        if (rows.isEmpty()) return

        if (info.isMusic) {
            // Bottom-aligned above the footer: track title, then artist.
            val row = rows.first()
            val total = (row.title?.height ?: 0) + (row.body?.height ?: 0)
            canvas.save()
            canvas.translate(pad, contentBottom - total)
            row.title?.draw(canvas)
            canvas.translate(0f, (row.title?.height ?: 0).toFloat())
            row.body?.draw(canvas)
            canvas.restore()
            return
        }

        if (!tile.size.isList) {
            // Medium: picture and sender on one line, the message below.
            val row = rows.first()
            var y = contentTop
            val avatar = mediumAvatarSize()
            if (row.image != null) {
                drawCover(canvas, row.image, pad, y, pad + avatar, y + avatar, paint = avatarPaint)
                canvas.save()
                canvas.translate(pad + avatar + dp(8f) * k, y + (avatar - (row.title?.height ?: 0)) / 2f)
                row.title?.draw(canvas)
                canvas.restore()
                y += avatar + dp(6f) * k
            } else {
                canvas.save()
                canvas.translate(pad, y)
                row.title?.draw(canvas)
                canvas.restore()
                y += (row.title?.height ?: 0) + dp(3f) * k
            }
            row.body?.let { body ->
                // Whole lines only: never show a line cut in half.
                val room = contentBottom - y
                var lines = 0
                while (lines < body.lineCount && body.getLineBottom(lines) <= room) lines++
                if (lines > 0) {
                    canvas.save()
                    canvas.translate(pad, y)
                    canvas.clipRect(0f, 0f, w - pad * 2, body.getLineBottom(lines - 1).toFloat())
                    body.draw(canvas)
                    canvas.restore()
                }
            }
            return
        }

        // Wide, extra wide and large: a list of messages with breathing room between them.
        val avatar = rowAvatarSize()
        val gap = dp(12f) * k
        var y = contentTop
        var shown = 0
        for (row in rows) {
            if (shown >= maxListRows()) break
            val textH = (row.title?.height ?: 0) + (row.body?.height ?: 0)
            val rowH = maxOf(if (row.image != null) avatar else 0f, textH.toFloat())
            if (y + rowH > contentBottom) break
            var x = pad
            if (row.image != null) {
                drawCover(canvas, row.image, pad, y + (rowH - avatar) / 2f, pad + avatar, y + (rowH + avatar) / 2f, paint = avatarPaint)
                x += avatar + dp(10f) * k
            }
            canvas.save()
            canvas.translate(x, y + (rowH - textH) / 2f)
            row.title?.draw(canvas)
            canvas.translate(0f, (row.title?.height ?: 0).toFloat())
            row.body?.draw(canvas)
            canvas.restore()
            y += rowH + gap
            shown++
        }
    }

    private fun drawAppIcon(canvas: Canvas, left: Float, top: Float, size: Float, onColor: Int) {
        val bmp = icon ?: return
        val s = size / maxOf(bmp.width, bmp.height)
        val iw = bmp.width * s
        val ih = bmp.height * s
        iconRect.set(left + (size - iw) / 2f, top + (size - ih) / 2f, left + (size + iw) / 2f, top + (size + ih) / 2f)
        iconPaint.colorFilter = if (iconIsMonochrome) PorterDuffColorFilter(onColor, PorterDuff.Mode.SRC_IN) else null
        canvas.drawBitmap(bmp, null, iconRect, iconPaint)
    }

    private fun mediumAvatarSize() = dp(34f) * k

    /** At most this many messages, so rows never crowd each other. */
    private fun maxListRows() = when (tile.size) {
        TileSize.LARGE -> if (k < 0.9f) 3 else 4
        else -> if (k < 0.9f) 1 else 2
    }

    private fun rowAvatarSize() = (if (tile.size == TileSize.LARGE) dp(40f) else dp(36f)) * k

    private fun ensureBackLayouts(info: LiveInfo, width: Int): List<BackRow> {
        val wInt = width.coerceAtLeast(1)
        backLayouts?.let { if (backLayoutsWidth == wInt) return it }
        backLayoutsWidth = wInt

        // Secondary text (the message under a sender) is slightly dimmer, for hierarchy.
        val dim = ColorUtils.setAlphaComponent(bodyPaint.color, 0xC8)
        val rows: List<BackRow> = if (info.isMusic) {
            headPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            headPaint.textSize = spK(if (tile.size == TileSize.MEDIUM) 15f else 17f, 12.5f)
            bodyPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            bodyPaint.textSize = spK(13f, 11f)
            bodyPaint.color = dim
            listOf(
                BackRow(
                    info.title?.let { layout(it, headPaint, wInt, 2) },
                    info.text?.let { layout(it, bodyPaint, wInt, 1) },
                    null,
                ),
            )
        } else if (!tile.size.isList) {
            val item = info.items.firstOrNull()
            headPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            headPaint.textSize = spK(17f, 13f)
            bodyPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            bodyPaint.textSize = spK(13f, 11f)
            val hasPic = (item?.image ?: info.image) != null
            val titleWidth = if (hasPic) (wInt - mediumAvatarSize() - dp(8f) * k).toInt() else wInt
            listOf(
                BackRow(
                    (item?.title ?: info.title)?.let { layout(it, headPaint, titleWidth.coerceAtLeast(1), if (hasPic) 1 else 2) },
                    (item?.text ?: info.text)?.let { layout(it, bodyPaint, wInt, 6) },
                    item?.image ?: info.image,
                ),
            )
        } else {
            // List rows: sender in regular weight, message dimmer below it.
            headPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            headPaint.textSize = spK(14.5f, 12f)
            bodyPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            bodyPaint.textSize = spK(13f, 11f)
            bodyPaint.color = dim
            val bodyLines = if (tile.size == TileSize.LARGE) 2 else 1
            val items = info.items.ifEmpty { listOf(LiveItem(info.title, info.text, info.image, 0L)) }
            items.map { item ->
                val textW = if (item.image != null) (wInt - rowAvatarSize() - dp(10f) * k).toInt() else wInt
                BackRow(
                    item.title?.let { layout(it, headPaint, textW.coerceAtLeast(1), 1) },
                    item.text?.let { layout(it, bodyPaint, textW.coerceAtLeast(1), bodyLines) },
                    item.image,
                )
            }
        }
        backLayouts = rows
        return rows
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(paint), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.06f)
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
        animate().rotationX(90f).setDuration(180).setInterpolator(AccelerateInterpolator(1.8f))
            .setUpdateListener { syncWindow() }
            .withEndAction {
                showingBack = !showingBack
                invalidate()
                rotationX = -90f
                syncWindow()
                flipSpring = SpringAnimation(this, DynamicAnimation.ROTATION_X, 0f).apply {
                    spring.stiffness = 520f
                    spring.dampingRatio = 0.62f
                    addUpdateListener { _, _, _ -> syncWindow() }
                    addEndListener { _, _, _, _ ->
                        flipping = false
                        syncWindow()
                    }
                    start()
                }
            }.start()
    }

    /**
     * In window mode the hole in the black mask must follow this tile's rotation and scale,
     * so the "window" itself flips or sinks rather than just its content.
     */
    private fun syncWindow() {
        if (windowMode) (parent as? View)?.invalidate()
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
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                .setInterpolator(DecelerateInterpolator()).setUpdateListener { syncWindow() }.start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(120)
                    .setInterpolator(DecelerateInterpolator()).setUpdateListener { syncWindow() }.start()
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

}
