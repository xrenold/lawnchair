package app.lawnchair.metro.auto

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import app.lawnchair.metro.data.MetroTile
import app.lawnchair.metro.data.MetroUsage
import app.lawnchair.metro.data.TilePacker
import app.lawnchair.metro.data.TileSize
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Arranges Start from how you actually use your phone.
 *
 * 1. **Score** every app: launches from Metro (recent days count more), screen time when Usage
 *    access is granted, how often it notifies, and a stability bonus for tiles already on Start.
 * 2. **Choose** what's on Start: the highest scores, up to about two and a half screens. Apps you
 *    pinned yourself recently always stay; apps you unpinned recently stay off; locked tiles
 *    never change.
 * 3. **Size** by how much there is to show, not by use: busy chats and music get wide tiles,
 *    calendars and quieter chats medium, everything else small (Camera, Maps, Phone…). A size
 *    you set yourself wins.
 * 4. **Place** the first screen in two parts. The bottom ~40% is the easy-reach area, filled with
 *    the apps you open most. The top holds the live tiles you read more than tap. Everything else
 *    follows below by score. Tiles you dragged to a part of Start go back to that part.
 */
object AutoLayout {

    class Result(val tiles: List<MetroTile>, val added: Int, val removed: Int)

    private enum class Kind { CHAT, MUSIC, MAIL, CALENDAR, OTHER }

    private class App(
        val component: ComponentName,
        val kind: Kind,
        val score: Double,
        val tapScore: Double,
        val notifPerDay: Double,
        val existing: MetroTile?,
        val manual: MetroUsage.Manual?,
    ) {
        val pkg: String get() = component.packageName
        var size: TileSize = TileSize.SMALL
    }

