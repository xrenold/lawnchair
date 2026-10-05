package app.lawnchair.metro.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process

/**
 * App shortcuts as Start tiles ("New chat with…", "Incognito tab", a Chrome page pinned with
 * "Add to Home screen"…). Android only lets the default launcher use them.
 */
object MetroShortcuts {

    private const val FLAGS = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

    private fun la(context: Context) = context.getSystemService(LauncherApps::class.java)

    /** Shortcuts an app offers, for the app list's "Pin shortcut" menu. */
    fun forApp(context: Context, pkg: String): List<ShortcutInfo> {
        val launcherApps = la(context) ?: return emptyList()
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery().setPackage(pkg)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        return runCatching { launcherApps.getShortcuts(q, Process.myUserHandle()) }.getOrNull().orEmpty()
            .filter { it.isEnabled }
            .sortedBy { it.rank }
    }

    fun find(context: Context, pkg: String, id: String): ShortcutInfo? {
        val launcherApps = la(context) ?: return null
        val q = LauncherApps.ShortcutQuery().setPackage(pkg).setShortcutIds(listOf(id)).setQueryFlags(FLAGS)
        return runCatching { launcherApps.getShortcuts(q, Process.myUserHandle()) }.getOrNull()?.firstOrNull()
    }

    /**
     * Pins [info] for Metro (so the app keeps it available) and adds it to Start. Apps only keep
     * shortcuts a launcher has pinned, and pinning replaces the set, so we pin ours together.
     */
    fun pinToStart(context: Context, info: ShortcutInfo) {
        val launcherApps = la(context) ?: return
        runCatching {
            val q = LauncherApps.ShortcutQuery().setPackage(info.`package`)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val pinned = launcherApps.getShortcuts(q, Process.myUserHandle()).orEmpty().map { it.id }
            launcherApps.pinShortcuts(info.`package`, (pinned + info.id).distinct(), Process.myUserHandle())
        }
        added(context, info)
    }

    /** A shortcut the system already pinned for us (an app's "Add to Home screen"). */
    @JvmStatic
    fun added(context: Context, info: ShortcutInfo) {
        val label = (info.shortLabel ?: info.longLabel)?.toString()
        MetroTileStore.get(context).addShortcut(info.`package`, info.id, label)
    }

    fun icon(context: Context, info: ShortcutInfo): Drawable? =
        runCatching { la(context)?.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi) }.getOrNull()

    fun start(context: Context, pkg: String, id: String, bounds: Rect?, options: Bundle?): Boolean =
        runCatching {
            la(context)?.startShortcut(pkg, id, bounds, options, Process.myUserHandle())
            true
        }.getOrDefault(false)
}
