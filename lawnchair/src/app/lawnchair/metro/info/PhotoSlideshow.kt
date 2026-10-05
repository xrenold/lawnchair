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
 * The Photos tile slideshow: recent camera photos fill the tile, each slowly panning and
 * zooming (the Windows Phone "Ken Burns" drift), crossfading to the next every few seconds.
 * It only animates while the tile is actually being drawn, so it costs nothing while you're
 * in another app.
 */
class PhotoSlideshow(private val view: View) {

    private val main = Handler(Looper.getMainLooper())
    private val visible = android.graphics.Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()

    private var uris: List<Uri> = emptyList()
    private var index = 0
    private var current: Bitmap? = null
    private var next: Bitmap? = null
    private var shownAt = 0L
    private var loading = false

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
                shownAt = System.currentTimeMillis()
                view.invalidate()
                preloadNext()
            }
        }
    }

    private fun preloadNext() {
        if (uris.size < 2) return
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

    /** Draws the slideshow; returns false while there's nothing to show yet. */
    fun draw(canvas: Canvas, w: Float, h: Float): Boolean {
        val cur = current ?: return false
        val now = System.currentTimeMillis()
        val elapsed = now - shownAt
        drawKenBurns(canvas, cur, w, h, (elapsed / SHOW_MS.toFloat()).coerceIn(0f, 1.2f), index, 255)
        val fadeStart = SHOW_MS - FADE_MS
        val nxt = next
        if (nxt != null && elapsed > fadeStart) {
            val a = ((elapsed - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
            drawKenBurns(canvas, nxt, w, h, 0f, index + 1, (a * 255).toInt())
            if (a >= 1f) {
                current = nxt
                next = null
                index = (index + 1) % uris.size.coerceAtLeast(1)
                shownAt = now
                preloadNext()
            }
        } else if (nxt == null && elapsed > SHOW_MS && uris.size > 1) {
            preloadNext()
        }
        // About 30 frames a second is plenty for a slow drift. Stop when there's nothing left to
        // move (a single photo that has finished drifting) or the tile is scrolled out of view.
        val settled = uris.size < 2 && elapsed > SHOW_MS * 1.2f
        if (!settled && view.getLocalVisibleRect(visible)) {
            view.postInvalidateDelayed(33)
        } else if (!settled) {
            view.postInvalidateDelayed(500) // check again in a moment, cheaply
        }
        return true
    }

    /** Centre-crop with a slow zoom (1.06 → 1.14) and a pan whose direction varies by photo. */
    private fun drawKenBurns(canvas: Canvas, bmp: Bitmap, w: Float, h: Float, t: Float, seed: Int, alpha: Int) {
        val base = maxOf(w / bmp.width, h / bmp.height)
        val zoom = 1.06f + 0.08f * t
        val s = base * zoom
        val extraX = bmp.width * s - w
        val extraY = bmp.height * s - h
        val dirX = if (seed % 2 == 0) 1f else -1f
        val dirY = if ((seed / 2) % 2 == 0) 1f else -1f
        val px = 0.5f + 0.3f * dirX * (t - 0.5f)
        val py = 0.5f + 0.3f * dirY * (t - 0.5f)
        m.setScale(s, s)
        m.postTranslate(-extraX * px, -extraY * py)
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(m)
        paint.shader = shader
        paint.alpha = alpha
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    companion object {
        /** One loader for every slideshow. */
        private val loader = Executors.newSingleThreadExecutor()
        private const val SHOW_MS = 7000L
        private const val FADE_MS = 900L
    }
}
