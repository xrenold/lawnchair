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
enum class TileSize(val span: Int, val rowSpan: Int, val label: String) {
    SMALL(1, 1, "Small"),
    MEDIUM(2, 2, "Medium"),
    WIDE(4, 2, "Wide"),
    LARGE(4, 4, "Large"),
    ;

    /** Sizes that list several messages when live. */
    val isList: Boolean get() = this == WIDE || this == LARGE
}

/**
 * One tile on the Start screen. Tiles are kept in display order; the grid packs them
 * top-to-bottom, so the order is all that needs saving.
 */
data class MetroTile(
    val id: Long,
    /** The app's activity; for widgets, the widget provider; for shortcuts, the owning app. */
    val component: ComponentName,
    var size: TileSize = TileSize.MEDIUM,
    /**
     * Per-tile colour override: 0 = automatic (theme, or brand colour when the app qualifies),
     * [COLOR_BRAND] or [COLOR_ACCENT] to force either, or a specific colour.
     */
    var color: Int = 0,
    /** Locked tiles keep their size and spot when auto layout runs. */
    var locked: Boolean = false,
    val kind: Kind = Kind.APP,
    /** App shortcut id (kind SHORTCUT). */
    val shortcutId: String? = null,
    /** Bound widget id (kind WIDGET). */
    val widgetId: Int = 0,
    /** Label for shortcut tiles. */
    val title: String? = null,
    /**
     * Colour picked while in 8.1 window mode (same values as [color]; 0 = a window). Kept apart
     * from [color], so picks made in one mode never carry over into the other.
     */
    var windowColor: Int = 0,
) {
    /** The colour choice that applies in the current mode. */
    fun colorFor(windowMode: Boolean): Int = if (windowMode) windowColor else color
    enum class Kind { APP, SHORTCUT, WIDGET }

    /** Stable key for colour picks and icon caching. */
    val key: String get() = when (kind) {
        Kind.APP -> component.flattenToShortString()
        Kind.SHORTCUT -> component.packageName + "#" + shortcutId
        Kind.WIDGET -> "widget#$widgetId"
    }

    val isApp: Boolean get() = kind == Kind.APP

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("component", component.flattenToString())
        .put("size", size.name)
        .put("color", color)
        .put("locked", locked)
        .put("kind", kind.name)
        .put("shortcutId", shortcutId ?: "")
        .put("widgetId", widgetId)
        .put("title", title ?: "")
        .put("windowColor", windowColor)

    companion object {
        /** Sentinels for [color]; real colours always have a non-zero alpha. */
        const val COLOR_BRAND = 1
        const val COLOR_ACCENT = 2
        /** 8.1 window mode: always a window onto the background (no automatic brand colour). */
        const val COLOR_WINDOW = 3

        fun fromJson(o: JSONObject): MetroTile? {
            val cn = ComponentName.unflattenFromString(o.optString("component")) ?: return null
            val size = runCatching { TileSize.valueOf(o.optString("size")) }.getOrDefault(TileSize.MEDIUM)
            val kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.APP)
            return MetroTile(
                o.optLong("id"), cn, size, o.optInt("color", 0), o.optBoolean("locked", false),
                kind,
                o.optString("shortcutId").ifEmpty { null },
                o.optInt("widgetId", 0),
                o.optString("title").ifEmpty { null },
                o.optInt("windowColor", 0),
            )
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
        return tiles.filter {
            when (it.kind) {
                MetroTile.Kind.APP -> isLaunchable(it.component)
                MetroTile.Kind.SHORTCUT -> isInstalled(it.component.packageName)
                MetroTile.Kind.WIDGET -> it.widgetId != 0
            }
        }.toMutableList()
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
        if (tiles.any { it.isApp && it.component == component }) return false
        tiles += MetroTile(nextId(tiles), component, TileSize.MEDIUM)
        save(tiles)
        MetroUsage.recordPinned(context, component.packageName)
        listeners.toList().forEach(Runnable::run)
        return true
    }

    fun isPinned(component: ComponentName) = load().any { it.isApp && it.component == component }

    /** Replaces all tiles (auto layout, undo) and tells Start to rebuild. */
    fun replaceAll(newTiles: List<MetroTile>) {
        save(newTiles)
        listeners.toList().forEach(Runnable::run)
    }

    fun nextId(tiles: List<MetroTile>) = (tiles.maxOfOrNull { it.id } ?: 0L) + 1

    private fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    /** Adds an app shortcut (pinned by the app or chosen from the app list) as a tile. */
    fun addShortcut(pkg: String, shortcutId: String, label: String?) {
        val tiles = load()
        if (tiles.any { it.kind == MetroTile.Kind.SHORTCUT && it.component.packageName == pkg && it.shortcutId == shortcutId }) return
        tiles += MetroTile(
            nextId(tiles), ComponentName(pkg, pkg), TileSize.SMALL,
            kind = MetroTile.Kind.SHORTCUT, shortcutId = shortcutId, title = label,
        )
        save(tiles)
        listeners.toList().forEach(Runnable::run)
    }

    /** Adds a bound widget as a tile. */
    fun addWidget(provider: ComponentName, widgetId: Int, size: TileSize) {
        val tiles = load()
        tiles += MetroTile(nextId(tiles), provider, size, kind = MetroTile.Kind.WIDGET, widgetId = widgetId)
        save(tiles)
        listeners.toList().forEach(Runnable::run)
    }

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
