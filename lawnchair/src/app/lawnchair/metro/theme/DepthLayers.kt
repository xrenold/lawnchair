package app.lawnchair.metro.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import app.lawnchair.metro.CrashLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Splits a photo into depth layers for the parallax background, entirely on the phone.
 *
 * 1. [estimate]: a small depth model (MiDaS v2.1 Small, MIT licence, half-precision weights)
 *    guesses how near each part of the photo is, on a 256×256 copy. About a third of a second.
 * 2. [build]: the depth is grouped into 2–4 bands (far to near). Each band becomes a layer;
 *    where a nearer thing covered it, the layer is filled with a soft continuation of its own
 *    surroundings, so moving the nearer layer never shows a hole.
 *
 * Runs once when a photo is chosen; Start only loads the saved layers.
 */
object DepthLayers {

    /** Depth for the whole photo, 0 (far) to 1 (near), on a coarse grid. */
    class DepthMap(val w: Int, val h: Int, val v: FloatArray) {
        /** Depth at ([u], [y]) in 0..1 photo coordinates, interpolated. */
        fun at(u: Float, y: Float): Float {
            val fx = (u * w - 0.5f).coerceIn(0f, (w - 1).toFloat())
            val fy = (y * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = minOf(x0 + 1, w - 1)
            val y1 = minOf(y0 + 1, h - 1)
            val ax = fx - x0
            val ay = fy - y0
            val top = v[y0 * w + x0] * (1 - ax) + v[y0 * w + x1] * ax
            val bottom = v[y1 * w + x0] * (1 - ax) + v[y1 * w + x1] * ax
            return top * (1 - ay) + bottom * ay
        }
    }

    private const val MODEL = "metro/depth_midas_small_fp16.tflite"

    /** Runs the depth model on [photo]. Null if it can't run here (it then simply has no depth). */
    fun estimate(context: Context, photo: Bitmap): DepthMap? = runCatching {
        // Mapped straight from the app package (stored uncompressed), so the 33 MB model isn't
        // copied into memory; read into a buffer only if mapping isn't possible.
        val model: ByteBuffer = runCatching {
            context.assets.openFd(MODEL).use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }.getOrElse {
            context.assets.open(MODEL).use { input ->
                val bytes = input.readBytes()
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
            }
        }
        val options = org.tensorflow.lite.Interpreter.Options().apply { setNumThreads(4) }
        org.tensorflow.lite.Interpreter(model, options).use { interp ->
            val inShape = interp.getInputTensor(0).shape() // [1, h, w, 3]
            val ih = inShape[1]
            val iw = inShape[2]
            val small = Bitmap.createScaledBitmap(photo, iw, ih, true)
            val px = IntArray(iw * ih)
            small.getPixels(px, 0, iw, 0, 0, iw, ih)
            val input = ByteBuffer.allocateDirect(4 * iw * ih * 3).order(ByteOrder.nativeOrder())
            for (c in px) {
                input.putFloat((Color.red(c) / 255f - 0.485f) / 0.229f)
                input.putFloat((Color.green(c) / 255f - 0.456f) / 0.224f)
                input.putFloat((Color.blue(c) / 255f - 0.406f) / 0.225f)
            }
            input.rewind()
            val outShape = interp.getOutputTensor(0).shape() // [1, h, w, 1]
            val oh = outShape[1]
            val ow = outShape[2]
            val output = ByteBuffer.allocateDirect(4 * oh * ow).order(ByteOrder.nativeOrder())
            interp.run(input, output)
            output.rewind()
            val raw = FloatArray(oh * ow) { output.getFloat() }
            // The model's numbers are relative: stretch the middle 96% to 0..1, then smooth so
            // layer edges follow shapes rather than noise.
            val sorted = raw.sortedArray()
            val lo = sorted[(sorted.size * 0.02f).toInt()]
            val hi = sorted[(sorted.size * 0.98f).toInt().coerceAtMost(sorted.size - 1)]
            val range = (hi - lo).coerceAtLeast(1e-6f)
            var v = FloatArray(raw.size) { ((raw[it] - lo) / range).coerceIn(0f, 1f) }
            repeat(2) { v = boxBlur(v, ow, oh, 2) }
            DepthMap(ow, oh, v)
        }
    }.onFailure { CrashLog.event("depth", "depth model failed", it) }.getOrNull()

