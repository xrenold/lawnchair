package app.lawnchair.metro.info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Picks the photos worth showing on the Photos tile, on the phone, from each photo's small
 * thumbnail. A photo is skipped when it's blurry, too dark or blown out, or looks like a
 * document, receipt or screen (little colour, mostly white, lots of fine edges). The rest get a
 * score from colourfulness, contrast and sharpness. Bursts of near-identical shots keep only
 * their best.
 *
 * Each photo is scored once (about 10–20ms) and the result is remembered, so later passes only
 * look at new photos.
 */
object PhotoQuality {

    /** Score (0..1) and shape of a photo that passed, plus an 8×8 fingerprint for duplicates. */
    data class Result(val score: Float, val landscape: Boolean, val hash: Long, val color: Int = 0)

    private const val SIDE = 96

    private fun cache(context: Context) = context.getSharedPreferences("metro_photo_scores", Context.MODE_PRIVATE)

    /** Cached or freshly computed result; null when the photo should be skipped. */
    fun evaluate(context: Context, id: Long, uri: Uri): Result? {
        val prefs = cache(context)
        // "v2": rules changed (sharper blur check); older results are checked again once.
        prefs.getString("v2_$id", null)?.let { return decode(it) }
        // A failure to read (e.g. still syncing) isn't remembered, so it's tried again later.
        val result = runCatching { analyse(context, uri) }.getOrElse { return null }
        prefs.edit().remove(id.toString()).putString("v2_$id", encode(result)).apply()
        return result
    }

    private fun encode(r: Result?) = if (r == null) "x" else "${r.score},${if (r.landscape) 1 else 0},${r.hash},${r.color}"

    private fun decode(s: String): Result? {
        if (s == "x") return null
        val p = s.split(',')
        if (p.size < 3) return null
        return Result(p[0].toFloatOrNull() ?: return null, p[1] == "1", p[2].toLongOrNull() ?: 0L, p.getOrNull(3)?.toIntOrNull() ?: 0)
    }

    private fun thumbnail(context: Context, uri: Uri): Bitmap? {
        val r = context.contentResolver
        return if (Build.VERSION.SDK_INT >= 29) {
            r.loadThumbnail(uri, Size(512, 512), null)
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / (sample * 2) >= 512 && o.outHeight / (sample * 2) >= 512) sample *= 2
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }

    private fun analyse(context: Context, uri: Uri): Result? {
        val thumb = thumbnail(context, uri) ?: error("unreadable for now") // not remembered: retried later
        val landscape = thumb.width > thumb.height * 1.1f
        val small = Bitmap.createScaledBitmap(thumb, SIDE, SIDE, true)
        val px = IntArray(SIDE * SIDE)
        small.getPixels(px, 0, SIDE, 0, 0, SIDE, SIDE)

        val gray = FloatArray(px.size)
        var sumL = 0.0
        var dark = 0
        var blown = 0
        var paper = 0
        var sumRg = 0.0
        var sumYb = 0.0
        var sumRg2 = 0.0
        var sumYb2 = 0.0
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            gray[i] = l
            sumL += l
            if (l < 0.08f) dark++
            if (l > 0.96f) blown++
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            if (l > 0.72f && mx - mn < 28) paper++ // white, unsaturated: paper or screens
            val rg = (r - g).toDouble()
            val yb = (0.5 * (r + g) - b)
            sumRg += rg
            sumYb += yb
            sumRg2 += rg * rg
            sumYb2 += yb * yb
        }
        val n = px.size.toDouble()
        val mean = sumL / n
        // Too dark or blown out.
        if (mean < 0.12 || mean > 0.88) return null
        if (dark > n * 0.65 || blown > n * 0.45) return null

        // Colourfulness (Hasler & Süsstrunk).
        val mRg = sumRg / n
        val mYb = sumYb / n
        val sRg = sqrt((sumRg2 / n - mRg * mRg).coerceAtLeast(0.0))
        val sYb = sqrt((sumYb2 / n - mYb * mYb).coerceAtLeast(0.0))
        val colourful = sqrt(sRg * sRg + sYb * sYb) + 0.3 * sqrt(mRg * mRg + mYb * mYb)

        // Contrast: spread of brightness.
        var varL = 0.0
        for (l in gray) varL += (l - mean) * (l - mean)
        val contrast = sqrt(varL / n)

