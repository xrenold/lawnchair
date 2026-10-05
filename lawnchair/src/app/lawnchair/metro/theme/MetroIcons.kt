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
import app.lawnchair.metro.data.MetroShortcuts
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
     * (WhatsApp green, Spotify green, X blue…), lifted for glows, or 0 when the icon has no
     * clear colour.
     *
     * [brandTile] is the colour for a brand-coloured tile, or 0 when the app doesn't qualify:
     * apps that came with the phone keep the accent, as Windows Phone's built-in apps did, and
     * only icons with one clearly dominant colour qualify (not multicolour icons like Chrome,
     * nor white or black ones like X). The colour is toned down slightly and darkened as needed
     * so white text stays readable on it. [brandStrength] (0..1) says how clearly the colour
     * dominates, for choosing between candidates.
     */
    class Icon(
        val bitmap: Bitmap?,
        val monochrome: Boolean,
        val label: CharSequence,
        val brandColor: Int,
        val brandTile: Int = 0,
        val brandStrength: Float = 0f,
        /** Drawn from the chosen icon pack: shown in its own colours, not tinted. */
        val fromPack: Boolean = false,
        /** Average colour of the pack icon's lines, to check contrast against the tile. */
        val packColor: Int = 0,
    ) {
        /**
         * How to colour this icon on a background: monochrome glyphs take [onColor]; pack icons
         * keep their colours unless they'd nearly vanish on a solid [background] (an accent
         * icon on an accent tile), in which case they're drawn white. [background] is null for
         * wallpaper or black behind the icon.
         */
        fun filterFor(background: Int?, onColor: Int): android.graphics.ColorFilter? = when {
            monochrome -> android.graphics.PorterDuffColorFilter(onColor, android.graphics.PorterDuff.Mode.SRC_IN)
            fromPack && background != null && packColor != 0 &&
                androidx.core.graphics.ColorUtils.calculateContrast(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(packColor, 255),
                    androidx.core.graphics.ColorUtils.setAlphaComponent(background, 255),
                ) < 2.2 -> android.graphics.PorterDuffColorFilter(onColor, android.graphics.PorterDuff.Mode.SRC_IN)
            else -> null
        }
    }

    private val cache = LruCache<String, Icon>(400)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private const val RENDER_SIZE = 192

    /** Icon pack in use when keys are made, so switching packs never shows stale icons. */
    @Volatile private var packKey = ""

    private fun key(cn: ComponentName, user: UserHandle) = cn.flattenToShortString() + "#" + user.hashCode() + "#" + packKey

    /** Call before loading icons: picks up the current icon pack setting. */
    @JvmStatic
    fun syncPack(context: Context) {
        val p = app.lawnchair.preferences.PreferenceManager.getInstance(context).metroIconPack.get()
        if (p != packKey) {
            packKey = p
            cache.evictAll()
        }
    }

    /** The chosen icon pack, loaded once (Lawnchair's icon pack support). */
    private fun packIcon(context: Context, target: ComponentName, user: UserHandle): Drawable? {
        val pkg = app.lawnchair.preferences.PreferenceManager.getInstance(context).metroIconPack.get()
        if (pkg.isEmpty()) return null
        return runCatching {
            val provider = app.lawnchair.icons.iconpack.IconPackProvider.INSTANCE.get(context)
            val pack = provider.getIconPack(pkg) ?: return null
            pack.loadBlocking()
            val entry = pack.getIcon(target) ?: return null
            provider.getDrawable(entry, context.resources.displayMetrics.densityDpi, user)
        }.getOrNull()
    }

    /** Average colour of the visible pixels of [bmp]. */
    private fun averageColor(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (c in px) {
            if ((c ushr 24) < 160) continue
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
        }
        if (n == 0L) return 0
        return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Returns the cached icon, or null and delivers it to [onLoaded] on the main thread later. */
    @JvmStatic
    fun get(
        context: Context,
        component: ComponentName,
        user: UserHandle = Process.myUserHandle(),
        onLoaded: (Icon) -> Unit,
    ): Icon? {
        syncPack(context)
        val k = key(component, user)
        cache.get(k)?.let { return it }
        val app = context.applicationContext
        executor.execute {
            val icon = cache.get(k) ?: load(app, component, user).also { cache.put(k, it) }
            main.post { onLoaded(icon) }
        }
        return null
    }

    /**
     * Icon and label for an app shortcut tile. Shortcut icons are often pictures (a contact, a
     * web page), so they are kept in full colour.
     */
    @JvmStatic
    fun getShortcut(context: Context, pkg: String, shortcutId: String, fallbackLabel: String?, onLoaded: (Icon) -> Unit): Icon? {
        val k = "shortcut:$pkg#$shortcutId"
        cache.get(k)?.let { return it }
        val appCtx = context.applicationContext
        executor.execute {
            val icon = cache.get(k) ?: run {
                val info = MetroShortcuts.find(appCtx, pkg, shortcutId)
                val d = info?.let { MetroShortcuts.icon(appCtx, it) }
                val label = (info?.shortLabel ?: info?.longLabel)?.toString() ?: fallbackLabel ?: ""
                Icon(runCatching { d?.let(::renderTrimmed) }.getOrNull(), false, label, 0)
            }.also { cache.put(k, it) }
            main.post { onLoaded(icon) }
        }
        return null
    }

    /** Loads (or returns the cached) icon on the calling thread. Never call on the main thread. */
    @JvmStatic
    fun getBlocking(context: Context, component: ComponentName, user: UserHandle = Process.myUserHandle()): Icon {
        syncPack(context)
        val k = key(component, user)
        return cache.get(k) ?: load(context.applicationContext, component, user).also { cache.put(k, it) }
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
        val packBitmap = packIcon(context, target, user)?.let { d -> runCatching { renderTrimmed(d) }.getOrNull() }
        val bitmap = packBitmap ?: runCatching { (mono ?: full)?.let(::renderTrimmed) }.getOrNull()
        val brand = runCatching { full?.let(::brandOf) }.getOrNull()
        val appInfo = runCatching { info?.applicationInfo }.getOrNull()
        val preinstalled = appInfo != null && appInfo.flags and
            (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val tile = if (brand != null && !preinstalled && brand.strength >= 0.62f) brand.tile else 0
        return Icon(
            bitmap, packBitmap == null && mono != null, label, brand?.glow ?: 0, tile,
            if (tile != 0) brand!!.strength else 0f,
            fromPack = packBitmap != null,
            packColor = packBitmap?.let(::averageColor) ?: 0,
        )
    }

    private class Brand(val glow: Int, val tile: Int, val strength: Float)

    /**
     * Finds the icon's brand colour: bucket saturated pixels by hue and take the strongest
     * bucket (with its neighbours, so a gradient within one hue still counts as one colour).
     * Strength is how much of the icon's colour that hue holds, scaled down when the icon is
     * mostly white, grey or black.
     */
    private fun brandOf(d: Drawable): Brand? {
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
        var opaque = 0
        var colored = 0
        for (c in px) {
            if ((c ushr 24) < 200) continue
            opaque++
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue // greys, whites, blacks
            colored++
            val b = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            val w = hsv[1] * hsv[2]
            weight[b] += w
            sumR[b] += ((c shr 16) and 0xFF) * w
            sumG[b] += ((c shr 8) and 0xFF) * w
            sumB[b] += (c and 0xFF) * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] < 20f) return null // too little colour to call it a brand colour
        val total = weight.sum()
        val near = weight[best] + weight[(best + 1) % buckets] + weight[(best + buckets - 1) % buckets]
        val coverage = if (opaque == 0) 0f else colored.toFloat() / opaque
        // One hue must hold most of the colour, and colour must cover a fair part of the icon.
        val strength = (near / total) * (coverage / 0.35f).coerceAtMost(1f)
        val avg = android.graphics.Color.rgb(
            (sumR[best] / weight[best]).toInt(),
            (sumG[best] / weight[best]).toInt(),
            (sumB[best] / weight[best]).toInt(),
        )
        android.graphics.Color.colorToHSV(avg, hsv)
        val glowHsv = hsv.copyOf()
        glowHsv[1] = glowHsv[1].coerceAtLeast(0.6f)
        glowHsv[2] = glowHsv[2].coerceAtLeast(0.85f)
        return Brand(android.graphics.Color.HSVToColor(glowHsv), tileTone(hsv), strength)
    }

    /** Brand colour toned down a touch for a tile, and dark enough for white text. */
    private fun tileTone(hsv: FloatArray): Int {
        val t = hsv.copyOf()
        t[1] = (t[1] * 0.88f).coerceIn(0.35f, 0.9f)
        t[2] = t[2].coerceAtMost(0.80f)
        var c = android.graphics.Color.HSVToColor(t)
        var guard = 0
        while (androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE, c) < 3.2 && guard++ < 20) {
            t[2] *= 0.94f
            c = android.graphics.Color.HSVToColor(t)
        }
        return c
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
