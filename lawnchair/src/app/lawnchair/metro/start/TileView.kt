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
import app.lawnchair.metro.info.CalEvent
import app.lawnchair.metro.info.InfoKind
import app.lawnchair.metro.info.InfoPainter
import app.lawnchair.metro.info.InfoTiles
import app.lawnchair.metro.info.PhotoSlideshow
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
class TileView(context: Context, override var tile: MetroTile) : View(context), TileHolder {

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

    /** How long the icon side stays before the tile may turn over again. */
    var frontDwellMs = 3500L

    private var flipping = false
    private var flipSpring: SpringAnimation? = null

    /** A back face exists for music always, and for messages when message peeks are allowed. */
    val hasBackFace: Boolean
        get() {
            // Info tiles: only the medium calendar tile turns over, to the next event.
            infoKind?.let { kind ->
                return kind == InfoKind.CALENDAR && tile.size == TileSize.MEDIUM && infoPainter.nextEvent() != null
            }
            val info = live ?: return false
            // Album art works at every size, even on small tiles.
            if (info.isMusic) return info.title != null && (tile.size != TileSize.SMALL || info.image != null)
            if (tile.size == TileSize.SMALL) return false
            if (info.ongoing) return info.title != null
            if (!PreferenceManager.getInstance(context).metroMessagePeek.get()) return false
            return info.title != null || info.text != null
        }

    /**
     * Ongoing content (music, a download, navigation…) stays on the tile for as long as it
     * runs instead of flipping back and forth.
     */
    val isPinned: Boolean get() = infoKind == null && live?.ongoing == true && hasBackFace

    /** Shows the back face at once, without animating (for off-screen tiles). */
    fun showBackNow() {
        if (showingBack || !hasBackFace) return
        showingBack = true
        lastFlipAt = System.currentTimeMillis()
        invalidate()
    }

    // ---- Media controls (wide and large music tiles) ----
    private val controlRects = Array(3) { RectF() }
    private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val controlPath = android.graphics.Path()
    private var controlGesture = -1

