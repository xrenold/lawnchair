package app.lawnchair.metro.info

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.View
import java.util.concurrent.Executors

/**
 * The Photos tile slideshow, paced so the tile doesn't pull your eye away from Start. It moves
 * only about a quarter of the time:
 *
 *  1. **Drift** (~18s): the photo very slowly zooms and pans.
 *  2. **Fade** (1.5s) to the next photo, which then **holds still** (~30s).
 *  3. The tile **turns** (a live-tile flip) to the next photo, which drifts again; or, every
 *     other cycle, turns to its plain **icon** and rests there (~60s) before turning back to a
 *     new photo.
 *
 * The turns themselves are started by Start's flip scheduler (see [wantsTurn]), so they never
 * coincide with neighbouring tiles flipping. Nothing redraws during the still and resting
 * phases, and drawing stops entirely while the tile is off screen.
 */
class PhotoSlideshow(private val view: View) {

    private enum class Phase { DRIFT, FADE, STILL, ICON }

    private val main = Handler(Looper.getMainLooper())
    private val visible = android.graphics.Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    /** Shaders for the current and next photo only, so earlier photos can be freed. */
    private val shaders = object : java.util.LinkedHashMap<Bitmap, BitmapShader>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Bitmap, BitmapShader>?) = size > 2
    }

    private var uris: List<Uri> = emptyList()
    private var index = 0
    private var current: Bitmap? = null
    private var next: Bitmap? = null
    private var loading = false

    private var phase = Phase.DRIFT
    private var phaseStart = 0L
    private var cycle = 0

    /** True while resting on the plain icon (the tile then draws its normal face). */
    val restingOnIcon: Boolean get() = phase == Phase.ICON

    /** New photo list; keeps the current photo if it's still in it. */
    fun update(list: List<Uri>) {
        if (list == uris) return
        uris = list
        if (list.isEmpty()) {
            current = null
            next = null
            return
        }
        if (current == null) {
            index = 0
            load(list[0]) { bmp ->
                current = bmp
                phase = Phase.DRIFT
                phaseStart = System.currentTimeMillis()
                view.invalidate()
                preloadNext()
            }
        }
    }

    private fun preloadNext() {
        if (uris.size < 2 || next != null) return
        load(uris[(index + 1) % uris.size]) { next = it }
    }

    private fun load(uri: Uri, done: (Bitmap?) -> Unit) {
        if (loading) return
        loading = true
        val side = maxOf(view.width, view.height, 480).coerceAtMost(900)
        val resolver = view.context.contentResolver
        loader.execute {
            val bmp = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    resolver.loadThumbnail(uri, Size(side, side), null)
                } else {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= side && bounds.outHeight / (sample * 2) >= side) sample *= 2
                    resolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
            }.getOrNull()
            main.post {
                loading = false
                done(bmp)
            }
        }
    }

    /** Moves on to the preloaded photo, if there is one. */
    private fun advance() {
        val nxt = next ?: return
        current = nxt
        next = null
        index = (index + 1) % uris.size.coerceAtLeast(1)
        preloadNext()
    }

    /** True when the tile should turn now (Start decides when it's allowed). */
    fun wantsTurn(now: Long = System.currentTimeMillis()): Boolean = when (phase) {
        Phase.STILL -> now - phaseStart > STILL_MS
        Phase.ICON -> now - phaseStart > ICON_MS
        else -> false
    }

    /** Called at the edge-on point of a turn: swap to what the other side shows. */
    fun onTurnMidway() {
        val now = System.currentTimeMillis()
        when (phase) {
            Phase.STILL -> {
                cycle++
                if (cycle % 2 == 0) {
                    phase = Phase.ICON // every other cycle: rest on the icon
                } else {
                    advance()
                    phase = Phase.DRIFT
                }
            }
            Phase.ICON -> {
                advance()
                phase = Phase.DRIFT
            }
            else -> Unit
        }
        phaseStart = now
        view.invalidate()
    }

    /** Draws the slideshow; returns false to show the tile's normal face (no photo, or resting). */
    fun draw(canvas: Canvas, w: Float, h: Float): Boolean {
        val cur = current ?: return false
        if (phase == Phase.ICON) return false
        val now = System.currentTimeMillis()
        val elapsed = now - phaseStart
        var animating = false
        when (phase) {
            Phase.DRIFT -> {
                drawKenBurns(canvas, cur, w, h, (elapsed / DRIFT_MS.toFloat()).coerceIn(0f, 1f), index, 255)
                if (elapsed >= DRIFT_MS) {
                    phase = if (next != null) Phase.FADE else Phase.STILL
                    phaseStart = now
                }
                animating = true
                if (next == null) preloadNext()
            }
            Phase.FADE -> {
                drawKenBurns(canvas, cur, w, h, 1f, index, 255)
                val nxt = next
                if (nxt == null) {
                    phase = Phase.STILL
                    phaseStart = now
                } else {
                    val a = (elapsed / FADE_MS.toFloat()).coerceIn(0f, 1f)
                    drawKenBurns(canvas, nxt, w, h, 0f, index + 1, (a * 255).toInt())
                    if (a >= 1f) {
                        advance()
                        phase = Phase.STILL
                        phaseStart = now
                    } else {
                        animating = true
                    }
                }
            }
            Phase.STILL -> drawKenBurns(canvas, cur, w, h, 0f, index, 255)
            Phase.ICON -> Unit
        }
        // Only the drift and the fade need frames (about 30 a second is plenty); still and
        // resting phases don't redraw at all. Off screen, just check back now and then.
        if (animating) {
            if (view.getLocalVisibleRect(visible)) view.postInvalidateDelayed(33) else view.postInvalidateDelayed(500)
        }
        return true
    }

    /** Centre-crop with a very slow zoom (1.06 → 1.09) and a short pan that varies by photo. */
    private fun drawKenBurns(canvas: Canvas, bmp: Bitmap, w: Float, h: Float, t: Float, seed: Int, alpha: Int) {
        val base = maxOf(w / bmp.width, h / bmp.height)
        val zoom = 1.06f + 0.03f * t
        val s = base * zoom
        val extraX = bmp.width * s - w
        val extraY = bmp.height * s - h
        val dirX = if (seed % 2 == 0) 1f else -1f
        val dirY = if ((seed / 2) % 2 == 0) 1f else -1f
        val px = 0.5f + 0.15f * dirX * (t - 0.5f)
        val py = 0.5f + 0.15f * dirY * (t - 0.5f)
        m.setScale(s, s)
        m.postTranslate(-extraX * px, -extraY * py)
        val shader = shaders.getOrPut(bmp) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        shader.setLocalMatrix(m)
        paint.shader = shader
        paint.alpha = alpha
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    companion object {
        /** One loader for every slideshow. */
        private val loader = Executors.newSingleThreadExecutor()
        private const val DRIFT_MS = 18_000L
        private const val FADE_MS = 1_500L
        private const val STILL_MS = 30_000L
        private const val ICON_MS = 60_000L
    }
}
