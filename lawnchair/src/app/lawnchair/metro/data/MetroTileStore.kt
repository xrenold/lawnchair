package app.lawnchair.metro.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import app.lawnchair.preferences.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** Windows Phone tile sizes, measured in small-tile cells. */
enum class TileSize(val span: Int, val rowSpan: Int) {
    SMALL(1, 1),
    MEDIUM(2, 2),
    WIDE(4, 2),
    LARGE(4, 4),
    ;

    /** Next size when cycling with the resize button, as on WP 8.1 (large skipped by default). */
    fun next(allowLarge: Boolean): TileSize = when (this) {
        MEDIUM -> SMALL
        SMALL -> WIDE
        WIDE -> if (allowLarge) LARGE else MEDIUM
        LARGE -> MEDIUM
    }
}

/**
 * One tile on the Start screen. Tiles are kept in display order; the grid packs them
 * top-to-bottom, so the order is all that needs saving.
 */
data class MetroTile(
    val id: Long,
    val component: ComponentName,
    var size: TileSize = TileSize.MEDIUM,
    /** Per-tile colour override, 0 = follow the theme. */
    var color: Int = 0,
) {
    /** Stable key for colour picks and icon caching. */
    val key: String get() = component.flattenToShortString()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("component", component.flattenToString())
        .put("size", size.name)
        .put("color", color)

    companion object {
        fun fromJson(o: JSONObject): MetroTile? {
            val cn = ComponentName.unflattenFromString(o.optString("component")) ?: return null
            val size = runCatching { TileSize.valueOf(o.optString("size")) }.getOrDefault(TileSize.MEDIUM)
            return MetroTile(o.optLong("id"), cn, size, o.optInt("color", 0))
        }
    }
}

/**
 * Stores the Start screen tiles as JSON in preferences.
 *
 * Tiles are few (tens, not thousands) and always loaded together, so a single JSON value is
 * simpler and safer than a database table.
 */
class MetroTileStore(private val context: Context) {

    private val pref = PreferenceManager.getInstance(context).metroStartTiles
    private val listeners = mutableListOf<Runnable>()

    fun load(): MutableList<MetroTile> {
        val raw = pref.get()
        if (raw.isBlank()) {
            val seeded = seedTiles()
            save(seeded)
            return seeded
        }
        val tiles = mutableListOf<MetroTile>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                MetroTile.fromJson(array.getJSONObject(i))?.let(tiles::add)
            }
        }
        // Drop tiles whose app was uninstalled.
        return tiles.filter { isLaunchable(it.component) }.toMutableList()
    }

    fun save(tiles: List<MetroTile>) {
        val array = JSONArray()
        tiles.forEach { array.put(it.toJson()) }
        pref.set(array.toString())
    }

    fun addListener(listener: Runnable) {
        listeners += listener
    }

    fun removeListener(listener: Runnable) {
        listeners -= listener
    }

    /** Adds an app at the end of Start, as a medium tile. Returns false if already pinned. */
    fun pin(component: ComponentName): Boolean {
        val tiles = load()
        if (tiles.any { it.component == component }) return false
        tiles += MetroTile(nextId(tiles), component, TileSize.MEDIUM)
        save(tiles)
        listeners.toList().forEach(Runnable::run)
        return true
    }

    fun isPinned(component: ComponentName) = load().any { it.component == component }

    private fun nextId(tiles: List<MetroTile>) = (tiles.maxOfOrNull { it.id } ?: 0L) + 1

    private fun isLaunchable(cn: ComponentName): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return true
        return runCatching {
            launcherApps.isActivityEnabled(cn, Process.myUserHandle())
        }.getOrDefault(false)
    }

    /**
     * First-run Start screen: the phone's default apps for calls, messages, browser, camera,
     * photos, mail, calendar, music, settings and the store, laid out like a fresh Windows Phone.
     */
    private fun seedTiles(): MutableList<MetroTile> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL) to TileSize.MEDIUM,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING) to TileSize.MEDIUM,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER) to TileSize.MEDIUM,
            Intent(MediaStore.ACTION_IMAGE_CAPTURE) to TileSize.SMALL,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS) to TileSize.SMALL,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL) to TileSize.SMALL,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR) to TileSize.SMALL,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY) to TileSize.WIDE,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC) to TileSize.MEDIUM,
            Intent(Settings.ACTION_SETTINGS) to TileSize.MEDIUM,
            Intent().setPackage("com.android.vending") to TileSize.MEDIUM,
        )
        val seen = mutableSetOf<String>()
        val tiles = mutableListOf<MetroTile>()
        for ((intent, size) in intents) {
            val cn = resolveLauncherActivity(intent) ?: continue
            if (!seen.add(cn.packageName)) continue
            tiles += MetroTile(tiles.size + 1L, cn, size)
        }
        return tiles
    }

    /** Finds the launcher (home-screen) activity of the app that handles [intent]. */
    private fun resolveLauncherActivity(intent: Intent): ComponentName? {
        val pm = context.packageManager
        val pkg = intent.`package`
            ?: runCatching { pm.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
            ?: return null
        if (pkg == "android") return null // the "choose an app" resolver
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        return launcherApps.getActivityList(pkg, Process.myUserHandle()).firstOrNull()?.componentName
    }

    companion object {
        private var instance: MetroTileStore? = null

        @JvmStatic
        fun get(context: Context): MetroTileStore =
            instance ?: MetroTileStore(context.applicationContext).also { instance = it }
    }
}
