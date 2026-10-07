package app.lawnchair.metro.start

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import app.lawnchair.metro.theme.BgLayer
import app.lawnchair.metro.theme.LayeredImage
import app.lawnchair.preferences.PreferenceManager
import java.io.File
import kotlin.math.abs

/**
 * The Start screen's own background, for Windows Phone 8.1's parallax: the picture is a little
 * taller than the screen and drifts upwards at a fraction of the scroll speed, so the tiles look
 * like windows sliding over a scene further away.
 *
 * With depth on, the picture is split into layers (see DepthLayers and GradientGen): nearer
 * layers drift further than the base, so the scene itself has depth. Every layer is drawn once;
 * scrolling only moves them (view translations on the graphics chip), nothing is redrawn.
 *
 * Android doesn't let launchers read the system wallpaper image any more, so true parallax
 * needs a picture chosen in Metro's settings; it's stored privately in the app.
 */
class ParallaxBackgroundView(context: Context) : FrameLayout(context) {

    private var main: Stack? = null
    private var progress = 0f
    private var pan = 0f
    private var blurRadius = 0f

    fun load() {
        main?.let { removeView(it) }
        main = null
        val file = file(context)
        val depth = PreferenceManager.getInstance(context).metroBackgroundDepth.get() && LayeredImage.exists(context)
        val size = (if (depth) LayeredImage.baseSize(context) else null) ?: jpgSize(file)
        if (size == null) {
            dimBaked = false
            return
        }
        val dm = resources.displayMetrics
        var sample = 1
        val targetH = (dm.heightPixels * (1 + TRAVEL_Y)).toInt()
        while (size.first / (sample * 2) >= dm.widthPixels && size.second / (sample * 2) >= targetH) sample *= 2
        fun flat(): LayeredImage? {
            val s = jpgSize(file) ?: return null
            var k = 1
            while (s.first / (k * 2) >= dm.widthPixels && s.second / (k * 2) >= targetH) k *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = k; inMutable = true })
                ?.let { LayeredImage(it, emptyList()) }
        }
        // Saved layers that won't load fall back to the flat picture.
        val image = (if (depth) LayeredImage.load(context, sample) else null) ?: flat() ?: return
        main = Stack(context, image).also { addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        dimBaked = false
        applyOffsets()
    }

    private fun jpgSize(file: File): Pair<Int, Int>? {
        if (!file.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return if (o.outWidth > 0) o.outWidth to o.outHeight else null
    }

    val hasImage get() = main != null

    /** The picture as it looks at rest (layers included), for measuring its brightness. */
    val image: Bitmap?
        get() {
            val img = main?.image ?: return null
            return if (img.layers.isEmpty()) img.base else img.flatten()
        }

    /** True once the legibility dim has been painted into the picture itself. */
    var dimBaked = false
        private set

    /**
     * Paints the dim straight into the picture (every layer), once, instead of blending a
     * separate full-screen layer on every frame while Start scrolls.
     */
    fun bakeDim(alpha: Float) {
        val stack = main ?: return
        if (dimBaked) return
        dimBaked = true
        if (alpha <= 0f) return
        stack.replaceImage(stack.image.bakeDim(alpha))
        applyOffsets() // the new layer views start unmoved
    }

    private val travelPx get() = height - height / (1 + TRAVEL_Y)
    private val travelXPx get() = width - width / (1 + TRAVEL_X)

    /** 0 at the top of Start, 1 at the bottom. Only moves views: no redraw needed. */
    fun setScrollFraction(fraction: Float) {
        progress = fraction
        translationY = -fraction * travelPx
        applyOffsets()
    }

    /** 0 on Start, 1 with the app list open: the picture drifts left as the list slides in. */
    fun setPanFraction(fraction: Float) {
        pan = fraction
        translationX = -fraction * travelXPx
        applyOffsets()
    }

    private fun applyOffsets() {
        val dy = progress * travelPx
        val dx = pan * travelXPx
        for (i in 0 until childCount) (getChildAt(i) as? Stack)?.offset(dx, dy)
    }

    /** Frosted-glass blur behind the app list (Android 12+). */
    fun setBlur(radius: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        if (abs(radius - blurRadius) < 0.5f) return
        blurRadius = radius
        setRenderEffect(
            if (radius < 1f) null else android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP),
        )
    }

    /** Larger than the screen by [TRAVEL_Y] and [TRAVEL_X], so there is picture left to reveal. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (MeasureSpec.getSize(widthMeasureSpec) * (1 + TRAVEL_X)).toInt()
        val h = (MeasureSpec.getSize(heightMeasureSpec) * (1 + TRAVEL_Y)).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setScrollFraction(progress)
        setPanFraction(pan)
    }

    // ---- Album gradient (gradient backgrounds while music plays) ----

    private var overlay: Stack? = null

    /**
     * Crossfades (1s) to [image] over the saved background, or back to the saved background
     * when null. The image should already carry its dim.
     */
    fun showOverlay(image: LayeredImage?) {
        val old = overlay
        // Album pictures from earlier songs still fading: only the newest one needs to stay.
        (0 until childCount).map { getChildAt(it) }.filter { it is Stack && it !== main && it !== old }.forEach {
            it.animate().cancel()
            removeView(it)
        }
        if (image == null) {
            if (old == null) return
            overlay = null
            main?.visibility = View.VISIBLE
            old.animate().alpha(0f).setDuration(1000).withEndAction { removeView(old) }.start()
            return
        }
        val next = Stack(context, image).apply { alpha = 0f }
        addView(next, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        overlay = next
        applyOffsets()
        next.animate().alpha(1f).setDuration(1000).withEndAction {
            // Fully covered: the saved background and any earlier album stop drawing.
            if (old != null) removeView(old)
            if (overlay === next) main?.visibility = View.INVISIBLE
        }.start()
    }

    /**
     * One picture and its layers. The base is drawn by this view (centre-cropped to fill it);
     * each depth layer is a child view placed over the same spot, moved further than the base
     * while scrolling the nearer it is.
     */
    private class Stack(context: Context, var image: LayeredImage) : FrameLayout(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()

        init {
            setWillNotDraw(false)
            // Layers draw their extended edges beyond their own bounds while they drift.
            clipChildren = false
            addLayers()
        }

        fun replaceImage(newImage: LayeredImage) {
            image = newImage
            removeAllViews()
            addLayers()
            invalidate()
        }

        private fun addLayers() {
            for (l in image.layers) addView(LayerView(context, this, l), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        /** Base pixels to view pixels: centre-crop of the base into this view. */
        val scale: Float get() = maxOf(width.toFloat() / image.base.width, height.toFloat() / image.base.height)
        val originX: Float get() = (width - image.base.width * scale) / 2f
        val originY: Float get() = (height - image.base.height * scale) / 2f

        /** The base has moved by ([dx], [dy]): nearer layers move further, by up to [NEAR_EXTRA]. */
        fun offset(dx: Float, dy: Float) {
            for (i in 0 until childCount) {
                val v = getChildAt(i) as? LayerView ?: continue
                val extra = v.layer.depth * NEAR_EXTRA
                v.translationX = -dx * extra
                v.translationY = -dy * extra
            }
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = image.base
            val s = scale
            dst.set(originX, originY, originX + bmp.width * s, originY + bmp.height * s)
            canvas.drawBitmap(bmp, null, dst, paint)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            for (i in 0 until childCount) getChildAt(i).invalidate()
        }
    }

    /**
     * A depth layer. Its edges are extended (the outermost pixels repeated) by as far as it can
     * drift, so a layer touching the picture's edge never pulls away from it.
     */
    private class LayerView(context: Context, private val stack: Stack, val layer: BgLayer) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val shader = BitmapShader(layer.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val m = Matrix()
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val s = stack.scale
            r.set(
                stack.originX + layer.rect.left * s, stack.originY + layer.rect.top * s,
                stack.originX + layer.rect.right * s, stack.originY + layer.rect.bottom * s,
            )
            m.setScale(r.width() / layer.bitmap.width, r.height() / layer.bitmap.height)
            m.postTranslate(r.left, r.top)
            shader.setLocalMatrix(m)
            paint.shader = shader
            val margin = maxOf(width, height) * maxOf(TRAVEL_X, TRAVEL_Y) * NEAR_EXTRA * layer.depth + 2f
            canvas.drawRect(r.left - margin, r.top - margin, r.right + margin, r.bottom + margin, paint)
        }
    }

    companion object {
        /** Extra picture height and width beyond the screen, for the vertical and sideways drift. */
        const val TRAVEL_Y = 0.12f
        const val TRAVEL_X = 0.10f

        /** The nearest layer drifts this much further than the base (0.45 = 45% more). */
        const val NEAR_EXTRA = 0.45f

        @JvmStatic
        fun file(context: Context) = File(context.filesDir, "metro_start_background.jpg")
    }
}