    /**
     * Builds the layers for [img], which shows the part [region] (0..1 coordinates) of the photo
     * [depth] was measured on. Returns a single layer (just [img]) when the photo has no real
     * depth to it.
     */
    fun build(img: Bitmap, depth: DepthMap, region: RectF = RectF(0f, 0f, 1f, 1f)): LayeredImage {
        val w = img.width
        val h = img.height
        val qw = ceil(w / Q.toFloat()).toInt().coerceAtLeast(2)
        val qh = ceil(h / Q.toFloat()).toInt().coerceAtLeast(2)
        val d = FloatArray(qw * qh)
        for (y in 0 until qh) for (x in 0 until qw) {
            d[y * qw + x] = depth.at(region.left + (x + 0.5f) / qw * region.width(), region.top + (y + 0.5f) / qh * region.height())
        }
        val centres = bands(d)
        if (centres.size < 2) return LayeredImage(img.copy(Bitmap.Config.ARGB_8888, true), emptyList())
        val n = centres.size
        val thresholds = FloatArray(n) { if (it == 0) 0f else (centres[it - 1] + centres[it]) / 2f }

        // Coverage of each layer (layer i covers everything at least as near as band i).
        val cover = Array(n) { i ->
            if (i == 0) FloatArray(d.size) { 1f } else FloatArray(d.size) { smooth(thresholds[i] - EDGE, thresholds[i] + EDGE, d[it]) }
        }
        // Where each layer shows its own photo pixels: everywhere the next nearer layer isn't
        // fully opaque, so at rest the stack looks exactly like the photo (no halo at edges).
        // Only where a nearer layer completely covers it does a layer use its filled-in colour.
        val band = Array(n) { i ->
            if (i + 1 >= n) FloatArray(d.size) { 1f } else FloatArray(d.size) { ((1f - cover[i + 1][it]) / 0.05f).coerceIn(0f, 1f) }
        }

        val small = Bitmap.createScaledBitmap(img, qw, qh, true)
        val px = IntArray(qw * qh)
        small.getPixels(px, 0, qw, 0, 0, qw, qh)

        val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val plain = Paint(Paint.FILTER_BITMAP_FLAG)
        val full = Rect(0, 0, w, h)
        val span = (centres.last() - centres.first()).coerceAtLeast(1e-3f)

        var base: Bitmap? = null
        val layers = ArrayList<BgLayer>()
        for (i in 0 until n) {
            // Colour: the photo where this band is, its own surroundings carried in elsewhere.
            val fill = pushPull(px, band[i], qw, qh)
            val fillSmall = Bitmap.createBitmap(fill, qw, qh, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(fillSmall, w, h, true)
            val colour = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
            if (fillSmall !== colour) fillSmall.recycle()
            val own = img.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(own).drawBitmap(alphaMask(band[i], qw, qh), null, full, maskPaint)
            Canvas(colour).drawBitmap(own, 0f, 0f, plain)
            own.recycle()
            if (i == 0) {
                base = colour
                continue
            }
            Canvas(colour).drawBitmap(alphaMask(cover[i], qw, qh), null, full, maskPaint)
            // Keep only the part with something in it.
            val box = bounds(cover[i], qw, qh) ?: continue
            val r = Rect(box.left * Q, box.top * Q, minOf(box.right * Q, w), minOf(box.bottom * Q, h))
            if (r.width() <= 0 || r.height() <= 0) continue
            val cut = Bitmap.createBitmap(colour, r.left, r.top, r.width(), r.height())
            if (cut !== colour) colour.recycle()
            layers += BgLayer(cut, RectF(r), ((centres[i] - centres.first()) / span).coerceIn(0f, 1f))
        }
        return LayeredImage(base ?: img.copy(Bitmap.Config.ARGB_8888, true), layers)
    }

    /** Work at a quarter of the size for masks and fills. */
    private const val Q = 4
    /** Softness of layer edges, in depth units. */
    private const val EDGE = 0.035f

    /**
     * Groups depths into bands (k-means), far to near. Bands covering under 4% of the picture are
     * dropped, and so are bands too close in depth to move visibly differently.
     */
    private fun bands(d: FloatArray): FloatArray {
        val sample = FloatArray((d.size + 3) / 4) { d[(it * 4).coerceAtMost(d.size - 1)] }
        val sorted = sample.sortedArray()
        val spread = sorted[(sorted.size * 0.95f).toInt().coerceAtMost(sorted.size - 1)] - sorted[(sorted.size * 0.05f).toInt()]
        if (spread < 0.2f) return FloatArray(0)
        val k = if (spread > 0.55f) 4 else 3
        val c = FloatArray(k) { sorted[((it + 0.5f) / k * sorted.size).toInt().coerceAtMost(sorted.size - 1)] }
        val count = IntArray(k)
        repeat(12) {
            val sum = FloatArray(k)
            count.fill(0)
            for (x in sample) {
                var best = 0
                for (j in 1 until k) if (kotlin.math.abs(x - c[j]) < kotlin.math.abs(x - c[best])) best = j
                sum[best] += x
                count[best]++
            }
            for (j in 0 until k) if (count[j] > 0) c[j] = sum[j] / count[j]
        }
        val order = (0 until k).sortedBy { c[it] }
        val out = ArrayList<Float>()
        for (j in order) {
            if (count[j] < sample.size * 0.04f) continue
            if (out.isNotEmpty() && c[j] - out.last() < 0.12f) continue
            out += c[j]
        }
        return out.toFloatArray()
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun alphaMask(a: FloatArray, w: Int, h: Int): Bitmap {
        val bytes = ByteArray(a.size) { (a[it] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
    }

    /** Cells where [a] is visibly above zero, padded by two cells. */
    private fun bounds(a: FloatArray, w: Int, h: Int): Rect? {
        var l = w
        var t = h
        var r = -1
        var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (a[y * w + x] > 0.02f) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < 0) return null
        return Rect((l - 2).coerceAtLeast(0), (t - 2).coerceAtLeast(0), (r + 3).coerceAtMost(w), (b + 3).coerceAtMost(h))
    }

    /**
     * Fills every pixel from the pixels that have [weight] (push-pull): each pixel keeps its own
     * colour as far as its weight goes, the rest comes from ever coarser averages, so holes get
     * a smooth continuation of their surroundings.
     */
    private fun pushPull(px: IntArray, weight: FloatArray, w: Int, h: Int): IntArray {
        class Level(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray, val wt: FloatArray)
        val levels = ArrayList<Level>()
        run {
            val n = w * h
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            for (i in 0 until n) {
                val c = px[i]
                val wi = weight[i]
                r[i] = Color.red(c) * wi
                g[i] = Color.green(c) * wi
                b[i] = Color.blue(c) * wi
            }
            levels += Level(w, h, r, g, b, weight.copyOf())
        }
        while (levels.last().w > 1 || levels.last().h > 1) {
            val p = levels.last()
            val nw = (p.w + 1) / 2
            val nh = (p.h + 1) / 2
            val r = FloatArray(nw * nh)
            val g = FloatArray(nw * nh)
            val b = FloatArray(nw * nh)
            val wt = FloatArray(nw * nh)
            for (y in 0 until p.h) for (x in 0 until p.w) {
                val s = y * p.w + x
                val t = (y / 2) * nw + x / 2
                r[t] += p.r[s]
                g[t] += p.g[s]
                b[t] += p.b[s]
                wt[t] += p.wt[s]
            }
            for (i in wt.indices) {
                r[i] /= 4f
                g[i] /= 4f
                b[i] /= 4f
                wt[i] /= 4f
            }
            levels += Level(nw, nh, r, g, b, wt)
        }
        // Pull: from the coarsest level down, each level's own colour where it has weight,
        // the (interpolated) coarser fill elsewhere.
        var fr = FloatArray(1)
        var fg = FloatArray(1)
        var fb = FloatArray(1)
        run {
            val top = levels.last()
            val wt = top.wt[0].coerceAtLeast(1e-6f)
            fr[0] = top.r[0] / wt
            fg[0] = top.g[0] / wt
            fb[0] = top.b[0] / wt
        }
        for (li in levels.size - 2 downTo 0) {
            val l = levels[li]
            val c = levels[li + 1]
            val nr = FloatArray(l.w * l.h)
            val ng = FloatArray(l.w * l.h)
            val nb = FloatArray(l.w * l.h)
            for (y in 0 until l.h) for (x in 0 until l.w) {
                val i = y * l.w + x
                // Coarser fill at this pixel (bilinear).
                val cx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (c.w - 1).toFloat())
                val cy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (c.h - 1).toFloat())
                val x0 = cx.toInt()
                val y0 = cy.toInt()
                val x1 = minOf(x0 + 1, c.w - 1)
                val y1 = minOf(y0 + 1, c.h - 1)
                val ax = cx - x0
                val ay = cy - y0
                val i00 = y0 * c.w + x0
                val i01 = y0 * c.w + x1
                val i10 = y1 * c.w + x0
                val i11 = y1 * c.w + x1
                fun lerp(a: FloatArray) = (a[i00] * (1 - ax) + a[i01] * ax) * (1 - ay) + (a[i10] * (1 - ax) + a[i11] * ax) * ay
                val ur = lerp(fr)
                val ug = lerp(fg)
                val ub = lerp(fb)
                val wt = l.wt[i]
                val a = wt.coerceAtMost(1f)
                if (wt > 1e-6f) {
                    nr[i] = l.r[i] / wt * a + ur * (1 - a)
                    ng[i] = l.g[i] / wt * a + ug * (1 - a)
                    nb[i] = l.b[i] / wt * a + ub * (1 - a)
                } else {
                    nr[i] = ur
                    ng[i] = ug
                    nb[i] = ub
                }
            }
            fr = nr
            fg = ng
            fb = nb
        }
        return IntArray(w * h) { Color.rgb(fr[it].toInt().coerceIn(0, 255), fg[it].toInt().coerceIn(0, 255), fb[it].toInt().coerceIn(0, 255)) }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (k in -r..r) {
                val xx = x + k
                if (xx in 0 until w) {
                    s += src[y * w + xx]
                    n++
                }
            }
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (k in -r..r) {
                val yy = y + k
                if (yy in 0 until h) {
                    s += tmp[yy * w + x]
                    n++
                }
            }
            out[y * w + x] = s / n
        }
        return out
    }
}
