package app.lawnchair.metro.theme

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates mesh gradient backgrounds: several soft fields of colour that melt into each
 * other, like recent phone and desktop wallpapers. Most of the picture is deep and dark, with
 * one or two smaller areas that glow, and a fine grain over everything so it reads as a
 * texture (and never bands).
 *
 * Random gradients pick from proven colour schemes (neighbouring hues with one contrasting
 * accent, and a few variations). Album gradients use the art's own colours in the same style:
 * its dark tones for the body, its most vivid ones for the glow.
 *
 * Rendering happens at a sixth of the size and is scaled up smoothly; a full-screen gradient
 * takes a fraction of a second, only when one is generated.
 */
object GradientGen {

    /** A colour field: position (0..1), colour, and how far it reaches (fraction of the diagonal). */
    private class Point(val x: Float, val y: Float, val color: Int, val reach: Float, val angle: Float = 0f)

    /** A random gradient of [w]×[h] pixels. Same [seed], same gradient. */
    fun random(w: Int, h: Int, seed: Long): LayeredImage {
        val rnd = Random(seed)
        // Yellow-greens make a sickly dark body; shift them along the wheel.
        val base = (rnd.nextFloat() * 360f).let { if (it in 55f..105f) it + 60f else it }
        val scheme = rnd.nextInt(4)
        val spread = 18f + rnd.nextFloat() * 22f
        // Body hues (dark) and glow hues (bright), by scheme.
        val bodyHues: List<Float>
        val glowHues: List<Float>
        when (scheme) {
            0 -> { // neighbouring hues, one contrasting glow
                bodyHues = listOf(base, base + spread, base - spread)
                glowHues = listOf(base + 180f + (rnd.nextFloat() - 0.5f) * 40f)
            }
            1 -> { // neighbouring hues, glow in the same family
                bodyHues = listOf(base, base + spread, base + spread * 2)
                glowHues = listOf(base + spread * 0.5f)
            }
            2 -> { // split complementary: two glows either side of the opposite hue
                bodyHues = listOf(base, base + spread * 0.6f)
                glowHues = listOf(base + 150f, base + 210f)
            }
            else -> { // deep single hue with a warm or cool light
                bodyHues = listOf(base, base + 8f, base - 8f)
                glowHues = listOf(if (rnd.nextBoolean()) 35f + rnd.nextFloat() * 20f else 190f + rnd.nextFloat() * 30f)
            }
        }
        // One body tone a little lighter, the rest deep: light and dark rather than one flat level.
        val body = bodyHues.mapIndexed { i, hue ->
            hsv(hue, 0.55f + rnd.nextFloat() * 0.3f, if (i == 0) 0.26f + rnd.nextFloat() * 0.12f else 0.1f + rnd.nextFloat() * 0.12f)
        }
        val glows = glowHues.map { hsv(it, 0.6f + rnd.nextFloat() * 0.3f, 0.62f + rnd.nextFloat() * 0.2f) }
        // One glow always; a second only sometimes, so not every gradient is busy.
        val glowCount = if (glows.size > 1 || rnd.nextFloat() < 0.35f) 2 else 1
        return render(w, h, body, List(glowCount) { glows[it % glows.size] }, rnd)
    }

    /** A gradient from the album art's colours, in the same style. */
    fun fromArt(art: Bitmap, w: Int, h: Int): LayeredImage {
        val palette = Palette.from(art).maximumColorCount(16).generate()
        val darks = listOfNotNull(palette.darkMutedSwatch, palette.darkVibrantSwatch, palette.dominantSwatch, palette.mutedSwatch)
            .map { it.rgb }.distinct()
        val brights = listOfNotNull(palette.vibrantSwatch, palette.lightVibrantSwatch, palette.lightMutedSwatch)
            .map { it.rgb }.distinct()
        val fallback = palette.dominantSwatch?.rgb ?: 0xFF404040.toInt()
        val body = darks.ifEmpty { listOf(fallback) }.take(3).map { tone(it, 0.3f, 0.85f, 0.12f, 0.3f) }
        val glows = brights.ifEmpty { listOf(fallback) }.take(2).map { tone(it, 0.45f, 0.95f, 0.6f, 0.82f) }
        return render(w, h, body, glows, Random((body + glows).hashCode().toLong()))
    }

