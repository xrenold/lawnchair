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
import app.lawnchair.metro.info.InfoTiles
import app.lawnchair.metro.theme.MetroIcons
import app.lawnchair.metro.theme.MetroTheme
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

    private enum class Kind { CHAT, MUSIC, MAIL, CALENDAR, OTHER, WIDGET, SHORTCUT, WEATHER, CLOCK, PHOTOS }

    /** Info tiles you glance at rather than open: always kept, in the glance area. */
    private val INFO = setOf(Kind.CALENDAR, Kind.WEATHER, Kind.CLOCK, Kind.PHOTOS)

    private class App(
        val component: ComponentName,
        val kind: Kind,
        val tap: Double,
        val live: Double,
        val existing: MetroTile?,
        val manual: MetroUsage.Manual?,
        /** Widget and shortcut tiles: kept as they are, only placed. */
        val fixed: MetroTile? = null,
    ) {
        val pkg: String get() = component.packageName
        /** Unique per item (several shortcuts can share an app). */
        val key: String get() = fixed?.key ?: pkg
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
        InfoTiles.loadOverrides(context)
        val apps = gatherApps(context, current)
        fun recently(t: Long, days: Int) = t > 0 && now - t < days * DAY

        // ---- What's eligible ----
        val excluded = apps.filter { it.fixed == null && recently(it.manual?.unpinnedAt ?: 0, 30) && it.existing?.locked != true }.toSet()
        val forced = apps.filter { it.fixed != null || it.existing?.locked == true || recently(it.manual?.pinnedAt ?: 0, 30) }.toSet()
        val ranked = apps.filter { it !in excluded }.sortedByDescending { it.score }.toMutableList()

        fun manualSize(a: App): TileSize? {
            a.fixed?.let { return it.size }
            a.existing?.takeIf { it.locked }?.let { return it.size }
            val m = a.manual ?: return null
            return m.size?.takeIf { recently(m.sizeAt, 60) }
        }
        fun prefers(a: App, r: MetroUsage.Region) = a.manual?.region == r && recently(a.manual?.movedAt ?: 0, 60)

        // Brand-coloured tiles (see MetroIcons): spread out, and kept off the big wide slots in
        // 8.1 window mode, where a solid block of colour would dominate the photo.
        val windowMode = MetroTheme.background(context) == MetroTheme.BG_WINDOW
        val brandMemo = HashMap<String, Boolean>()
        fun isBrandTile(t: MetroTile?, component: ComponentName): Boolean {
            if (t != null && t.kind != MetroTile.Kind.APP) return false
            when (t?.color ?: 0) {
                MetroTile.COLOR_BRAND -> return true
                0 -> Unit
                else -> return false
            }
            return brandMemo.getOrPut(component.packageName) {
                runCatching { MetroIcons.getBlocking(context, component).brandTile != 0 }.getOrDefault(false)
            }
        }
        fun isBrand(a: App) = a.fixed == null && isBrandTile(a.existing, a.component)
        fun wideOk(a: App) = !(windowMode && isBrand(a))

        val blocksPerRow = max(1, columns / 2)
        val screenBlockRows = max(2, screenRows / 2)
        val thumbBlockRows = max(1, Math.round(screenBlockRows * 0.4).toInt())
        val glanceBlockRows = max(0, screenBlockRows - thumbBlockRows)

        val placed = ArrayList<Pair<MetroTile, Pair<Int, Int>>>() // tile to (col,row)
        val used = HashSet<String>()
        fun take(list: List<App>, filter: (App) -> Boolean = { true }): App? =
            list.firstOrNull { it.key !in used && filter(it) }?.also { used += it.key }

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

        // Sizes info tiles take: the clock (next alarm) is small; photos, calendar and weather
        // are medium or wide, never small.
        // A size you chose yourself always wins.
        fun mediumOk(a: App) = (a.kind != Kind.CLOCK || manualSize(a) == TileSize.MEDIUM) && okFor(a, TileSize.MEDIUM)
        fun smallOk(a: App) =
            (a.kind !in setOf(Kind.PHOTOS, Kind.CALENDAR, Kind.WEATHER) || manualSize(a) == TileSize.SMALL) && okFor(a, TileSize.SMALL)

        // ---- Easy-reach area: most-opened apps ----
        val byTap = ranked.filter {
            it.kind != Kind.WIDGET && (it.kind !in INFO || prefers(it, MetroUsage.Region.BOTTOM)) &&
                !prefers(it, MetroUsage.Region.TOP) && !prefers(it, MetroUsage.Region.BELOW)
        }
            .sortedWith(compareByDescending<App> { prefers(it, MetroUsage.Region.BOTTOM) }.thenByDescending { it.tap })
        val thumbPatterns = thumbPatterns(blocksPerRow)
        // Fill the bottom row first, so the very top apps sit lowest (nearest the thumb).
        val thumbRowsTop = glanceBlockRows
        for (k in thumbBlockRows - 1 downTo 0) {
            val br = thumbRowsTop + k
            layRow(
                br,
                thumbPatterns[k % thumbPatterns.size],
                mPick = { take(byTap) { mediumOk(it) } },
                sPick = { take(byTap) { smallOk(it) } },
                wPick = { if (windowMode) take(byTap) { wideOk(it) && okFor(it, TileSize.WIDE) } else null },
            )
        }

        // ---- Glance area: a hero wide tile and live tiles ----
        val liveFirst = ranked.filter { !prefers(it, MetroUsage.Region.BELOW) }
            .sortedWith(
                compareByDescending<App> { it.kind == Kind.WIDGET }
                    .thenByDescending { prefers(it, MetroUsage.Region.TOP) }
                    .thenByDescending { it.kind in INFO }
                    .thenByDescending { it.kind != Kind.OTHER && it.kind != Kind.SHORTCUT }
                    .thenByDescending { it.score },
            )

        // Who gets the wide slots at the top: music that's playing, then the photo slideshow,
        // then your busiest chat; a busy calendar, widgets and weather after that.
        val todayEvents = run {
            val t0 = InfoTiles.dayOf(now)
            InfoTiles.events.count { it.begin < t0 + InfoTiles.DAY && it.end > now }
        }
        fun heroRank(a: App): Double = when {
            LiveTileData.snapshot[a.pkg]?.let { it.isMusic && it.isPlaying } == true -> 6.0
            a.kind == Kind.PHOTOS -> 5.0
            a.kind == Kind.CHAT -> 4.0 + (a.live / 100.0).coerceAtMost(0.9)
            a.kind == Kind.MUSIC -> 3.5
            a.kind == Kind.CALENDAR && todayEvents >= 2 -> 3.0
            a.kind == Kind.WIDGET -> 2.0
            a.live > 2 -> 1.5
            a.kind == Kind.WEATHER -> 1.0
            else -> 0.0
        }
        val heroOrder = liveFirst.filter { heroRank(it) > 0 }.sortedByDescending { heroRank(it) }
        val hero = {
            take(heroOrder) { wideOk(it) && okFor(it, TileSize.WIDE) } ?: take(heroOrder) { okFor(it, TileSize.WIDE) }
        }
        val glancePatterns = glancePatterns(blocksPerRow)
        for (br in 0 until glanceBlockRows) {
            layRow(
                br,
                glancePatterns[br % glancePatterns.size],
                mPick = { take(liveFirst) { mediumOk(it) } },
                // Quads: the next alarm first, then the rest in order.
                sPick = { take(liveFirst) { it.kind == Kind.CLOCK && smallOk(it) } ?: take(liveFirst) { smallOk(it) } },
                wPick = hero,
            )
        }

        // ---- Below the first screen: the rest worth keeping, same rhythm ----
        // Only apps you actually use earn a place below the first screen.
        // Apps used about as much sit by kind, so similar apps tend to land near each other.
        val keep = ranked.filter { it.key !in used && (it in forced || it.kind in INFO || it.tap >= 3.0 || it.live >= 2.0) }
            .sortedWith(compareByDescending<App> { Math.floor(it.score / 3.0) }.thenBy { it.kind.ordinal }.thenByDescending { it.score })
        val maxExtraBlockRows = screenBlockRows // about one more screen
        val restPatterns = restPatterns(blocksPerRow)
        var br = screenBlockRows
        var guard = 0
        while (keep.any { it.key !in used } && guard < maxExtraBlockRows) {
            val remaining = keep.count { it.key !in used }
            // Medium tiles for apps with live content or heavy use; quads for the rest.
            val mediumWorthy = { a: App -> a.kind != Kind.OTHER || a.live >= 2 || a.tap >= 8 }
            val pattern = when {
                blocksPerRow == 2 && remaining <= 2 -> List(remaining) { Block.MEDIUM }
                blocksPerRow >= 3 && remaining <= 2 -> List(remaining) { Block.MEDIUM }
                else -> restPatterns[guard % restPatterns.size]
            }.let { p ->
                // Something that only fits small (the next-alarm tile) needs a quad somewhere.
                if (Block.QUAD !in p && keep.any { it.key !in used && !mediumOk(it) && smallOk(it) }) {
                    p.dropLast(1) + Block.QUAD
                } else {
                    p
                }
            }
            layRow(
                br,
                pattern,
                mPick = { take(keep) { mediumWorthy(it) && mediumOk(it) } ?: take(keep) { mediumOk(it) } },
                // Quads take the least-used apps, so small tiles hold what you open least.
                sPick = { keep.lastOrNull { it.key !in used && smallOk(it) }?.also { used += it.key } },
                wPick = {
                    take(keep) { (it.kind != Kind.OTHER || it.live >= 2) && wideOk(it) && okFor(it, TileSize.WIDE) }
                        ?: take(keep) { wideOk(it) && okFor(it, TileSize.WIDE) }
                        ?: take(keep) { okFor(it, TileSize.WIDE) }
                },
            )
            br++
            guard++
        }
        // Forced apps that didn't get a slot (e.g. locked wide tiles) go at the end.
        val leftovers = forced.filter { it.key !in used }
        var tailRow = br * 2
        for (a in leftovers) {
            val size = manualSize(a) ?: TileSize.MEDIUM
            placed += tileFor(a, size) to (0 to tailRow)
            tailRow += size.rowSpan
            used += a.key
        }

        spreadBrands(placed, columns, screenRows) { t -> isBrandTile(t, t.component) }
        // Info tiles (calendar, weather, photos, alarm) aren't put side by side either, so the
        // top doesn't turn into a block of dashboards.
        val infoPkgs = apps.filter { it.kind in INFO }.map { it.pkg }.toSet()
        spreadBrands(placed, columns, screenRows) { t -> t.isApp && t.component.packageName in infoPkgs }

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

    // Variety is the point: neighbouring rows never repeat, wide tiles turn up all the way down,
    // and each row has at most one quad of small tiles (on 6 columns small tiles get tiny, so
    // they appear sparingly there).

    private fun glancePatterns(blocks: Int): List<List<Block>> = when (blocks) {
        2 -> listOf(listOf(Block.WIDE_LEFT), listOf(Block.MEDIUM, Block.QUAD), listOf(Block.MEDIUM, Block.MEDIUM))
        3 -> listOf(
            listOf(Block.WIDE_LEFT, Block.MEDIUM),
            listOf(Block.MEDIUM, Block.QUAD, Block.MEDIUM),
            listOf(Block.MEDIUM, Block.WIDE_LEFT),
            listOf(Block.QUAD, Block.MEDIUM, Block.MEDIUM),
        )
        else -> listOf(List(blocks) { Block.MEDIUM })
    }

    /** Index 0 is the top row of the easy-reach area, the last index the bottom row. */
    private fun thumbPatterns(blocks: Int): List<List<Block>> = when (blocks) {
        2 -> listOf(listOf(Block.QUAD, Block.MEDIUM), listOf(Block.MEDIUM, Block.QUAD))
        3 -> listOf(
            listOf(Block.MEDIUM, Block.QUAD, Block.MEDIUM),
            listOf(Block.WIDE_LEFT, Block.MEDIUM),
        )
        else -> listOf(List(blocks) { Block.MEDIUM })
    }

    private fun restPatterns(blocks: Int): List<List<Block>> = when (blocks) {
        2 -> listOf(
            listOf(Block.MEDIUM, Block.QUAD),
            listOf(Block.WIDE_LEFT),
            listOf(Block.QUAD, Block.MEDIUM),
            listOf(Block.MEDIUM, Block.MEDIUM),
        )
        3 -> listOf(
            listOf(Block.MEDIUM, Block.WIDE_LEFT),
            listOf(Block.QUAD, Block.MEDIUM, Block.MEDIUM),
            listOf(Block.WIDE_LEFT, Block.MEDIUM),
            listOf(Block.MEDIUM, Block.MEDIUM, Block.QUAD),
            listOf(Block.MEDIUM, Block.QUAD, Block.MEDIUM),
        )
        else -> listOf(List(blocks) { Block.MEDIUM })
    }

    // ---- Signals ----------------------------------------------------------------------------

    /**
     * Swaps tiles so brand-coloured ones never sit side by side and there are at most two in a
     * row of blocks. Only tiles of the same size within the same screen swap, so the layout's
     * shape and the usage order stay as they were.
     */
    private fun spreadBrands(
        placed: MutableList<Pair<MetroTile, Pair<Int, Int>>>,
        columns: Int,
        screenRows: Int,
        isBrand: (MetroTile) -> Boolean,
    ) {
        if (placed.isEmpty()) return
        val order = placed.indices.sortedWith(compareBy({ placed[it].second.second }, { placed[it].second.first }))
        val brand = BooleanArray(placed.size) { isBrand(placed[it].first) }
        fun rectOf(i: Int): IntArray {
            val (c, r) = placed[i].second
            val sz = placed[i].first.size
            return intArrayOf(c, r, c + sz.span.coerceAtMost(columns), r + sz.rowSpan)
        }
        fun touching(a: Int, b: Int): Boolean {
            val x = rectOf(a)
            val y = rectOf(b)
            return x[0] <= y[2] && y[0] <= x[2] && x[1] <= y[3] && y[1] <= x[3]
        }
        fun violates(i: Int, settled: List<Int>): Boolean {
            if (settled.any { touching(i, it) }) return true
            val blockRow = placed[i].second.second / 2
            return settled.count { placed[it].second.second / 2 == blockRow } >= 2
        }
        fun screenOf(i: Int) = placed[i].second.second / screenRows.coerceAtLeast(1)
        val settled = ArrayList<Int>()
        for ((k, i) in order.withIndex()) {
            if (!brand[i]) continue
            if (!violates(i, settled)) {
                settled += i
                continue
            }
            // Find a later, same-size, plain tile on the same screen whose spot works.
            val swapWith = order.drop(k + 1).firstOrNull { j ->
                !brand[j] && placed[j].first.size == placed[i].first.size && screenOf(j) == screenOf(i) &&
                    !violates(j, settled)
            }
            if (swapWith == null) {
                settled += i // nothing better; Start's quarter cap and neighbour rule still apply
                continue
            }
            val ti = placed[i].first
            val tj = placed[swapWith].first
            placed[i] = tj to placed[i].second
            placed[swapWith] = ti to placed[swapWith].second
            brand[i] = false
            brand[swapWith] = true
        }
    }

    private fun gatherApps(context: Context, current: List<MetroTile>): List<App> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val activities = launcherApps?.getActivityList(null, Process.myUserHandle()).orEmpty()
            .filter { it.componentName.packageName != context.packageName }
            .distinctBy { it.componentName.packageName }
        val opens = MetroUsage.appOpens(context)
        val minutes = MetroUsage.foregroundMinutes(context)
        val activeNow = LiveTileData.snapshot
        val byPkg = current.filter { it.isApp }.associateBy { it.component.packageName }
        // Widget and shortcut tiles you added: always kept, placed by type.
        val extras = current.filter { !it.isApp }.map { t ->
            val kind = if (t.kind == MetroTile.Kind.WIDGET) Kind.WIDGET else Kind.SHORTCUT
            App(
                t.component, kind,
                tap = if (kind == Kind.SHORTCUT) 40.0 else 0.0,
                live = if (kind == Kind.WIDGET) 100.0 else 0.0,
                existing = t, manual = null, fixed = t,
            )
        }
        val slideshowOn = app.lawnchair.preferences.PreferenceManager.getInstance(context).metroPhotoSlideshow.get() &&
            InfoTiles.hasPhotoAccess(context)
        val alarmSoon = runCatching {
            val next = context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock
            next != null && next.triggerTime - System.currentTimeMillis() in 0..DAY
        }.getOrDefault(false)
        return extras + activities.map { info ->
            val pkg = info.componentName.packageName
            val tap = MetroUsage.launchScore(context, pkg) + (opens[pkg] ?: 0.0) + min(minutes[pkg] ?: 0.0, 900.0) / 30.0
            val notif = MetroUsage.notificationsPerDay(context, pkg) + (activeNow[pkg]?.count ?: 0) * 0.5
            var t = tap
            if (byPkg[pkg] != null) t += 1.0 // a little stability for what's already on Start
            val kind = when (InfoTiles.kindOf(pkg, info.label)) {
                app.lawnchair.metro.info.InfoKind.CALENDAR -> Kind.CALENDAR
                app.lawnchair.metro.info.InfoKind.WEATHER -> Kind.WEATHER
                app.lawnchair.metro.info.InfoKind.CLOCK -> if (alarmSoon) Kind.CLOCK else Kind.OTHER
                app.lawnchair.metro.info.InfoKind.PHOTOS -> if (slideshowOn) Kind.PHOTOS else Kind.OTHER
                null -> kindOf(pkg, info.applicationInfo)
            }
            App(info.componentName, kind, t, notif, byPkg[pkg], MetroUsage.manual(context, pkg))
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
