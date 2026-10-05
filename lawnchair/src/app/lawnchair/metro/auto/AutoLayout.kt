package app.lawnchair.metro.auto

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import app.lawnchair.metro.data.MetroTile
import app.lawnchair.metro.data.MetroUsage
import app.lawnchair.metro.data.TileSize
import app.lawnchair.metro.live.LiveTileData
import kotlin.math.max
import kotlin.math.min

/**
 * Arranges Start, putting how it looks first.
 *
 * **Composition.** The grid is built from 2×2 blocks: a medium tile, a quad of four small tiles,
 * or half of a wide tile. Rows of blocks follow alternating patterns (medium next to a quad,
 * then a quad next to a medium…), giving the checkerboard rhythm of Windows Phone rather than a
 * wall of identical squares. Every tile gets an exact position; the positions are then turned
 * into an order the grid reproduces exactly.
 *
 * **First screen.** The top holds a hero wide tile (music or a busy chat) and live tiles you
 * read. The bottom ~40% is the easy-reach area: your most-opened apps, the very top ones as
 * medium tiles on the bottom row, the next ones as small tiles in quads.
 *
 * **Below.** Everything else worth keeping, by importance, in the same rhythm. Apps you rarely
 * open are left in the app list.
 *
 * **Your choices win.** Locked tiles keep their size; recent manual sizes, pins, unpins and the
 * part of Start you dragged a tile to are respected.
 */
object AutoLayout {

    class Result(val tiles: List<MetroTile>, val added: Int, val removed: Int)

    private enum class Kind { CHAT, MUSIC, MAIL, CALENDAR, OTHER }

    private class App(
        val component: ComponentName,
        val kind: Kind,
        val tap: Double,
        val live: Double,
        val existing: MetroTile?,
        val manual: MetroUsage.Manual?,
    ) {
        val pkg: String get() = component.packageName
        val score: Double get() = tap + live * 0.5
    }

    /** One 2×2 block of the composition. */
    private enum class Block { MEDIUM, QUAD, WIDE_LEFT }

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
        val apps = gatherApps(context, current)
        fun recently(t: Long, days: Int) = t > 0 && now - t < days * DAY

        // ---- What's eligible ----
        val excluded = apps.filter { recently(it.manual?.unpinnedAt ?: 0, 30) && it.existing?.locked != true }.toSet()
        val forced = apps.filter { it.existing?.locked == true || recently(it.manual?.pinnedAt ?: 0, 30) }.toSet()
        val ranked = apps.filter { it !in excluded }.sortedByDescending { it.score }.toMutableList()

        fun manualSize(a: App): TileSize? {
            a.existing?.takeIf { it.locked }?.let { return it.size }
            val m = a.manual ?: return null
            return m.size?.takeIf { recently(m.sizeAt, 60) }
        }
        fun prefers(a: App, r: MetroUsage.Region) = a.manual?.region == r && recently(a.manual?.movedAt ?: 0, 60)

        val blocksPerRow = max(1, columns / 2)
        val screenBlockRows = max(2, screenRows / 2)
        val thumbBlockRows = max(1, Math.round(screenBlockRows * 0.4).toInt())
        val glanceBlockRows = max(0, screenBlockRows - thumbBlockRows)

        val placed = ArrayList<Pair<MetroTile, Pair<Int, Int>>>() // tile to (col,row)
        val used = HashSet<String>()
        fun take(list: List<App>, filter: (App) -> Boolean = { true }): App? =
            list.firstOrNull { it.pkg !in used && filter(it) }?.also { used += it.pkg }

        fun tileFor(a: App, size: TileSize) = a.existing?.copy(size = size) ?: MetroTile(0, a.component, size)

