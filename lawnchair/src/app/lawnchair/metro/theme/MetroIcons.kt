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

    private val packFailed = ThreadLocal.withInitial { false }

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
        }.getOrElse {
            packFailed.set(true) // don't save this result: the pack may just be busy updating
            null
        }
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

    // ---- Saved icons ---------------------------------------------------------------------------
    // Rendered icons and their colour analysis are saved, so after a launcher restart Start and
    // the app list appear fully drawn almost at once. The key changes when the app updates, the
    // icon pack or language changes, or (for packs that follow Material You) the palette changes.

    private fun diskKey(context: Context, target: ComponentName, user: UserHandle): String {
        // Per user, so work-profile apps get their own update time too.
        val stamp = runCatching {
            val ai = context.getSystemService(LauncherApps::class.java).getApplicationInfo(target.packageName, 0, user)
            java.io.File(ai.sourceDir).lastModified()
        }.getOrDefault(0L)
        val packStamp = if (packKey.isNotEmpty()) {
            runCatching { context.packageManager.getPackageInfo(packKey, 0).lastUpdateTime }.getOrDefault(0L)
        } else {
            0L
        }
        val palette = if (packKey.isNotEmpty() && Build.VERSION.SDK_INT >= 31) {
            context.getColor(android.R.color.system_accent1_500)
        } else {
            0
        }
        val raw = "${target.flattenToString()}#${user.hashCode()}#$packKey@$packStamp#$stamp#$palette#" +
            "${java.util.Locale.getDefault().toLanguageTag()}#${context.resources.displayMetrics.densityDpi}"
        return Integer.toHexString(raw.hashCode()) + "_" + Integer.toHexString(raw.reversed().hashCode())
    }

    private fun diskDir(context: Context) = java.io.File(context.cacheDir, "metro_icons").apply { mkdirs() }

    private fun readDisk(context: Context, key: String): Icon? = runCatching {
        val dir = diskDir(context)
        val meta = java.io.File(dir, "$key.meta").takeIf { it.exists() }?.readText() ?: return null
        val p = meta.split('\u0001')
        if (p.size < 8 || p[7] != "v2") return null
        // A missing or unreadable picture is a miss, never a blank icon.
        val bmp = android.graphics.BitmapFactory.decodeFile(java.io.File(dir, "$key.png").path) ?: return null
        Icon(bmp, p[0] == "1", p[1], p[2].toInt(), p[3].toInt(), p[4].toFloat(), p[5] == "1", p[6].toInt())
    }.getOrNull()

    private fun writeDisk(context: Context, key: String, icon: Icon) {
        val b = icon.bitmap ?: return
        runCatching {
            val dir = diskDir(context)
            // Written to temporary names and renamed, picture first and details last, so a
            // reader never sees half a pair.
            val tag = Thread.currentThread().id
            val pngTmp = java.io.File(dir, "$key.png.$tag.tmp")
            pngTmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pngTmp.renameTo(java.io.File(dir, "$key.png"))
            val meta = listOf(
                if (icon.monochrome) "1" else "0", icon.label.toString().replace('\u0001', ' '), icon.brandColor, icon.brandTile,
                icon.brandStrength, if (icon.fromPack) "1" else "0", icon.packColor, "v2",
            ).joinToString("\u0001")
            val metaTmp = java.io.File(dir, "$key.meta.$tag.tmp")
            metaTmp.writeText(meta)
            metaTmp.renameTo(java.io.File(dir, "$key.meta"))
            // Keep the folder small: drop the oldest half of the icons (both files) when it grows.
            val metas = dir.listFiles { f -> f.name.endsWith(".meta") }.orEmpty()
            if (metas.size > 800) {
                metas.sortedBy { it.lastModified() }.take(metas.size / 2).forEach { m ->
                    val k = m.name.removeSuffix(".meta")
                    m.delete()
                    java.io.File(dir, "$k.png").delete()
                }
            }
        }
    }

    private fun load(context: Context, target: ComponentName, user: UserHandle): Icon {
        val key = diskKey(context, target, user)
        readDisk(context, key)?.let { return it }
        packFailed.set(false)
        val fresh = loadFresh(context, target, user)
        // Only save complete results: not when the app couldn't be found (paused work profile,
        // mid-install) or the icon pack was busy.
        if (fresh.bitmap != null && fresh.label.toString() != target.packageName && !packFailed.get()) writeDisk(context, key, fresh)
        return fresh
    }

    private fun loadFresh(context: Context, target: ComponentName, user: UserHandle): Icon {
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