    private fun tone(c: Int, sMin: Float, sMax: Float, vMin: Float, vMax: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        return hsv(hsv[0], hsv[1].coerceIn(sMin, sMax), hsv[2].coerceIn(vMin, vMax))
    }

    private fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))

    /**
     * The body fields (with depth vignette and grain) as the base, and each glow as its own
     * soft layer over it, so they can drift at their own depth. Glow layers are stored at the
     * render size and drawn scaled up; they're soft, so nothing is lost.
     */
    private fun render(w: Int, h: Int, body: List<Int>, glows: List<Int>, rnd: Random): LayeredImage {
        val outW = w.coerceAtLeast(1)
        val outH = h.coerceAtLeast(1)
        val sw = (outW / SCALE).coerceAtLeast(8)
        val sh = (outH / SCALE).coerceAtLeast(8)
        val aspect = sh.toFloat() / sw

        // Body fields spread over a loose grid so the colours don't clump; glows land anywhere
        // except the very edges, and stay smaller.
        val points = ArrayList<Point>()
        val cells = body.size + 2
        for (i in 0 until cells) {
            val gx = (i % 2 + 0.5f) / 2f
            val gy = (i / 2 + 0.5f) / ((cells + 1) / 2f)
            points += Point(
                (gx + (rnd.nextFloat() - 0.5f) * 0.45f).coerceIn(-0.1f, 1.1f),
                (gy + (rnd.nextFloat() - 0.5f) * 0.3f).coerceIn(-0.1f, 1.1f),
                body[i % body.size],
                0.32f + rnd.nextFloat() * 0.18f,
            )
        }
        // Glows are long, soft streaks of light at a random angle.
        val glowPoints = glows.map { g ->
            Point(0.15f + rnd.nextFloat() * 0.7f, 0.1f + rnd.nextFloat() * 0.8f, g, 0.11f + rnd.nextFloat() * 0.07f, rnd.nextFloat() * 3.14f)
        }
        // Deep base the fields fade into, tinted by the first body colour.
        val baseLin = lin(blend(0xFF040406.toInt(), body.first(), 0.35f))

        // Gentle warp of the coordinates, so the fields have organic, uneven edges.
        val f1 = 2f + rnd.nextFloat() * 3f
        val f2 = 2f + rnd.nextFloat() * 3f
        val p1 = rnd.nextFloat() * 6.28f
        val p2 = rnd.nextFloat() * 6.28f
        val amp = 0.1f + rnd.nextFloat() * 0.07f

        val colorsLin = points.map { lin(it.color) }
        val glowLin = glowPoints.map { lin(it.color) }
        val glowCos = glowPoints.map { cos(it.angle) }
        val glowSin = glowPoints.map { sin(it.angle) }
        val px = IntArray(sw * sh)
        val glowPx = Array(glowPoints.size) { IntArray(sw * sh) }
        val diag = kotlin.math.sqrt(1f + aspect * aspect)
        for (yi in 0 until sh) {
            val v = yi.toFloat() / (sh - 1)
            for (xi in 0 until sw) {
                val u = xi.toFloat() / (sw - 1)
                val wu = u + amp * sin(v * f1 + p1) + amp * 0.25f * sin(v * f2 * 2.3f + p2)
                val wv = v + amp * cos(u * f2 + p2) + amp * 0.25f * cos(u * f1 * 2.1f + p1)
                var r = baseLin[0] * BASE_WEIGHT
                var g = baseLin[1] * BASE_WEIGHT
                var b = baseLin[2] * BASE_WEIGHT
                var total = BASE_WEIGHT
                for (k in points.indices) {
                    val p = points[k]
                    val dx = wu - p.x
                    val dy = (wv - p.y) * aspect
                    val d = (dx * dx + dy * dy) / (diag * diag)
                    val reach = p.reach * p.reach
                    val wt = exp(-d / reach)
                    val c = colorsLin[k]
                    r += c[0] * wt
                    g += c[1] * wt
                    b += c[2] * wt
                    total += wt
                }
                r /= total
                g /= total
                b /= total
                // Darker towards the edges, for depth.
                val vig = 1f - 0.45f * (((u - 0.5f) * (u - 0.5f) + (v - 0.5f) * (v - 0.5f) * 0.6f) * 2.2f).coerceIn(0f, 1f)
                px[yi * sw + xi] = srgb(r * vig, g * vig, b * vig)
                // Glows: light laid over the body, each in its own layer.
                for (k in glowPoints.indices) {
                    val p = glowPoints[k]
                    val dx = wu - p.x
                    val dy = (wv - p.y) * aspect
                    val a1 = (dx * glowCos[k] + dy * glowSin[k]) / 1.9f
                    val a2 = (-dx * glowSin[k] + dy * glowCos[k]) * 1.15f
                    val d = (a1 * a1 + a2 * a2) / (diag * diag) / (p.reach * p.reach)
                    val a = 0.85f * exp(-d.toDouble().pow(0.8).toFloat())
                    val c = glowLin[k]
                    val col = srgb(c[0] * vig, c[1] * vig, c[2] * vig)
                    glowPx[k][yi * sw + xi] = (((a * 255f + 0.5f).toInt().coerceIn(0, 255)) shl 24) or (col and 0xFFFFFF)
                }
            }
        }
        val small = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
        val bmp = Bitmap.createScaledBitmap(small, outW, outH, true).copy(Bitmap.Config.ARGB_8888, true)
        small.recycle()
        addGrain(bmp, rnd)
        val kx = outW.toFloat() / sw
        val ky = outH.toFloat() / sh
        val layers = glowPx.mapIndexedNotNull { k, g ->
            // Only the part with light in it, so nothing transparent is drawn while scrolling.
            var l = sw
            var t = sh
            var r = -1
            var b = -1
            for (y in 0 until sh) for (x in 0 until sw) {
                if ((g[y * sw + x] ushr 24) > 2) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
            if (r < 0) return@mapIndexedNotNull null
            l = (l - 1).coerceAtLeast(0)
            t = (t - 1).coerceAtLeast(0)
            r = (r + 2).coerceAtMost(sw)
            b = (b + 2).coerceAtMost(sh)
            val cut = IntArray((r - l) * (b - t)) { i -> g[(t + i / (r - l)) * sw + l + i % (r - l)] }
            // The first glow sits nearest; any second one a little further back.
            BgLayer(
                Bitmap.createBitmap(cut, r - l, b - t, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true),
                android.graphics.RectF(l * kx, t * ky, r * kx, b * ky),
                if (k == 0) 1f else 0.6f,
            )
        }
        return LayeredImage(bmp, layers)
    }

    /** Colours are mixed in linear light, so blends stay clean instead of going muddy. */
    private fun lin(c: Int) = floatArrayOf(
        (Color.red(c) / 255f).pow(2.2f),
        (Color.green(c) / 255f).pow(2.2f),
        (Color.blue(c) / 255f).pow(2.2f),
    )

    private fun srgb(r: Float, g: Float, b: Float): Int = Color.rgb(
        (r.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f + 0.5f).toInt(),
        (g.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f + 0.5f).toInt(),
        (b.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f + 0.5f).toInt(),
    )

    private fun blend(a: Int, b: Int, t: Float): Int = androidx.core.graphics.ColorUtils.blendARGB(a, b, t)

    /** Fine film grain (±4 levels, same on all channels): barely visible, but it reads as texture. */
    private fun addGrain(bmp: Bitmap, rnd: Random) {
        val w = bmp.width
        val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val n = rnd.nextInt(9) - 4
                val r = (((p shr 16) and 0xFF) + n).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + n).coerceIn(0, 255)
                val b = ((p and 0xFF) + n).coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    private const val SCALE = 6
    /** How strongly the dark base holds where no field reaches. */
    private const val BASE_WEIGHT = 0.08f
}
