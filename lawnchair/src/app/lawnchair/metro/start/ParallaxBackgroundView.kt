package app.lawnchair.metro.start

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.io.File

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
    private val travel = 0.12f

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
        bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        invalidate()
    }

    val hasImage get() = bitmap != null

    /** 0 at the top of Start, 1 at the bottom. Only moves the view: no redraw needed. */
    fun setScrollFraction(fraction: Float) {
        progress = fraction
        translationY = -fraction * (height - height / (1 + travel))
    }

    /** Taller than the screen by [travel], so there is photo left to reveal while scrolling. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, (h * (1 + travel)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
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
        @JvmStatic
        fun file(context: Context) = File(context.filesDir, "metro_start_background.jpg")
    }
}
