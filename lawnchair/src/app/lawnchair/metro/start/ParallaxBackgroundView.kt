package app.lawnchair.metro.start

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.io.File
import kotlin.math.abs

/**
 * The Start screen's own background photo, for Windows Phone 8.1's parallax: the photo is
 * a little taller than the screen and drifts upwards at a fraction of the scroll speed, so the
 * tiles look like windows sliding over a scene further away.
 *
 * Android doesn't let launchers read the system wallpaper image any more, so true parallax
 * needs a photo chosen in Metro's settings; it's stored privately in the app.
 */
class ParallaxBackgroundView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var progress = 0f

    /** How much taller than the screen the photo is drawn, as a fraction of the height. */
    private val travel = TRAVEL_Y

    /** How much wider than the screen, for the sideways drift when the app list slides in. */
    private val travelX = TRAVEL_X
    private var blurRadius = 0f

    fun load() {
        val file = file(context)
        if (!file.exists()) {
            bitmap = null
            return
        }
        val dm = resources.displayMetrics
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        var sample = 1
        val targetH = (dm.heightPixels * (1 + travel)).toInt()
        while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= targetH) sample *= 2
        bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true })
        dimBaked = false
        invalidate()
    }

    val hasImage get() = bitmap != null

    /** The loaded photo, for measuring its brightness. */
    val image: Bitmap? get() = bitmap

    /** True once the legibility dim has been painted into the photo itself. */
    var dimBaked = false
        private set

    /**
     * Paints the dim straight into the photo, once, instead of blending a separate full-screen
     * layer on every frame while Start scrolls.
     */
    fun bakeDim(alpha: Float) {
        val bmp = bitmap ?: return
        if (dimBaked) return
        dimBaked = true
        if (alpha <= 0f) return
        val target = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true).also { bitmap = it }
        Canvas(target).drawColor(android.graphics.Color.argb((alpha * 255).toInt(), 0, 0, 0))
        invalidate()
    }

    /** 0 at the top of Start, 1 at the bottom. Only moves the view: no redraw needed. */
    fun setScrollFraction(fraction: Float) {
        progress = fraction
        translationY = -fraction * (height - height / (1 + travel))
    }

    /** 0 on Start, 1 with the app list open: the photo drifts left as the list slides in. */
    fun setPanFraction(fraction: Float) {
        translationX = -fraction * (width - width / (1 + travelX))
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

    /** Larger than the screen by [travel] and [travelX], so there is photo left to reveal. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension((w * (1 + travelX)).toInt(), (h * (1 + travel)).toInt())
    }

    // ---- Album gradient (gradient backgrounds while music plays) ----

    private var overlay: Bitmap? = null
    private var overlayAlpha = 0f
    private var overlayAnim: android.animation.ValueAnimator? = null
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Crossfades (1s) to [bmp] drawn over the saved background, or back to the saved background
     * when [bmp] is null. The bitmap should already carry its dim.
     */
    fun showOverlay(bmp: Bitmap?) {
        overlayAnim?.cancel()
        if (bmp != null) {
            // From whatever shows now: draw the old overlay into the base of the fade.
            val from = if (overlay != null && overlayAlpha > 0f) overlayAlpha else 0f
            if (overlay != null && from > 0f) {
                // Fade the new one in over the current overlay by swapping once it's covered.
                previousOverlay = overlay
                previousAlpha = from
            }
            overlay = bmp
            overlayAlpha = 0f
            animateOverlay(1f) { previousOverlay = null }
        } else {
            if (overlay == null) return
            animateOverlay(0f) {
                overlay = null
                previousOverlay = null
            }
        }
    }

    private var previousOverlay: Bitmap? = null
    private var previousAlpha = 0f

    private fun animateOverlay(to: Float, end: () -> Unit) {
        overlayAnim = android.animation.ValueAnimator.ofFloat(overlayAlpha, to).apply {
            duration = 1000
            addUpdateListener {
                overlayAlpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
    }

    private fun drawFill(canvas: Canvas, bmp: Bitmap, paint: Paint) {
        val boxW = width.toFloat()
        val boxH = height.toFloat()
        val scale = maxOf(boxW / bmp.width, boxH / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        dst.set((boxW - w) / 2f, (boxH - h) / 2f, (boxW - w) / 2f + w, (boxH - h) / 2f + h)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val ov = overlay
        if (ov != null && overlayAlpha >= 1f && previousOverlay == null) {
            // Fully covered: draw only the album gradient (one full-screen bitmap per frame).
            overlayPaint.alpha = 255
            drawFill(canvas, ov, overlayPaint)
            return
        }
        drawBase(canvas)
        previousOverlay?.let {
            overlayPaint.alpha = (previousAlpha * 255).toInt()
            drawFill(canvas, it, overlayPaint)
        }
        overlay?.let {
            if (overlayAlpha > 0f) {
                overlayPaint.alpha = (overlayAlpha * 255).toInt()
                drawFill(canvas, it, overlayPaint)
            }
        }
    }

    private fun drawBase(canvas: Canvas) {
        val bmp = bitmap ?: return
        // Centre-crop into the whole (taller-than-screen) view.
        val boxW = width.toFloat()
        val boxH = height.toFloat()
        val scale = maxOf(boxW / bmp.width, boxH / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        dst.set((boxW - w) / 2f, (boxH - h) / 2f, (boxW - w) / 2f + w, (boxH - h) / 2f + h)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setScrollFraction(progress)
    }

    companion object {
        /** Extra photo height and width beyond the screen, for the vertical and sideways drift. */
        const val TRAVEL_Y = 0.12f
        const val TRAVEL_X = 0.10f

        @JvmStatic
        fun file(context: Context) = File(context.filesDir, "metro_start_background.jpg")
    }
}
