package app.lawnchair.metro.data

import android.app.AppOpsManager
import android.app.Notification
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * On-device memory of how you use your apps, for auto layout. Nothing leaves the phone.
 *
 * - Launches made from Metro (tiles, app list, search), per day.
 * - Notifications each app posts, per day.
 * - Your manual choices: tile sizes you set, apps you pinned or unpinned, where you moved tiles.
 * - Optionally Android's usage stats (screen time), when Usage access is granted.
 *
 * Daily counts are kept for [KEEP_DAYS] days and weighted towards recent days.
 */
object MetroUsage {

    private const val PREFS = "metro_usage"
    private const val KEEP_DAYS = 21
    private const val HALF_LIFE_DAYS = 7.0

    private var loaded = false
    private val launches = HashMap<String, HashMap<Long, Int>>()
    private val notifications = HashMap<String, HashMap<Long, Int>>()
    private val manual = HashMap<String, JSONObject>()
    private val recentNotificationKeys = LinkedHashMap<String, Long>()

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val saveRunnable = Runnable { save() }

    /** Where on Start a tile was placed by hand. */
    enum class Region { TOP, BOTTOM, BELOW }

    private fun today() = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        appContext = context.applicationContext
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        readCounts(sp.getString("launches", null), launches)
        readCounts(sp.getString("notifications", null), notifications)
        runCatching {
            val o = JSONObject(sp.getString("manual", "{}") ?: "{}")
            o.keys().forEach { manual[it] = o.getJSONObject(it) }
        }
        loaded = true
    }

    private fun readCounts(raw: String?, into: HashMap<String, HashMap<Long, Int>>) {
        if (raw == null) return
        runCatching {
            val o = JSONObject(raw)
            o.keys().forEach { pkg ->
                val days = o.getJSONObject(pkg)
                val map = HashMap<Long, Int>()
                days.keys().forEach { d -> map[d.toLong()] = days.getInt(d) }
                into[pkg] = map
            }
        }
    }

    private fun writeCounts(from: HashMap<String, HashMap<Long, Int>>): String {
        val cutoff = today() - KEEP_DAYS
        val o = JSONObject()
        from.forEach { (pkg, days) ->
            val d = JSONObject()
            days.filterKeys { it > cutoff }.forEach { (day, n) -> d.put(day.toString(), n) }
            if (d.length() > 0) o.put(pkg, d)
        }
        return o.toString()
    }

    @Synchronized
    private fun save() {
        val ctx = appContext ?: return
        val m = JSONObject()
        manual.forEach { (k, v) -> m.put(k, v) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("launches", writeCounts(launches))
            .putString("notifications", writeCounts(notifications))
            .putString("manual", m.toString())
            .apply()
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 2000)
    }

    private fun bump(map: HashMap<String, HashMap<Long, Int>>, pkg: String) {
        val days = map.getOrPut(pkg) { HashMap() }
        val d = today()
        days[d] = (days[d] ?: 0) + 1
    }

    // ---- Recording --------------------------------------------------------------------

    @JvmStatic
    @Synchronized
    fun recordLaunch(context: Context, pkg: String) {
        ensureLoaded(context)
        bump(launches, pkg)
        scheduleSave()
    }

    /** Called by the notification listener for every posted notification. */
    @JvmStatic
    @Synchronized
    fun recordNotification(context: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Updates to the same notification don't count again.
        val id = sbn.key + "@" + n.`when`
        if (recentNotificationKeys.containsKey(id)) return
        recentNotificationKeys[id] = System.currentTimeMillis()
        if (recentNotificationKeys.size > 300) recentNotificationKeys.remove(recentNotificationKeys.keys.first())
        ensureLoaded(context)
        bump(notifications, sbn.packageName)
        scheduleSave()
    }

    private fun manualFor(pkg: String) = manual.getOrPut(pkg) { JSONObject() }

    @JvmStatic
    @Synchronized
    fun recordSize(context: Context, pkg: String, size: TileSize) {
        ensureLoaded(context)
        manualFor(pkg).put("size", size.name).put("sizeAt", System.currentTimeMillis())
        scheduleSave()
    }

    @JvmStatic
    @Synchronized
    fun recordPinned(context: Context, pkg: String) {
        ensureLoaded(context)
        manualFor(pkg).put("pinnedAt", System.currentTimeMillis()).remove("unpinnedAt")
        scheduleSave()
    }

    @JvmStatic
    @Synchronized
    fun recordUnpinned(context: Context, pkg: String) {
        ensureLoaded(context)
        manualFor(pkg).put("unpinnedAt", System.currentTimeMillis()).remove("pinnedAt")
        scheduleSave()
    }

    @JvmStatic
    @Synchronized
    fun recordMoved(context: Context, pkg: String, region: Region) {
        ensureLoaded(context)
        manualFor(pkg).put("region", region.name).put("movedAt", System.currentTimeMillis())
        scheduleSave()
    }

    // ---- Reading ----------------------------------------------------------------------

    /** Recency-weighted daily counts: today counts 1, a week ago ½, two weeks ago ¼. */
    private fun weighted(map: HashMap<String, HashMap<Long, Int>>, pkg: String): Double {
        val days = map[pkg] ?: return 0.0
        val t = today()
        return days.entries.sumOf { (d, n) -> n * Math.pow(0.5, (t - d) / HALF_LIFE_DAYS) }
    }

    @Synchronized
    fun launchScore(context: Context, pkg: String): Double {
        ensureLoaded(context)
        return weighted(launches, pkg)
    }

    /** Average notifications per day over the kept window. */
    @Synchronized
    fun notificationsPerDay(context: Context, pkg: String): Double {
        ensureLoaded(context)
        val days = notifications[pkg] ?: return 0.0
        val cutoff = today() - 14
        return days.filterKeys { it > cutoff }.values.sum() / 14.0
    }

    class Manual(
        val size: TileSize?,
        val sizeAt: Long,
        val pinnedAt: Long,
        val unpinnedAt: Long,
        val region: Region?,
        val movedAt: Long,
    )

    @Synchronized
    fun manual(context: Context, pkg: String): Manual? {
        ensureLoaded(context)
        val o = manual[pkg] ?: return null
        return Manual(
            size = o.optString("size").takeIf { it.isNotEmpty() }?.let { runCatching { TileSize.valueOf(it) }.getOrNull() },
            sizeAt = o.optLong("sizeAt"),
            pinnedAt = o.optLong("pinnedAt"),
            unpinnedAt = o.optLong("unpinnedAt"),
            region = o.optString("region").takeIf { it.isNotEmpty() }?.let { runCatching { Region.valueOf(it) }.getOrNull() },
            movedAt = o.optLong("movedAt"),
        )
    }

    /** True once the user has granted Usage access to Metro. */
    @JvmStatic
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        // Some devices (Samsung among them) report "default" and leave it to the permission.
        return mode == AppOpsManager.MODE_ALLOWED ||
            (mode == AppOpsManager.MODE_DEFAULT &&
                context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    /**
     * How often each app was opened over the last 14 days, from Android's usage events, with
     * recent days weighted more (same half-life as Metro's own counts). Counts each time an app
     * comes to the front after another app, so moving between screens of one app counts once.
     * Empty without Usage access.
     */
    fun appOpens(context: Context): Map<String, Double> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.DAYS.toMillis(14)
        return runCatching {
            val events = usm.queryEvents(start, end)
            val e = android.app.usage.UsageEvents.Event()
            val out = HashMap<String, Double>()
            var lastPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType != android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                val pkg = e.packageName ?: continue
                if (pkg == lastPkg) continue
                lastPkg = pkg
                if (pkg == context.packageName) continue
                val ageDays = (end - e.timeStamp) / 86_400_000.0
                out[pkg] = (out[pkg] ?: 0.0) + Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** Minutes in the foreground per app over the last 14 days (empty without Usage access). */
    fun foregroundMinutes(context: Context): Map<String, Double> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.DAYS.toMillis(14)
        return runCatching {
            usm.queryAndAggregateUsageStats(start, end)
                .mapValues { it.value.totalTimeInForeground / 60000.0 }
        }.getOrDefault(emptyMap())
    }
}
