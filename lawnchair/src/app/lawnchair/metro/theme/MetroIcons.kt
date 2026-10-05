package app.lawnchair.metro.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Loads app icons the Metro way, shared by Start tiles and the app list.
 *
 * Prefers the app's monochrome (themed) layer, drawn white on the tile colour like Windows
 * Phone glyphs, and falls back to the normal full-colour icon. Every icon is cropped to its
 * visible artwork so all apps can be drawn at the same visual size.
 */
object MetroIcons {

    /**
     * [brandColor] is the most prominent saturated colour of the app's full-colour icon
     * (WhatsApp green, Spotify green, X blue…), or 0 when the icon has no clear colour.
     */
    class Icon(val bitmap: Bitmap?, val monochrome: Boolean, val label: CharSequence, val brandColor: Int)

    private val cache = LruCache<String, Icon>(400)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private const val RENDER_SIZE = 192

    private fun key(cn: ComponentName, user: UserHandle) = cn.flattenToShortString() + "#" + user.hashCode()

    /** Returns the cached icon, or null and delivers it to [onLoaded] on the main thread later. */
    @JvmStatic
    fun get(
        context: Context,
        component: ComponentName,
        user: UserHandle = Process.myUserHandle(),
        onLoaded: (Icon) -> Unit,
    ): Icon? {
        val k = key(component, user)
        cache.get(k)?.let { return it }
        val app = context.applicationContext
        executor.execute {
            val icon = cache.get(k) ?: load(app, component, user).also { cache.put(k, it) }
            main.post { onLoaded(icon) }
        }
        return null
    }

    /** Drops cached icons, e.g. after an app update or theme change. */
    @JvmStatic
    fun clear() = cache.evictAll()

    private fun load(context: Context, target: ComponentName, user: UserHandle): Icon {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val info = runCatching {
            val list = launcherApps?.getActivityList(target.packageName, user)
            list?.firstOrNull { it.componentName == target } ?: list?.firstOrNull()
        }.getOrNull()
        val density = context.resources.displayMetrics.densityDpi
        val full = runCatching { info?.getIcon(density) }.getOrNull()
        var mono: Drawable? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && full is AdaptiveIconDrawable) {
            mono = full.monochrome
        }
        val label = runCatching { info?.label }.getOrNull() ?: target.packageName
        val bitmap = runCatching { (mono ?: full)?.let(::renderTrimmed) }.getOrNull()
        val brand = runCatching { full?.let(::brandColorOf) }.getOrNull() ?: 0
        return Icon(bitmap, mono != null, label, brand)
    }

    /**
     * Finds the icon's brand colour: bucket saturated pixels by hue, take the strongest bucket,
     * average its colour and lift it to a bright, glow-friendly tone.
     */
    private fun brandColorOf(d: Drawable): Int {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        val buckets = 24
        val weight = FloatArray(buckets)
        val sumR = FloatArray(buckets)
        val sumG = FloatArray(buckets)
        val sumB = FloatArray(buckets)
        val hsv = FloatArray(3)
        for (c in px) {
            if ((c ushr 24) < 200) continue
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue // greys, whites, blacks
            val b = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            val w = hsv[1] * hsv[2]
            weight[b] += w
            sumR[b] += ((c shr 16) and 0xFF) * w
            sumG[b] += ((c shr 8) and 0xFF) * w
            sumB[b] += (c and 0xFF) * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return 0
        if (weight[best] < 20f) return 0 // too little colour to call it a brand colour
        val avg = android.graphics.Color.rgb(
            (sumR[best] / weight[best]).toInt(),
            (sumG[best] / weight[best]).toInt(),
            (sumB[best] / weight[best]).toInt(),
        )
        android.graphics.Color.colorToHSV(avg, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.6f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    /** Draws [d] into a bitmap and crops away transparent padding, leaving only the artwork. */
    private fun renderTrimmed(d: Drawable): Bitmap {
        val full = Bitmap.createBitmap(RENDER_SIZE, RENDER_SIZE, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, RENDER_SIZE, RENDER_SIZE)
        d.draw(Canvas(full))
        val px = IntArray(RENDER_SIZE * RENDER_SIZE)
        full.getPixels(px, 0, RENDER_SIZE, 0, 0, RENDER_SIZE, RENDER_SIZE)
        var minX = RENDER_SIZE
        var minY = RENDER_SIZE
        var maxX = -1
        var maxY = -1
        for (y in 0 until RENDER_SIZE) {
            val row = y * RENDER_SIZE
            for (x in 0 until RENDER_SIZE) {
                if ((px[row + x] ushr 24) > 24) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return full
        return Bitmap.createBitmap(full, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}
