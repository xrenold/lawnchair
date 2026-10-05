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
    data class Result(val score: Float, val landscape: Boolean, val hash: Long)

    private const val SIDE = 96

    private fun cache(context: Context) = context.getSharedPreferences("metro_photo_scores", Context.MODE_PRIVATE)

    /** Cached or freshly computed result; null when the photo should be skipped. */
    fun evaluate(context: Context, id: Long, uri: Uri): Result? {
        val prefs = cache(context)
        prefs.getString(id.toString(), null)?.let { return decode(it) }
        // A failure to read (e.g. still syncing) isn't remembered, so it's tried again later.
        val result = runCatching { analyse(context, uri) }.getOrElse { return null }
        prefs.edit().putString(id.toString(), encode(result)).apply()
        return result
    }

    private fun encode(r: Result?) = if (r == null) "x" else "${r.score},${if (r.landscape) 1 else 0},${r.hash}"

    private fun decode(s: String): Result? {
        if (s == "x") return null
        val p = s.split(',')
        if (p.size < 3) return null
        return Result(p[0].toFloatOrNull() ?: return null, p[1] == "1", p[2].toLongOrNull() ?: 0L)
    }

    private fun thumbnail(context: Context, uri: Uri): Bitmap? {
        val r = context.contentResolver
        return if (Build.VERSION.SDK_INT >= 29) {
            r.loadThumbnail(uri, Size(256, 256), null)
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / (sample * 2) >= 256 && o.outHeight / (sample * 2) >= 256) sample *= 2
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }

    private fun analyse(context: Context, uri: Uri): Result? {
        val thumb = thumbnail(context, uri) ?: return null
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

        // Sharpness: variance of the Laplacian; edge density for spotting text.
        var lapSum = 0.0
        var lapSum2 = 0.0
        var edges = 0
        var count = 0
        for (y in 1 until SIDE - 1) {
            for (x in 1 until SIDE - 1) {
                val i = y * SIDE + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - SIDE] + gray[i + SIDE] - 4 * gray[i]) * 255.0
                lapSum += lap
                lapSum2 += lap * lap
                if (abs(lap) > 40) edges++
                count++
            }
        }
        val lapMean = lapSum / count
        val sharp = lapSum2 / count - lapMean * lapMean
        if (sharp < 60) return null // blurry or shaken

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
        return Result(score, landscape, averageHash(gray))
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
}