    private val CHAT = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.microsoft.teams",
        "com.Slack", "com.discord", "com.facebook.orca", "com.instagram.android", "com.snapchat.android",
        "jp.naver.line.android", "com.viber.voip", "com.google.android.apps.dynamite",
    )
    private val MUSIC = setOf(
        "com.spotify.music", "com.apple.android.music", "com.google.android.apps.youtube.music",
        "com.amazon.mp3", "com.jio.media.jiobeats", "com.gaana", "com.soundcloud.android",
        "au.com.shiftyjelly.pocketcasts", "com.google.android.apps.podcasts", "com.audible.application",
    )
    private val MAIL = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
        "ch.protonmail.android", "com.yahoo.mobile.client.android.mail",
    )
    private val CALENDAR = setOf("com.google.android.calendar", "com.samsung.android.calendar")

    private const val DAY = 86_400_000L

    fun compute(context: Context, current: List<MetroTile>, columns: Int, screenRows: Int): Result {
        val now = System.currentTimeMillis()
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val activities = launcherApps?.getActivityList(null, Process.myUserHandle()).orEmpty()
            .filter { it.componentName.packageName != context.packageName }
            .distinctBy { it.componentName.packageName }
        val minutes = MetroUsage.foregroundMinutes(context)
        val byPkg = current.associateBy { it.component.packageName }

        val apps = activities.map { info ->
            val pkg = info.componentName.packageName
            val launches = MetroUsage.launchScore(context, pkg)
            val mins = min(minutes[pkg] ?: 0.0, 900.0)
            val notif = MetroUsage.notificationsPerDay(context, pkg)
            val manual = MetroUsage.manual(context, pkg)
            val existing = byPkg[pkg]
            val tap = launches + mins / 15.0
            var score = tap + min(notif, 30.0) * 0.35
            if (existing != null) score += 2.0 // stability: don't churn what's already there
            App(info.componentName, kindOf(pkg, info.applicationInfo), score, tap, notif, existing, manual)
        }

        // ---- Choose what's on Start ----
        fun recently(t: Long, days: Int) = t > 0 && now - t < days * DAY
        val forced = apps.filter { it.existing?.locked == true || recently(it.manual?.pinnedAt ?: 0, 30) }
        val excluded = apps.filter { recently(it.manual?.unpinnedAt ?: 0, 30) && it !in forced }.toSet()
        apps.forEach { it.size = sizeFor(it, now) }
        val wideBudget = 3
        var wides = 0
        val budget = (columns * screenRows * 2.5).roundToInt()
        var used = 0
        val chosen = ArrayList<App>()
        (forced + apps.filter { it !in forced && it !in excluded }.sortedByDescending { it.score })
            .forEach { app ->
                val isForced = app in forced
                if (!isForced && app.score < 0.5 && app.existing == null) return@forEach // never used
                if (app.size == TileSize.WIDE && app.existing?.locked != true) {
                    if (wides >= wideBudget) app.size = TileSize.MEDIUM else wides++
                }
                val area = app.size.span.coerceAtMost(columns) * app.size.rowSpan
                if (!isForced && used + area > budget) return@forEach
                used += area
                chosen += app
            }

        // ---- Place: easy-reach bottom, glance top, the rest below ----
        val thumbTarget = maxOf(2, ((screenRows * 0.4) / 2).roundToInt() * 2)
        val glanceRows = ((screenRows - thumbTarget) / 2) * 2
        val thumbRows = screenRows - glanceRows
        val pool = chosen.filter { it.existing?.locked != true }.toMutableList()

        fun prefers(app: App, region: MetroUsage.Region) =
            app.manual?.region == region && recently(app.manual?.movedAt ?: 0, 60)

        // Easy reach: the apps you open most (or dragged there yourself).
        val thumb = fill(
            rows = thumbRows,
            columns = columns,
            candidates = pool.filter { prefers(it, MetroUsage.Region.BOTTOM) } +
                pool.filter { !prefers(it, MetroUsage.Region.TOP) && !prefers(it, MetroUsage.Region.BELOW) }
                    .sortedByDescending { it.tapScore },
        )
        pool.removeAll(thumb.toSet())

        // Glance: live tiles you read more than tap, then smaller tiles to close the gaps.
        val live = { a: App -> a.kind != Kind.OTHER || a.size != TileSize.SMALL }
        val glance = fill(
            rows = glanceRows,
            columns = columns,
            candidates = pool.filter { prefers(it, MetroUsage.Region.TOP) } +
                pool.filter { live(it) && !prefers(it, MetroUsage.Region.BELOW) }.sortedByDescending { it.score } +
                pool.filter { !live(it) && !prefers(it, MetroUsage.Region.BELOW) }.sortedByDescending { it.score },
            shrinkToFit = true,
        )
        pool.removeAll(glance.toSet())

        val rest = pool.sortedByDescending { it.score }
        val order = (glance + thumb + rest).map { app ->
            app.existing?.copy(size = app.size) ?: MetroTile(0, app.component, app.size)
        }.toMutableList()

        // Locked tiles keep their place in the order.
        current.forEachIndexed { index, tile ->
            if (tile.locked) order.add(index.coerceAtMost(order.size), tile)
        }

        // Fresh ids for new tiles.
        var nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1
        val result = order.map { if (it.id == 0L) it.copy(id = nextId++) else it }

        val before = current.map { it.component.packageName }.toSet()
        val after = result.map { it.component.packageName }.toSet()
        return Result(result, added = (after - before).size, removed = (before - after).size)
    }

    /**
     * Packs [candidates] into [rows] rows, in order, skipping any that would spill over.
     * With [shrinkToFit], a tile that doesn't fit is tried again one size smaller, so gaps close.
     */
    private fun fill(rows: Int, columns: Int, candidates: List<App>, shrinkToFit: Boolean = false): List<App> {
        val packer = TilePacker(columns)
        val out = ArrayList<App>()
        for (app in candidates.distinct()) {
            if (packer.freeCells(rows) == 0) break
            val sizes = if (shrinkToFit) smallerOrEqual(app.size) else listOf(app.size)
            for (s in sizes) {
                val (_, row) = packer.peek(s)
                if (row + s.rowSpan <= rows) {
                    packer.place(s)
                    app.size = s
                    out += app
                    break
                }
            }
        }
        return out
    }

    private fun smallerOrEqual(size: TileSize) = when (size) {
        TileSize.LARGE -> listOf(TileSize.LARGE, TileSize.WIDE, TileSize.MEDIUM, TileSize.SMALL)
        TileSize.WIDE -> listOf(TileSize.WIDE, TileSize.MEDIUM, TileSize.SMALL)
        TileSize.MEDIUM -> listOf(TileSize.MEDIUM, TileSize.SMALL)
        TileSize.SMALL -> listOf(TileSize.SMALL)
    }

    /** Size by how much the app has to show; your own recent choice and locks win. */
    private fun sizeFor(app: App, now: Long): TileSize {
        app.existing?.takeIf { it.locked }?.let { return it.size }
        val m = app.manual
        val manualSize = m?.size
        if (m != null && manualSize != null && now - m.sizeAt < 60 * DAY) return manualSize
        val n = app.notifPerDay
        return when (app.kind) {
            Kind.MUSIC -> if (app.tapScore >= 3) TileSize.WIDE else TileSize.MEDIUM
            Kind.CHAT, Kind.MAIL -> when {
                n >= 12 -> TileSize.WIDE
                n >= 2 -> TileSize.MEDIUM
                else -> TileSize.SMALL
            }
            Kind.CALENDAR -> TileSize.MEDIUM
            Kind.OTHER -> if (n >= 6) TileSize.MEDIUM else TileSize.SMALL
        }
    }

    private fun kindOf(pkg: String, info: ApplicationInfo): Kind = when {
        pkg in CHAT -> Kind.CHAT
        pkg in MUSIC -> Kind.MUSIC
        pkg in MAIL -> Kind.MAIL
        pkg in CALENDAR -> Kind.CALENDAR
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.category == ApplicationInfo.CATEGORY_AUDIO -> Kind.MUSIC
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.category == ApplicationInfo.CATEGORY_SOCIAL -> Kind.CHAT
        else -> Kind.OTHER
    }
}