        /** Fills one row of blocks at block-row [br] following [pattern], pulling apps from [mPick]/[sPick]. */
        fun layRow(br: Int, pattern: List<Block>, mPick: () -> App?, sPick: () -> App?, wPick: () -> App?): Int {
            var count = 0
            var bc = 0
            for (b in pattern) {
                val col = bc * 2
                val row = br * 2
                when (b) {
                    Block.WIDE_LEFT -> {
                        val a = wPick() ?: mPick()
                        if (a != null) {
                            placed += tileFor(a, TileSize.WIDE) to (col to row)
                            count++
                        }
                        bc += 2
                        continue
                    }
                    Block.MEDIUM -> mPick()?.let {
                        placed += tileFor(it, TileSize.MEDIUM) to (col to row)
                        count++
                    }
                    Block.QUAD -> for (i in 0 until 4) {
                        sPick()?.let {
                            placed += tileFor(it, TileSize.SMALL) to ((col + i % 2) to (row + i / 2))
                            count++
                        }
                    }
                }
                bc++
            }
            return count
        }

        // Pickers respect manual sizes: an app you made small never becomes a medium tile, etc.
        fun okFor(a: App, size: TileSize): Boolean {
            val m = manualSize(a) ?: return true
            return m == size || (size == TileSize.SMALL && m == TileSize.SMALL)
        }

        // ---- Easy-reach area: most-opened apps ----
        val byTap = ranked.filter { !prefers(it, MetroUsage.Region.TOP) && !prefers(it, MetroUsage.Region.BELOW) }
            .sortedWith(compareByDescending<App> { prefers(it, MetroUsage.Region.BOTTOM) }.thenByDescending { it.tap })
        val thumbPatterns = thumbPatterns(blocksPerRow)
        // Fill the bottom row first, so the very top apps sit lowest (nearest the thumb).
        val thumbRowsTop = glanceBlockRows
        for (k in thumbBlockRows - 1 downTo 0) {
            val br = thumbRowsTop + k
            layRow(
                br,
                thumbPatterns[k % thumbPatterns.size],
                mPick = { take(byTap) { okFor(it, TileSize.MEDIUM) } },
                sPick = { take(byTap) { okFor(it, TileSize.SMALL) } },
                wPick = { null },
            )
        }

        // ---- Glance area: a hero wide tile and live tiles ----
        val liveFirst = ranked.filter { !prefers(it, MetroUsage.Region.BELOW) }
            .sortedWith(
                compareByDescending<App> { prefers(it, MetroUsage.Region.TOP) }
                    .thenByDescending { it.kind != Kind.OTHER }
                    .thenByDescending { it.score },
            )
        val hero = {
            take(liveFirst) { (it.kind == Kind.MUSIC || it.kind == Kind.CHAT || it.live > 2) && okFor(it, TileSize.WIDE) }
        }
        val glancePatterns = glancePatterns(blocksPerRow)
        for (br in 0 until glanceBlockRows) {
            layRow(
                br,
                glancePatterns[br % glancePatterns.size],
                mPick = { take(liveFirst) { okFor(it, TileSize.MEDIUM) } },
                sPick = { take(liveFirst) { okFor(it, TileSize.SMALL) } },
                wPick = hero,
            )
        }

        // ---- Below the first screen: the rest worth keeping, same rhythm ----
        val keep = ranked.filter { it.pkg !in used && (it in forced || it.tap >= 1.0 || it.live >= 1.0) }
            .sortedByDescending { it.score }
        val maxExtraBlockRows = screenBlockRows * 2 // at most ~2 more screens
        val restPatterns = restPatterns(blocksPerRow)
        var br = screenBlockRows
        var guard = 0
        while (keep.any { it.pkg !in used } && guard < maxExtraBlockRows) {
            val remaining = keep.count { it.pkg !in used }
            // Medium tiles for apps with live content or heavy use; quads for the rest.
            val mediumWorthy = { a: App -> a.kind != Kind.OTHER || a.live >= 2 || a.tap >= 8 }
            val pattern = when {
                remaining <= 4 -> listOf(Block.QUAD)
                else -> restPatterns[guard % restPatterns.size]
            }
            layRow(
                br,
                pattern,
                mPick = { take(keep) { mediumWorthy(it) && okFor(it, TileSize.MEDIUM) } ?: take(keep) { okFor(it, TileSize.MEDIUM) } },
                sPick = { take(keep) { okFor(it, TileSize.SMALL) } },
                wPick = { null },
            )
            br++
            guard++
        }
        // Forced apps that didn't get a slot (e.g. locked wide tiles) go at the end.
        val leftovers = forced.filter { it.pkg !in used }
        var tailRow = br * 2
        for (a in leftovers) {
            val size = manualSize(a) ?: TileSize.MEDIUM
            placed += tileFor(a, size) to (0 to tailRow)
            tailRow += size.rowSpan
            used += a.pkg
        }