    private val controlsShown: Boolean
        get() = showingBack && live?.isMusic == true && live?.controller != null &&
            (tile.size == TileSize.WIDE || tile.size == TileSize.LARGE)

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
                iconInfo = loaded
                label = loaded.label
                // Weather apps not known by package are recognised by their name.
                if (infoKind == null && tile.isApp) {
                    InfoTiles.kindOf(tile.component.packageName, loaded.label)?.let { infoKind = it }
                }
                contentDescription = loaded.label
                backLayouts = null
                invalidate()
                onIconLoaded?.invoke()
            }
        }
        if (tile.kind == MetroTile.Kind.SHORTCUT) {
            val id = tile.shortcutId ?: return
            MetroIcons.getShortcut(context, target.packageName, id, tile.title) { apply(it) }?.let { apply(it) }
        } else {
            MetroIcons.get(context, target) { apply(it) }?.let { apply(it) }
        }
    }

    /** True while picked up for dragging: a black frame sets it apart from the tiles below. */
    override var lifted = false
        set(value) {
            field = value
            invalidate()
        }
    private val liftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }

    /** Width of the black frame around a lifted tile. */
    override val liftBorder: Float get() = dp(4f)

    override fun onDraw(canvas: Canvas) {
        if (showingBack && hasBackFace) drawBack(canvas) else drawFront(canvas)
        if (lifted) {
            val b = liftBorder
            liftPaint.strokeWidth = b
            canvas.drawRect(b / 2f, b / 2f, width - b / 2f, height - b / 2f, liftPaint)
        }
    }

    /**
     * Legibility is handled by dimming the background behind Start (see BackgroundDim). Only
     * "Auto + stronger" adds a barely-there shadow, for the brightest photos, and only where
     * the wallpaper shows through the tile.
     */
    private fun applyShadows(onPhoto: Boolean) {
        if (!onPhoto || PreferenceManager.getInstance(context).metroLegibility.get() < 2) {
            iconPaint.clearShadowLayer()
            labelPaint.clearShadowLayer()
            countPaint.clearShadowLayer()
            return
        }
        val c = 0x40000000
        iconPaint.setShadowLayer(dp(2f), 0f, dp(0.5f), c)
        labelPaint.setShadowLayer(dp(1.5f), 0f, dp(0.5f), c)
        countPaint.setShadowLayer(dp(1.5f), 0f, dp(0.5f), c)
    }

    /**
     * Brand colour for this tile, chosen by Start (0 = use the theme colour). Brand tiles stay
     * solid even in window mode, as third-party tiles with their own colour did on 8.1.
     */
    var brandColor = 0
        set(value) {
            if (field == value) return
            field = value
            backLayouts = null
            invalidate()
        }

    /** Called when the icon (and so the brand colour) has loaded. */
    var onIconLoaded: (() -> Unit)? = null

    /** The app's icon colour info, for brand tiles and the notification panel. */
    var iconInfo: MetroIcons.Icon? = null
        private set

    /** True when this tile is drawn as a window onto the wallpaper (no fill). */
    private val isWindow: Boolean get() = windowMode && brandColor == 0

    private fun drawFill(canvas: Canvas): Int {
        val base = if (brandColor != 0) brandColor else MetroTheme.tileColor(context, tile.key, tile.color)
        if (!isWindow) {
            fillPaint.color = base
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        }
        applyShadows(isWindow)
        return if (isWindow) Color.WHITE else MetroTheme.onTileColor(base)
    }

    private fun drawFront(canvas: Canvas) {
        if (infoKind != null && drawInfoFront(canvas)) return
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
            }.let { b ->
                // Line icons (icon packs like Arcticons) read lighter than filled glyphs: a
                // touch bigger, and never spindly on the small 6-column tiles.
                val pack = iconInfo?.fromPack == true
                val scaled = b * userScale * when {
                    pack -> 1.08f
                    iconIsMonochrome -> 1f
                    else -> 0.9f // full-colour shapes read heavier than glyphs
                }
                if (pack) scaled.coerceAtLeast(dp(20f)) else scaled
            }
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
            iconPaint.colorFilter = iconFilter(onColor)
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

        drawLabel(canvas, onColor)
    }

    /** The app's name, bottom-left, hidden on small tiles (as on Windows Phone). */
    private fun drawLabel(canvas: Canvas, onColor: Int) {
        if (tile.size == TileSize.SMALL || label.isEmpty()) return
        labelPaint.textSize = spK(13f, 11.5f)
        val pad = dp(8f)
        labelPaint.color = onColor
        val text = TextUtils.ellipsize(label, labelPaint, width - pad * 2, TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, pad, height - pad - labelPaint.descent(), labelPaint)
    }

    // ---- Info tiles: calendar, weather, next alarm, photos -------------------------------------

    /** Set by Start for the calendar, weather, clock and photos apps. */
    var infoKind: InfoKind? = null
        set(value) {
            field = value
            invalidate()
        }

    private val infoPainter by lazy { InfoPainter(context) }
    private var slideshow: PhotoSlideshow? = null
    private val photoShade = Paint()

    /** Info data changed (or a minute passed): redraw, and keep the slideshow's photos current. */
    fun infoChanged() {
        if (infoKind == InfoKind.PHOTOS) {
            val on = PreferenceManager.getInstance(context).metroPhotoSlideshow.get()
            // Wide tiles show landscape photos first, so less gets cropped.
            val list = if (tile.size == TileSize.WIDE) {
                InfoTiles.photos.sortedByDescending { it in InfoTiles.landscapePhotos }
            } else {
                InfoTiles.photos
            }
            if (on) (slideshow ?: PhotoSlideshow(this).also { slideshow = it }).update(list) else slideshow = null
        }
        if (showingBack && !hasBackFace) resetFace()
        invalidate()
    }

    /** Draws the info face; returns false to fall back to the normal icon face. */
    private fun drawInfoFront(canvas: Canvas): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        when (infoKind) {
            InfoKind.CALENDAR -> {
                val on = drawFill(canvas)
                infoPainter.drawCalendarFront(canvas, w, h, tile.size, k, on)
                drawLabel(canvas, on)
            }
            InfoKind.WEATHER -> {
                if (InfoTiles.weather == null) return false
                val on = drawFill(canvas)
                if (!infoPainter.drawWeather(canvas, w, h, tile.size, k, on)) return false
                drawLabel(canvas, on)
            }
            InfoKind.CLOCK -> {
                val a = InfoTiles.alarm ?: return false
                if (a.time < System.currentTimeMillis()) return false
                val on = drawFill(canvas)
                infoPainter.drawAlarm(canvas, w, h, tile.size, k, on) { c, l, t, size -> drawAppIcon(c, l, t, size, on) }
                drawLabel(canvas, on)
            }
            InfoKind.PHOTOS -> {
                val show = slideshow ?: return false
                if (!show.draw(canvas, w, h)) return false
                if (tile.size != TileSize.SMALL) {
                    // A soft shade at the bottom so the name reads on any photo.
                    if (photoShadeH != h) {
                        photoShadeH = h
                        photoShade.shader = LinearGradient(0f, h * 0.6f, 0f, h, 0, 0x73000000, Shader.TileMode.CLAMP)
                    }
                    canvas.drawRect(0f, h * 0.6f, w, h, photoShade)
                    drawLabel(canvas, Color.WHITE)
                }
            }
            null -> return false
        }
        return true
    }

    /** The calendar event drawn under (x, y) on a wide or large calendar tile, if any. */
    fun calendarEventAt(x: Float, y: Float): CalEvent? {
        if (infoKind != InfoKind.CALENDAR || showingBack) return null
        if (tile.size != TileSize.WIDE && tile.size != TileSize.LARGE) return null
        return infoPainter.eventHits.firstOrNull { it.first.contains(x, y) }?.second
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
        if (infoKind == InfoKind.CALENDAR) {
            // Medium calendar, content side: the next event, the app's name and today's date.
            val on = drawFill(canvas)
            val h = height.toFloat()
            labelPaint.textSize = spK(13f, 11.5f)
            val footerTop = h - dp(8f) - labelPaint.textSize - dp(6f)
            infoPainter.drawCalendarBack(canvas, width.toFloat(), h, k, on, footerTop)
            drawLabel(canvas, on)
            val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH).toString()
            countPaint.textSize = spK(13f, 11f)
            countPaint.color = on
            canvas.drawText(day, width - dp(8f) - countPaint.measureText(day), h - dp(8f) - labelPaint.descent(), countPaint)
            return
        }
        val info = live ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(10f) * k

        val art = info.image
        if (tile.size == TileSize.SMALL) {
            // Small tile: just the album cover.
            if (art != null) drawCover(canvas, art, 0f, 0f, w, h)
            return
        }
        val onColor: Int
        if (info.isMusic && art != null) {
            drawCover(canvas, art, 0f, 0f, w, h)
            if (musicScrimH != h || musicScrim == null) {
                musicScrimH = h
                musicScrim = LinearGradient(0f, h * 0.4f, 0f, h, 0x00000000, 0xD0000000.toInt(), Shader.TileMode.CLAMP)
            }
            scrimPaint.shader = musicScrim
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
        if (controlsShown) {
            countW = drawControls(canvas, info, w - pad, h - pad - maxOf(footerIcon, labelPaint.textSize) / 2f) + dp(8f)
        } else if (info.count > 0 && !info.isMusic) {
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

        if (info.ongoing && !info.isMusic) {
            // Download, navigation, call…: what it is, its status, and a progress bar.
            val row = rows.first()
            canvas.save()
            canvas.translate(pad, contentTop)
            row.title?.draw(canvas)
            canvas.translate(0f, (row.title?.height ?: 0).toFloat() + dp(2f) * k)
            row.body?.draw(canvas)
            canvas.restore()
            if (info.progress >= 0f) {
                val barH = dp(4f) * k
                val top = contentBottom - barH
                fillPaint.color = ColorUtils.setAlphaComponent(onColor, 0x40)
                canvas.drawRect(pad, top, w - pad, top + barH, fillPaint)
                fillPaint.color = onColor
                val frac = if (info.progress > 1f) 0.35f else info.progress // indeterminate: a short bar
                canvas.drawRect(pad, top, pad + (w - pad * 2) * frac, top + barH, fillPaint)
            }
            return
        }

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
            // Medium: one notification at a time; several take turns, sliding up.
            val idx = itemIndex.coerceAtMost(rows.size - 1)
            val travel = contentBottom - contentTop + dp(8f) * k
            canvas.save()
            canvas.clipRect(0f, contentTop - dp(2f), w, contentBottom)
            if (slide < 1f && idx > 0) {
                drawMediumRow(canvas, rows[idx - 1], pad, contentTop - travel * slide, contentBottom - travel * slide, w)
            }
            drawMediumRow(canvas, rows[idx], pad, contentTop + travel * (1f - slide), contentBottom + travel * (1f - slide), w)
            canvas.restore()
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

    /** Medium back face content: picture and sender on one line, the message below. */
    private fun drawMediumRow(canvas: Canvas, row: BackRow, pad: Float, top: Float, bottom: Float, w: Float) {
        var y = top
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
            val room = bottom - y
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
    }

    private fun drawAppIcon(canvas: Canvas, left: Float, top: Float, size: Float, onColor: Int) {
        val bmp = icon ?: return
        val s = size / maxOf(bmp.width, bmp.height)
        val iw = bmp.width * s
        val ih = bmp.height * s
        iconRect.set(left + (size - iw) / 2f, top + (size - ih) / 2f, left + (size + iw) / 2f, top + (size + ih) / 2f)
        iconPaint.colorFilter = iconFilter(onColor)
        canvas.drawBitmap(bmp, null, iconRect, iconPaint)
    }

    /** Glyphs take [onColor]; icon pack icons keep their colours unless they'd vanish on the tile. */
    private fun iconFilter(onColor: Int): android.graphics.ColorFilter? {
        val info = iconInfo
        val bg = if (isWindow) null else if (brandColor != 0) brandColor else MetroTheme.tileColor(context, tile.key, tile.color)
        // Reused while nothing changes, instead of a new filter on every frame.
        if (filterValid && filterOn == onColor && filterBg == bg && filterIcon === info) return filterCached
        filterCached = if (info == null) {
            if (iconIsMonochrome) PorterDuffColorFilter(onColor, PorterDuff.Mode.SRC_IN) else null
        } else {
            info.filterFor(bg, onColor)
        }
        filterOn = onColor
        filterBg = bg
        filterIcon = info
        filterValid = true
        return filterCached
    }

    private var filterValid = false
    private var filterOn = 0
    private var filterBg: Int? = null
    private var filterIcon: MetroIcons.Icon? = null
    private var filterCached: android.graphics.ColorFilter? = null
    private var photoShadeH = -1f
    private var musicScrimH = -1f
    private var musicScrim: LinearGradient? = null
    private val shaders = java.util.WeakHashMap<Bitmap, BitmapShader>()

    /** Reloads the icon (icon pack or Material You colours changed). */
    fun reloadIcon() = loadIconAsync()

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
        } else if (info.ongoing) {
            headPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            headPaint.textSize = spK(17f, 13f)
            bodyPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            bodyPaint.textSize = spK(13f, 11f)
            bodyPaint.color = dim
            listOf(
                BackRow(
                    info.title?.let { layout(it, headPaint, wInt, 2) },
                    info.text?.let { layout(it, bodyPaint, wInt, if (tile.size == TileSize.LARGE) 4 else 2) },
                    null,
                ),
            )
        } else if (!tile.size.isList) {
            headPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            headPaint.textSize = spK(17f, 13f)
            bodyPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            bodyPaint.textSize = spK(13f, 11f)
            val items = info.items.take(3).ifEmpty { listOf(LiveItem(info.title, info.text, info.image, 0L)) }
            items.map { item ->
                val pic = item.image ?: info.image
                val titleWidth = if (pic != null) (wInt - mediumAvatarSize() - dp(8f) * k).toInt() else wInt
                BackRow(
                    item.title?.let { layout(it, headPaint, titleWidth.coerceAtLeast(1), if (pic != null) 1 else 2) },
                    item.text?.let { layout(it, bodyPaint, wInt, 6) },
                    pic,
                )
            }
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
        val shader = shaders.getOrPut(bmp) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val scale = maxOf((r - l) / bmp.width, (b - t) / bmp.height)
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(l + ((r - l) - bmp.width * scale) / 2f, t + ((b - t) - bmp.height * scale) / 2f)
        shader.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        canvas.drawRect(l, t, r, b, paint)
        paint.shader = null
    }

    /**
     * Previous / play-pause / next, right-aligned on the footer row. Returns the width used so
     * the app name can stop short of it.
     */
    private fun drawControls(canvas: Canvas, info: LiveInfo, right: Float, cy: Float): Float {
        val b = dp(34f) * k // touch target
        val g = dp(16f) * k // glyph
        for (i in 0 until 3) {
            val cx = right - b * (2 - i) - b / 2f
            controlRects[i].set(cx - b / 2f, cy - b / 2f, cx + b / 2f, cy + b / 2f)
            val l = cx - g / 2f
            val t = cy - g / 2f
            controlPath.rewind()
            when (i) {
                0 -> { // previous: bar + left triangle
                    canvas.drawRect(l, t, l + g * 0.16f, t + g, controlPaint)
                    controlPath.moveTo(l + g, t)
                    controlPath.lineTo(l + g * 0.2f, cy)
                    controlPath.lineTo(l + g, t + g)
                }
                1 -> if (info.isPlaying) { // pause: two bars
                    canvas.drawRect(l + g * 0.12f, t, l + g * 0.4f, t + g, controlPaint)
                    canvas.drawRect(l + g * 0.6f, t, l + g * 0.88f, t + g, controlPaint)
                } else { // play: right triangle
                    controlPath.moveTo(l + g * 0.12f, t)
                    controlPath.lineTo(l + g, cy)
                    controlPath.lineTo(l + g * 0.12f, t + g)
                }
                2 -> { // next: right triangle + bar
                    controlPath.moveTo(l, t)
                    controlPath.lineTo(l + g * 0.8f, cy)
                    controlPath.lineTo(l, t + g)
                    canvas.drawRect(l + g * 0.84f, t, l + g, t + g, controlPaint)
                }
            }
            controlPath.close()
            canvas.drawPath(controlPath, controlPaint)
        }
        return b * 3
    }

    private fun countLabel(count: Int) = if (count > 99) "99+" else count.toString()

    /**
     * Windows Phone live-tile flip: the tile swings away around its horizontal axis, swaps face
     * at the edge-on point, then a stiff, critically damped spring brings the new face down with
     * no overshoot, so it lands like a solid card. Turning to new content is quick (~300ms);
     * turning back to the icon is calmer (~450ms). Runs on the render thread's view properties,
     * so it stays smooth at 120 Hz.
     */
    fun flip() = turn(swap = true)

    /** New content arrived while showing the back: flip through to reveal it. */
    fun flipRefresh() = turn(swap = false)

    /** True while a flip is running, so Start can keep neighbours from flipping together. */
    val isFlipping: Boolean get() = flipping

    private fun turn(swap: Boolean) {
        if (flipping) return
        if (swap && !showingBack && !hasBackFace) return
        val toContent = !swap || !showingBack
        flipping = true
        lastFlipAt = System.currentTimeMillis()
        cameraDistance = 9000f * resources.displayMetrics.density
        pivotX = width / 2f
        pivotY = height / 2f
        animate().rotationX(90f).setDuration(if (toContent) 130 else 190).setInterpolator(AccelerateInterpolator(1.6f))
            .setUpdateListener { syncWindow() }
            .withEndAction {
                if (swap) showingBack = !showingBack
                itemIndex = 0
                slide = 1f
                invalidate()
                rotationX = -90f
                syncWindow()
                flipSpring = SpringAnimation(this, DynamicAnimation.ROTATION_X, 0f).apply {
                    spring.stiffness = if (toContent) 1400f else 650f
                    spring.dampingRatio = 1f
                    setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES)
                    addUpdateListener { _, _, _ -> syncWindow() }
                    addEndListener { _, _, _, _ ->
                        flipping = false
                        syncWindow()
                        if (showingBack) scheduleCycle()
                    }
                    start()
                }
            }.start()
    }

    // ---- Several notifications on a medium tile: they take turns with a short vertical slide.

    /** Which notification the medium back face shows. */
    private var itemIndex = 0
    /** 0..1 while sliding from the previous notification to [itemIndex]. */
    private var slide = 1f
    private var slideAnim: android.animation.ValueAnimator? = null
    private val cycleRunnable = Runnable { advanceItem() }

    /** Notifications a medium tile cycles through (up to 3). */
    private val cycleCount: Int
        get() {
            val info = live ?: return 1
            if (info.isMusic || info.ongoing || tile.size != TileSize.MEDIUM) return 1
            return info.items.size.coerceIn(1, 3)
        }

    /** How long the content face stays: 6–7s, plus about 2.5s for each extra notification. */
    fun pickBackDwell(random: java.util.Random) {
        backDwellMs = 6000L + random.nextInt(1000) + 2500L * (cycleCount - 1)
    }

    private fun scheduleCycle() {
        removeCallbacks(cycleRunnable)
        val n = cycleCount
        if (n <= 1 || itemIndex >= n - 1) return
        postDelayed(cycleRunnable, (backDwellMs - 400) / n)
    }

    private fun advanceItem() {
        if (!showingBack || flipping || itemIndex >= cycleCount - 1) return
        itemIndex++
        slideAnim?.cancel()
        slideAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                slide = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        scheduleCycle()
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
        removeCallbacks(cycleRunnable)
        slideAnim?.cancel()
        itemIndex = 0
        slide = 1f
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
    /** Where the last touch went down, for picking the tile up under the finger. */
    override var downX = 0f
        private set
    override var downY = 0f
        private set
    override var downRawX = 0f
        private set
    override var downRawY = 0f
        private set

    /** Set by Start while this tile is being dragged; it receives every touch event. */
    override var dragHandler: ((MotionEvent) -> Boolean)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            downRawX = event.rawX
            downRawY = event.rawY
        }
        dragHandler?.let { if (it(event)) return true }
        // Media buttons on the back of a music tile take the touch instead of opening the app.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && controlsShown) {
            controlGesture = controlRects.indexOfFirst { it.contains(event.x, event.y) }
        }
        if (controlGesture >= 0) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (controlRects[controlGesture].contains(event.x, event.y)) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    val info = live
                    val controls = info?.controller?.transportControls
                    when (controlGesture) {
                        0 -> controls?.skipToPrevious()
                        1 -> if (info?.isPlaying == true) controls?.pause() else controls?.play()
                        2 -> controls?.skipToNext()
                    }
                }
                controlGesture = -1
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                controlGesture = -1
            }
            return true
        }
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

    // Last in the class: every property above is initialised before the icon loads, so a
    // cached icon (delivered synchronously) isn't wiped by a later property initialiser.
    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
        loadIconAsync()
    }
}
