package app.lawnchair.metro.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.ColorUtils
import java.util.concurrent.Executors

/**
 * Legibility for Start and the app list: instead of tinting and shadowing each tile, the
 * background is dimmed once, by an amount set from how bright it is. Dark backgrounds get no
 * dim at all; very bright ones up to about 45%.
 *
 * Brightness is judged by the brightest part of the picture (the 80th percentile of pixel
 * luminance), not the average, so a dark photo with a bright sky band still gets dimmed enough
 * for the tiles sitting on the sky.
 *
 * Levels: 0 off, 1 auto, 2 auto + stronger.
 */
object BackgroundDim {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Dim (0..1 alpha of black) for a background of brightness [luminance] at [level]. */
    @JvmStatic
    fun dimFor(luminance: Float, level: Int): Float {
        if (level <= 0) return 0f
        return if (level == 1) {
            (((luminance - 0.30f) / 0.55f).coerceIn(0f, 1f) * 0.45f)
        } else {
            (((luminance - 0.20f) / 0.55f).coerceIn(0f, 1f) * 0.55f)
        }
    }

    /** 80th-percentile luminance of [bmp], 0..1. Samples a small grid, so it is cheap. */
    @JvmStatic
    fun luminanceOf(bmp: Bitmap): Float {
        val n = 48
        val small = runCatching { Bitmap.createScaledBitmap(bmp, n, n, true) }.getOrNull() ?: return 0f
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        if (small !== bmp) small.recycle()
        val lum = FloatArray(px.size) { i -> ColorUtils.calculateLuminance(px[i] or 0xFF000000.toInt()).toFloat() }
        lum.sort()
        // Perceived brightness: relative luminance is very non-linear, lift it towards L*.
        val raw = lum[(lum.size * 0.8f).toInt().coerceAtMost(lum.size - 1)]
        return perceived(raw)
    }

    /**
     * Brightness of the system wallpaper. Android no longer lets launchers read the wallpaper
     * image, so this uses the colours Android publishes for it: whether it is light enough for
     * dark text, and its main colours.
     */
    @JvmStatic
    fun systemLuminance(context: Context): Float {
        val colors: WallpaperColors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull() ?: return 0.5f
        val tones = listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
            .map { perceived(ColorUtils.calculateLuminance(it.toArgb()).toFloat()) }
        var l = tones.maxOrNull() ?: 0.5f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        ) {
            l = maxOf(l, 0.85f)
        }
        return l
    }

    /** Relative luminance to perceived lightness, 0..1. */
    private fun perceived(y: Float): Float {
        // CIE L* from relative luminance.
        val l =if (y > 0.008856f) 116f * Math.cbrt(y.toDouble()).toFloat() - 16f else 903.3f * y
        return (l / 100f).coerceIn(0f, 1f)
    }

    /** Works out the brightness on a background thread; [onResult] runs on the main thread. */
    @JvmStatic
    fun measure(context: Context, photo: Bitmap?, onResult: (Float) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val l = runCatching { if (photo != null) luminanceOf(photo) else systemLuminance(app) }.getOrDefault(0.5f)
            main.post { onResult(l) }
        }
    }
}
