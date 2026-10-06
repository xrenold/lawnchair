package app.lawnchair.metro.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.random.Random

/**
 * Generates mesh-style gradient backgrounds, in the spirit of Apple Music's: a few large, soft
 * blobs of colour blended into a dark base, with a fine grain so the colour never bands.
 *
 * Colours lean dark and rich so white tiles and text stay readable (the automatic dim catches
 * the rest). Random gradients use harmonious palettes: neighbouring hues plus one contrasting
 * accent. Album gradients take the strongest colours of the album art.
 */
object GradientGen {

    /** A random harmonious gradient of [w]×[h] pixels. Same [seed], same gradient. */
    fun random(w: Int, h: Int, seed: Long): Bitmap {
        val rnd = Random(seed)
        val baseHue = rnd.nextFloat() * 360f
        val colors = ArrayList<Int>()
        // Neighbouring hues for the body of the gradient…
        val spread = 25f + rnd.nextFloat() * 25f
        for (i in 0 until 3) {
            val hue = (baseHue + (i - 1) * spread + rnd.nextFloat() * 10f + 360f) % 360f
            colors += hsv(hue, 0.55f + rnd.nextFloat() * 0.3f, 0.32f + rnd.nextFloat() * 0.26f)
        }
        // …and one contrasting accent, used as a smaller blob.
        val accentHue = (baseHue + 150f + rnd.nextFloat() * 60f) % 360f
        colors += hsv(accentHue, 0.6f + rnd.nextFloat() * 0.25f, 0.4f + rnd.nextFloat() * 0.2f)
        return render(w, h, colors, rnd)
    }

    /** A gradient from the album art's strongest colours, darkened for legibility. */
    fun fromArt(art: Bitmap, w: Int, h: Int): Bitmap {
        val palette = Palette.from(art).maximumColorCount(16).generate()
        val picks = listOfNotNull(
            palette.vibrantSwatch, palette.darkVibrantSwatch, palette.mutedSwatch,
            palette.darkMutedSwatch, palette.lightVibrantSwatch, palette.dominantSwatch,
        ).map { it.rgb }.distinct()
        val colors = picks.ifEmpty { listOf(0xFF3A3A3A.toInt()) }.take(4).map { c ->
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hsv(hsv[0], hsv[1].coerceIn(0.35f, 0.85f), hsv[2].coerceIn(0.25f, 0.55f))
        }
        return render(w, h, colors, Random(colors.hashCode().toLong()))
    }

    private fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s, v))

    private fun render(w: Int, h: Int, colors: List<Int>, rnd: Random): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Dark base, tinted by the first colour.
        c.drawColor(ColorUtils.blendARGB(0xFF050505.toInt(), colors.first(), 0.25f))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val diag = Math.hypot(w.toDouble(), h.toDouble()).toFloat()
        // Large soft blobs, then the accent (last colour) smaller.
        colors.forEachIndexed { i, col ->
            val accent = i == colors.lastIndex && colors.size > 3
            val r = diag * (if (accent) 0.28f + rnd.nextFloat() * 0.12f else 0.45f + rnd.nextFloat() * 0.25f)
            val cx = w * (0.1f + rnd.nextFloat() * 0.8f)
            val cy = h * (0.1f + rnd.nextFloat() * 0.8f)
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(ColorUtils.setAlphaComponent(col, if (accent) 0xC0 else 0xE6), ColorUtils.setAlphaComponent(col, 0x55), 0),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawCircle(cx, cy, r, paint)
        }
        paint.shader = null
        addGrain(bmp, rnd)
        return bmp
    }

    /** Fine noise (±3 levels) so smooth gradients don't band on the display. */
    private fun addGrain(bmp: Bitmap, rnd: Random) {
        val w = bmp.width
        val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val n = rnd.nextInt(7) - 3
                val r = (((p shr 16) and 0xFF) + n).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + n).coerceIn(0, 255)
                val b = ((p and 0xFF) + n).coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }
}
