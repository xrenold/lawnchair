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
 * 2. [build]: the photo is cut along its sharpest depth outlines (a subject against what's
 *    behind it, a skyline), never across smooth slopes, and each cut is snapped to the photo's
 *    own edges. Behind each cut-out the picture is filled with a soft continuation of its
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
        val small = Bitmap.createScaledBitmap(img, qw, qh, true)
        val px = IntArray(qw * qh)
        small.getPixels(px, 0, qw, 0, 0, qw, qh)
        val gray = FloatArray(px.size) { (Color.red(px[it]) * 0.299f + Color.green(px[it]) * 0.587f + Color.blue(px[it]) * 0.114f) / 255f }

        // Cut only where depth jumps (a silhouette), never across a smooth slope like the ground
        // or the sky, which would tear as the layers move apart.
        val dsm = boxBlur(d, qw, qh, 1)
        val cuts = sharpestCuts(dsm, qw, qh)
        if (cuts.isEmpty()) return LayeredImage(img.copy(Bitmap.Config.ARGB_8888, true), emptyList())
        // Each cut's shape, snapped to the photo's own edges (the depth map is coarse), then made
        // crisp so nothing faint is left around it.
        val shapes = cuts.map { t ->
            val raw = FloatArray(dsm.size) { smooth(t - 0.02f, t + 0.02f, dsm[it]) }
            val refined = guided(gray, raw, qw, qh, 4, 1e-3f)
            FloatArray(refined.size) { smooth(0.15f, 0.85f, refined[it]) }
        }.filter { a -> a.count { it > 0.5f } > a.size * 0.02f }
        if (shapes.isEmpty()) return LayeredImage(img.copy(Bitmap.Config.ARGB_8888, true), emptyList())
        val n = shapes.size + 1
        // Coverage, far to near: layer 0 is the whole base; a nearer layer never pokes out of a
        // farther one's shape.
        val cover = Array(n) { i -> if (i == 0) FloatArray(d.size) { 1f } else shapes[i - 1].copyOf() }
        for (i in n - 2 downTo 1) for (k in cover[i].indices) cover[i][k] = maxOf(cover[i][k], cover[i + 1][k])
        val depthOf = FloatArray(n) { i -> if (i == 0) 0f else i.toFloat() / (n - 1) }

        // Where each layer shows its own photo pixels: everywhere the next nearer layer isn't
        // fully opaque, so at rest the stack looks exactly like the photo (no halo at edges).
        // Only where a nearer layer completely covers it does a layer use its filled-in colour.
        val band = Array(n) { i ->
            if (i + 1 >= n) FloatArray(d.size) { 1f } else FloatArray(d.size) { ((1f - cover[i + 1][it]) / 0.05f).coerceIn(0f, 1f) }
        }

        // Every layer is computed pixel by pixel (colour and transparency), at full size. Masks
        // drawn with Canvas blend modes turned out to be ignored on some phones, leaving each
        // layer a solid rectangle, so nothing here relies on them.
        val imgPx = IntArray(w * h)
        img.getPixels(imgPx, 0, w, 0, 0, w, h)
        // Bilinear lookup from the quarter-size grid, prepared once per row and column.
        val x0 = IntArray(w)
        val x1 = IntArray(w)
        val ax = FloatArray(w)
        for (x in 0 until w) {
            val f = ((x + 0.5f) / w * qw - 0.5f).coerceIn(0f, (qw - 1).toFloat())
            x0[x] = f.toInt()
            x1[x] = minOf(x0[x] + 1, qw - 1)
            ax[x] = f - x0[x]
        }
        val y0 = IntArray(h)
        val y1 = IntArray(h)
        val ay = FloatArray(h)
        for (y in 0 until h) {
            val f = ((y + 0.5f) / h * qh - 0.5f).coerceIn(0f, (qh - 1).toFloat())
            y0[y] = f.toInt()
            y1[y] = minOf(y0[y] + 1, qh - 1)
            ay[y] = f - y0[y]
        }

        var base: Bitmap? = null
        val layers = ArrayList<BgLayer>()
        for (i in 0 until n) {
            // Colour: the photo where this layer shows its own pixels, its own surroundings
            // carried in (push-pull fill) where a nearer layer covers it completely.
            val fill = pushPull(px, band[i], qw, qh)
            val bnd = band[i]
            val cov = cover[i]
            // Layers keep only the part with something in it.
            val box = if (i == 0) Rect(0, 0, qw, qh) else bounds(cov, qw, qh) ?: continue
            val r = Rect(box.left * Q, box.top * Q, minOf(box.right * Q, w), minOf(box.bottom * Q, h))
            if (r.width() <= 0 || r.height() <= 0) continue
            val rw = r.width()
            val out = IntArray(rw * r.height())
            for (y in r.top until r.bottom) {
                val ra = y0[y] * qw
                val rb = y1[y] * qw
                val fy = ay[y]
                for (x in r.left until r.right) {
                    val i00 = ra + x0[x]
                    val i01 = ra + x1[x]
                    val i10 = rb + x0[x]
                    val i11 = rb + x1[x]
                    val fx = ax[x]
                    val w00 = (1 - fx) * (1 - fy)
                    val w01 = fx * (1 - fy)
                    val w10 = (1 - fx) * fy
                    val w11 = fx * fy
                    val b = bnd[i00] * w00 + bnd[i01] * w01 + bnd[i10] * w10 + bnd[i11] * w11
                    val c00 = fill[i00]
                    val c01 = fill[i01]
                    val c10 = fill[i10]
                    val c11 = fill[i11]
                    val fr = Color.red(c00) * w00 + Color.red(c01) * w01 + Color.red(c10) * w10 + Color.red(c11) * w11
                    val fg = Color.green(c00) * w00 + Color.green(c01) * w01 + Color.green(c10) * w10 + Color.green(c11) * w11
                    val fb = Color.blue(c00) * w00 + Color.blue(c01) * w01 + Color.blue(c10) * w10 + Color.blue(c11) * w11
                    val p = imgPx[y * w + x]
                    val cr = (Color.red(p) * b + fr * (1 - b)).toInt().coerceIn(0, 255)
                    val cg = (Color.green(p) * b + fg * (1 - b)).toInt().coerceIn(0, 255)
                    val cb = (Color.blue(p) * b + fb * (1 - b)).toInt().coerceIn(0, 255)
                    val a = if (i == 0) 255 else ((cov[i00] * w00 + cov[i01] * w01 + cov[i10] * w10 + cov[i11] * w11) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    out[(y - r.top) * rw + (x - r.left)] = (a shl 24) or (cr shl 16) or (cg shl 8) or cb
                }
            }
            // Colours given unpremultiplied; Android stores them premultiplied.
            val bmp = Bitmap.createBitmap(out, rw, r.height(), Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            if (i == 0) {
                base = bmp
                continue
            }
            bmp.setHasAlpha(true)
            // A nearer layer drifts up and left of the base; where it runs into the picture's
            // bottom or right edge, continue it with a mirror image so no gap opens there.
            val extB = if (r.bottom >= h) (h * 0.06f).toInt().coerceAtMost(bmp.height) else 0
            val extR = if (r.right >= w) (w * 0.05f).toInt().coerceAtMost(bmp.width) else 0
            val layerBmp = if (extB == 0 && extR == 0) bmp else mirrorExtend(bmp, extR, extB).also { bmp.recycle() }
            layers += BgLayer(layerBmp, RectF(r.left.toFloat(), r.top.toFloat(), (r.right + extR).toFloat(), (r.bottom + extB).toFloat()), depthOf[i])
        }
        return LayeredImage(base ?: img.copy(Bitmap.Config.ARGB_8888, true), layers)
    }

    /** Work at a quarter of the size for masks and fills. */
    private const val Q = 4
    /** Softness of layer edges, in depth units. */
    private const val EDGE = 0.035f

    /**
     * Depth levels to cut at, nearest last: where the depth changes most steeply on average along
     * the cut line (the outline of something standing in front of what's behind it). A second
     * cut is kept only if nearly as sharp and clearly at a different depth. None when the photo
     * has no real silhouette.
     */
    private fun sharpestCuts(dsm: FloatArray, w: Int, h: Int): List<Float> {
        val grad = FloatArray(dsm.size)
        val scale = minOf(w, h).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            val gx = (dsm[y * w + minOf(x + 1, w - 1)] - dsm[y * w + maxOf(x - 1, 0)]) / 2f
            val gy = (dsm[minOf(y + 1, h - 1) * w + x] - dsm[maxOf(y - 1, 0) * w + x]) / 2f
            grad[y * w + x] = kotlin.math.sqrt(gx * gx + gy * gy) * scale
        }
        val scores = ArrayList<Pair<Float, Float>>() // (score, level)
        var t = 0.08f
        while (t < 0.925f) {
            var sum = 0f
            var count = 0
            for (i in dsm.indices) if (kotlin.math.abs(dsm[i] - t) < 0.015f) {
                sum += grad[i]
                count++
            }
            if (count >= minOf(w, h) / 2) scores += (sum / count) to t
            t += 0.01f
        }
        if (scores.isEmpty()) return emptyList()
        scores.sortByDescending { it.first }
        val best = scores.first()
        if (best.first < MIN_EDGE) return emptyList()
        val second = scores.firstOrNull { it.first >= best.first * 0.7f && kotlin.math.abs(it.second - best.second) >= 0.2f }
        return listOfNotNull(best.second, second?.second).sorted()
    }

    /** Below this, the photo has no clear silhouette worth lifting off. */
    private const val MIN_EDGE = 2f

    /**
     * Guided filter (He et al.): smooths [p] while following the edges of [guide], so a shape
     * from the coarse depth map snaps to the outline actually visible in the photo.
     */
    private fun guided(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val mI = boxBlur(guide, w, h, r)
        val mP = boxBlur(p, w, h, r)
        val ip = boxBlur(FloatArray(p.size) { guide[it] * p[it] }, w, h, r)
        val ii = boxBlur(FloatArray(p.size) { guide[it] * guide[it] }, w, h, r)
        val a = FloatArray(p.size)
        val b = FloatArray(p.size)
        for (i in p.indices) {
            val cov = ip[i] - mI[i] * mP[i]
            val v = ii[i] - mI[i] * mI[i]
            a[i] = cov / (v + eps)
            b[i] = mP[i] - a[i] * mI[i]
        }
        val ma = boxBlur(a, w, h, r)
        val mb = boxBlur(b, w, h, r)
        return FloatArray(p.size) { (ma[it] * guide[it] + mb[it]).coerceIn(0f, 1f) }
    }

    /** [src] continued by mirror images of its own last [right] columns and [bottom] rows. */
    private fun mirrorExtend(src: Bitmap, right: Int, bottom: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width + right, src.height + bottom, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(src, 0f, 0f, p)
        val m = android.graphics.Matrix()
        if (bottom > 0) {
            // Flip about the bottom edge.
            m.setScale(1f, -1f)
            m.postTranslate(0f, 2f * src.height)
            c.save()
            c.clipRect(0, src.height, src.width, src.height + bottom)
            c.drawBitmap(src, m, p)
            c.restore()
        }
        if (right > 0) {
            m.setScale(-1f, 1f)
            m.postTranslate(2f * src.width, 0f)
            c.save()
            c.clipRect(src.width, 0, src.width + right, src.height)
            c.drawBitmap(src, m, p)
            c.restore()
        }
        if (right > 0 && bottom > 0) {
            m.setScale(-1f, -1f)
            m.postTranslate(2f * src.width, 2f * src.height)
            c.save()
            c.clipRect(src.width, src.height, src.width + right, src.height + bottom)
            c.drawBitmap(src, m, p)
            c.restore()
        }
        return out
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
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
