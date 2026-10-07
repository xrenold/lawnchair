package app.lawnchair.metro.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One layer of a depth background: a picture (with transparency) placed over part of the base.
 *
 * [rect] is where it sits, in the base picture's pixels; the bitmap is scaled to fill it (glow
 * layers are stored small and drawn large). [depth] runs from 0 (as far as the base) to 1
 * (nearest): nearer layers drift further as Start scrolls.
 */
class BgLayer(val bitmap: Bitmap, val rect: RectF, val depth: Float)

/**
 * A background split into depth layers: the [base] fills the screen (with whatever was behind
 * nearer things filled in), and [layers] sit over it, far to near.
 */
class LayeredImage(val base: Bitmap, val layers: List<BgLayer>) {

    /** Everything drawn together, as one flat picture (for depth off, and the phone wallpaper). */
    fun flatten(): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        if (layers.isEmpty()) return out
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in layers) c.drawBitmap(l.bitmap, null, l.rect, p)
        return out
    }

    /**
     * The part [crop] (in base pixels) scaled by [scale]: what's saved as Start's background.
     * Layers are cut to the same part and keep their depth.
     */
    fun crop(crop: RectF, scale: Float): LayeredImage {
        val l = crop.left.toInt().coerceIn(0, base.width - 1)
        val t = crop.top.toInt().coerceIn(0, base.height - 1)
        val w = crop.width().toInt().coerceIn(1, base.width - l)
        val h = crop.height().toInt().coerceIn(1, base.height - t)
        val cut = Bitmap.createBitmap(base, l, t, w, h)
        val newBase = if (scale < 1f) Bitmap.createScaledBitmap(cut, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true) else cut
        val k = newBase.width.toFloat() / w
        val area = RectF(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat())
        val out = layers.mapNotNull { layer ->
            if (!RectF.intersects(layer.rect, area)) return@mapNotNull null
            BgLayer(
                layer.bitmap,
                RectF((layer.rect.left - l) * k, (layer.rect.top - t) * k, (layer.rect.right - l) * k, (layer.rect.bottom - t) * k),
                layer.depth,
            )
        }
        return LayeredImage(newBase, out)
    }

    /** Paints the legibility dim into every layer (transparent parts stay transparent). */
    fun bakeDim(alpha: Float): LayeredImage {
        if (alpha <= 0f) return this
        val a = (alpha * 255).toInt()
        fun dim(b: Bitmap, atop: Boolean): Bitmap {
            val m = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
            val p = Paint().apply {
                color = Color.argb(a, 0, 0, 0)
                if (atop) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            }
            Canvas(m).drawPaint(p)
            return m
        }
        return LayeredImage(dim(base, false), layers.map { BgLayer(dim(it.bitmap, true), it.rect, it.depth) })
    }

    companion object {
        private fun dir(context: Context) = File(context.filesDir, "metro_bg_layers")

        /** Saves [image] as Start's depth background (replacing any earlier one). */
        fun save(context: Context, image: LayeredImage) {
            val d = dir(context)
            d.deleteRecursively()
            d.mkdirs()
            File(d, "base.jpg").outputStream().use { image.base.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            val arr = JSONArray()
            image.layers.forEachIndexed { i, l ->
                val name = "layer_$i.png"
                File(d, name).outputStream().use { l.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                arr.put(
                    JSONObject().put("file", name).put("l", l.rect.left.toDouble()).put("t", l.rect.top.toDouble())
                        .put("r", l.rect.right.toDouble()).put("b", l.rect.bottom.toDouble()).put("depth", l.depth.toDouble()),
                )
            }
            val meta = JSONObject().put("w", image.base.width).put("h", image.base.height).put("layers", arr)
            // Written last: a background is only used once its description exists.
            File(d, "meta.json").writeText(meta.toString())
        }

        fun clear(context: Context) {
            dir(context).deleteRecursively()
        }

        fun exists(context: Context) = File(dir(context), "meta.json").exists()

        /**
         * Loads the saved depth background, with the base decoded at [sample] (1, 2, 4…).
         * Layers come back mutable, ready for the dim to be painted in.
         */
        fun load(context: Context, sample: Int): LayeredImage? = runCatching {
            val d = dir(context)
            val meta = JSONObject(File(d, "meta.json").readText())
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }
            val base = BitmapFactory.decodeFile(File(d, "base.jpg").path, opts) ?: return null
            val k = base.width.toFloat() / meta.getInt("w")
            val arr = meta.getJSONArray("layers")
            val layers = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val bmp = BitmapFactory.decodeFile(File(d, o.getString("file")).path, opts) ?: return@mapNotNull null
                BgLayer(
                    bmp,
                    RectF(o.getDouble("l").toFloat() * k, o.getDouble("t").toFloat() * k, o.getDouble("r").toFloat() * k, o.getDouble("b").toFloat() * k),
                    o.getDouble("depth").toFloat(),
                )
            }
            LayeredImage(base, layers)
        }.getOrNull()

        /** Size of the saved base (for choosing a decode sample), or null. */
        fun baseSize(context: Context): Pair<Int, Int>? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(File(dir(context), "base.jpg").path, o)
            if (o.outWidth > 0) o.outWidth to o.outHeight else null
        }.getOrNull()
    }
}