        // Exact positions -> order: sorting by top-left cell (row, then column) makes the grid's
        // first-fit packing reproduce this layout precisely.
        val ordered = placed.sortedWith(compareBy({ it.second.second }, { it.second.first })).map { it.first }
        var nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1
        val result = ordered.map { if (it.id == 0L) it.copy(id = nextId++) else it }

        val before = current.map { it.component.packageName }.toSet()
        val after = result.map { it.component.packageName }.toSet()
        return Result(result, added = (after - before).size, removed = (before - after).size)
    }

    // ---- Patterns ---------------------------------------------------------------------------

    private fun glancePatterns(blocks: Int): List<List<Block>> = when (blocks) {
        2 -> listOf(listOf(Block.WIDE_LEFT), listOf(Block.MEDIUM, Block.MEDIUM), listOf(Block.MEDIUM, Block.QUAD))
        3 -> listOf(
            listOf(Block.WIDE_LEFT, Block.MEDIUM),
            listOf(Block.MEDIUM, Block.QUAD, Block.MEDIUM),
            listOf(Block.MEDIUM, Block.WIDE_LEFT),
            listOf(Block.QUAD, Block.MEDIUM, Block.QUAD),
        )
        else -> listOf(List(blocks) { Block.MEDIUM })
    }

    /** Index 0 is the top row of the easy-reach area, the last index the bottom row. */
    private fun thumbPatterns(blocks: Int): List<List<Block>> = when (blocks) {
        2 -> listOf(listOf(Block.QUAD, Block.MEDIUM), listOf(Block.MEDIUM, Block.QUAD))
        3 -> listOf(listOf(Block.QUAD, Block.MEDIUM, Block.QUAD), listOf(Block.MEDIUM, Block.QUAD, Block.MEDIUM))
        else -> listOf(List(blocks) { Block.QUAD })
    }

    private fun restPatterns(blocks: Int): List<List<Block>> = when (blocks) {
        2 -> listOf(listOf(Block.MEDIUM, Block.QUAD), listOf(Block.QUAD, Block.MEDIUM), listOf(Block.QUAD, Block.QUAD))
        3 -> listOf(
            listOf(Block.MEDIUM, Block.QUAD, Block.QUAD),
            listOf(Block.QUAD, Block.MEDIUM, Block.QUAD),
            listOf(Block.QUAD, Block.QUAD, Block.MEDIUM),
        )
        else -> listOf(List(blocks) { Block.QUAD })
    }

    // ---- Signals ----------------------------------------------------------------------------

    private fun gatherApps(context: Context, current: List<MetroTile>): List<App> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val activities = launcherApps?.getActivityList(null, Process.myUserHandle()).orEmpty()
            .filter { it.componentName.packageName != context.packageName }
            .distinctBy { it.componentName.packageName }
        val opens = MetroUsage.appOpens(context)
        val minutes = MetroUsage.foregroundMinutes(context)
        val activeNow = LiveTileData.snapshot
        val byPkg = current.associateBy { it.component.packageName }
        return activities.map { info ->
            val pkg = info.componentName.packageName
            val tap = MetroUsage.launchScore(context, pkg) + (opens[pkg] ?: 0.0) + min(minutes[pkg] ?: 0.0, 900.0) / 30.0
            val notif = MetroUsage.notificationsPerDay(context, pkg) + (activeNow[pkg]?.count ?: 0) * 0.5
            var t = tap
            if (byPkg[pkg] != null) t += 1.0 // a little stability for what's already on Start
            App(info.componentName, kindOf(pkg, info.applicationInfo), t, notif, byPkg[pkg], MetroUsage.manual(context, pkg))
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