        // Edge density on the small copy, for spotting text.
        var edges = 0
        var count = 0
        for (y in 1 until SIDE - 1) {
            for (x in 1 until SIDE - 1) {
                val i = y * SIDE + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - SIDE] + gray[i + SIDE] - 4 * gray[i]) * 255.0
                if (abs(lap) > 40) edges++
                count++
            }
        }
        // Sharpness on a 4× larger copy: shrinking hides blur, so judge it at a size where
        // soft focus and shake still show. Variance of the Laplacian.
        val sharp = sharpness(thumb)
        if (sharp < SHARP_MIN) return null // blurry or shaken

        // Documents, receipts and screenshots of text: mostly white paper, little colour, many
        // small crisp edges.
        val paperShare = paper / n
        val edgeShare = edges.toDouble() / count
        if (paperShare > 0.45 && colourful < 22 && edgeShare > 0.06) return null
        if (colourful < 9 && contrast < 0.12) return null // flat, dull shots

        val score = (
            (colourful / 80.0).coerceAtMost(1.0) * 0.38 +
                (contrast / 0.28).coerceAtMost(1.0) * 0.30 +
                (sharp / 1500.0).coerceAtMost(1.0) * 0.32
            ).toFloat()
        // Average colour, to tell apart shots of different scenes with similar layouts.
        var ar = 0L
        var ag = 0L
        var ab = 0L
        for (c in px) {
            ar += (c shr 16) and 0xFF
            ag += (c shr 8) and 0xFF
            ab += c and 0xFF
        }
        val color = android.graphics.Color.rgb((ar / px.size).toInt(), (ag / px.size).toInt(), (ab / px.size).toInt())
        return Result(score, landscape, averageHash(gray), color)
    }

    private const val BIG = 384
    private const val SHARP_MIN = 90.0

    private fun sharpness(thumb: Bitmap): Double {
        val scale = BIG.toFloat() / maxOf(thumb.width, thumb.height)
        val w = (thumb.width * scale).toInt().coerceAtLeast(8)
        val h = (thumb.height * scale).toInt().coerceAtLeast(8)
        val big = if (scale < 1f) Bitmap.createScaledBitmap(thumb, w, h, true) else thumb
        val bw = big.width
        val bh = big.height
        val px = IntArray(bw * bh)
        big.getPixels(px, 0, bw, 0, 0, bw, bh)
        val g = FloatArray(px.size) { i ->
            val c = px[i]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF))
        }
        var s = 0.0
        var s2 = 0.0
        var n = 0
        for (y in 1 until bh - 1) {
            for (x in 1 until bw - 1) {
                val i = y * bw + x
                val lap = (g[i - 1] + g[i + 1] + g[i - bw] + g[i + bw] - 4 * g[i]).toDouble()
                s += lap
                s2 += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val m = s / n
        return s2 / n - m * m
    }

    /** 8×8 average-brightness fingerprint: similar photos differ in few bits. */
    private fun averageHash(gray: FloatArray): Long {
        val cells = FloatArray(64)
        val step = SIDE / 8
        for (y in 0 until SIDE) for (x in 0 until SIDE) {
            cells[(y / step).coerceAtMost(7) * 8 + (x / step).coerceAtMost(7)] += gray[y * SIDE + x]
        }
        val avg = cells.average()
        var h = 0L
        for (i in 0 until 64) if (cells[i] > avg) h = h or (1L shl i)
        return h
    }

    fun similar(a: Long, b: Long) = java.lang.Long.bitCount(a xor b) <= 10

    /** Looser check for photos of the same moment: similar layout, or similar layout and colour. */
    fun sameScene(a: Result, b: Result): Boolean {
        val bits = java.lang.Long.bitCount(a.hash xor b.hash)
        if (bits <= 16) return true
        if (bits > 24 || a.color == 0 || b.color == 0) return false
        val dr = ((a.color shr 16) and 0xFF) - ((b.color shr 16) and 0xFF)
        val dg = ((a.color shr 8) and 0xFF) - ((b.color shr 8) and 0xFF)
        val db = (a.color and 0xFF) - (b.color and 0xFF)
        return dr * dr + dg * dg + db * db < 30 * 30
    }

    /** How different two photos look (0 = same), for ordering the slideshow. */
    fun difference(a: Result, b: Result): Int {
        val dr = ((a.color shr 16) and 0xFF) - ((b.color shr 16) and 0xFF)
        val dg = ((a.color shr 8) and 0xFF) - ((b.color shr 8) and 0xFF)
        val db = (a.color and 0xFF) - (b.color and 0xFF)
        return java.lang.Long.bitCount(a.hash xor b.hash) * 4 + (abs(dr) + abs(dg) + abs(db)) / 6
    }
}
