package app.lawnchair.metro.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.SizeF
import android.util.SparseIntArray
import android.widget.Toast
import app.lawnchair.metro.CrashLog
import app.lawnchair.metro.data.MetroTileStore
import app.lawnchair.metro.data.TileSize
import com.android.systemui.monet.ColorScheme

/**
 * Hosts Android widgets on Start, as tiles.
 *
 * Pane keeps its own widget host (separate from Lawnchair's hidden home screen). Adding a widget
 * follows Android's rules: Pane asks for permission to bind it if needed, runs the widget's
 * setup screen if it has one, then puts it on Start: either in place of an app's tile ("show
 * widget" on that tile) or at the end of Start (an app asking to pin one).
 */
object MetroWidgets {

    private const val HOST_ID = 0x4D45 // "ME"
    const val REQUEST_BIND = 7301
    const val REQUEST_CONFIGURE = 7302
    private const val TAG = "widget"

    /** A widget being added: its id, what it is, and the tile it replaces (if any). */
    private data class Pending(val id: Int, val info: AppWidgetProviderInfo, val replaceTileId: Long?)

    private var host: AppWidgetHost? = null
    private var pending: Pending? = null

    fun host(context: Context): AppWidgetHost =
        host ?: PaneWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    fun startListening(context: Context) {
        runCatching { host(context).startListening() }
            .onFailure { CrashLog.event(TAG, "host couldn't start listening", it) }
    }

    fun stopListening(context: Context) {
        runCatching { host(context).stopListening() }
    }

    /**
     * Views made so far, one per widget. Start rebuilds its tiles often (any layout change); the
     * same view is handed out again, already showing the widget, rather than a fresh one that
     * starts at "Loading…".
     */
    private val views = HashMap<Int, PaneWidgetHostView>()

    fun createView(context: Context, widgetId: Int): PaneWidgetHostView? {
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)
        if (info == null) {
            CrashLog.event(TAG, "widget $widgetId is no longer bound")
            return null
        }
        views[widgetId]?.let { v ->
            if (v.context === context) {
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                v.scaleX = 1f
                v.scaleY = 1f
                v.onContentChanged = null
                return v
            }
        }
        return runCatching { (host(context).createView(context, widgetId, info) as PaneWidgetHostView).also { views[widgetId] = it } }
            .onFailure { CrashLog.event(TAG, "couldn't load ${info.provider.flattenToShortString()}", it) }
            .getOrNull()
    }

    fun delete(context: Context, widgetId: Int) {
        views.remove(widgetId)
        runCatching { host(context).deleteAppWidgetId(widgetId) }
    }

    /** All widgets the user can add, optionally just [packageName]'s. */
    fun providers(context: Context, packageName: String? = null): List<AppWidgetProviderInfo> =
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            if (packageName != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.getInstalledProvidersForPackage(packageName, Process.myUserHandle())
            } else {
                manager.getInstalledProvidersForProfile(Process.myUserHandle())
                    .filter { packageName == null || it.provider.packageName == packageName }
            }
        }.getOrDefault(emptyList())
            // Widgets meant only for the lock screen or keyguard can't sit on Start.
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }

    fun hasWidgets(context: Context, packageName: String) = providers(context, packageName).isNotEmpty()

    /**
     * Starts adding [info]: bind (asking permission if needed), configure, then put it on Start,
     * shown on app tile [forTileId] when given (as one of its faces), else as a widget tile of its
     * own at the end of Start.
     */
    fun add(activity: Activity, info: AppWidgetProviderInfo, forTileId: Long? = null) {
        val replaceTileId = forTileId
        val id = host(activity).allocateAppWidgetId()
        pending = Pending(id, info, replaceTileId)
        CrashLog.event(TAG, "adding ${info.provider.flattenToShortString()} as $id")
        val ok = runCatching {
            AppWidgetManager.getInstance(activity).bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        }.getOrDefault(false)
        if (ok) {
            afterBind(activity)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            runCatching { activity.startActivityForResult(intent, REQUEST_BIND) }.onFailure {
                CrashLog.event(TAG, "couldn't ask to bind", it)
                cancel(activity)
                Toast.makeText(activity, "Couldn't add the widget", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun afterBind(activity: Activity) {
        val p = pending ?: return
        // A widget with a setup screen always gets it: some (Maps) say setup is optional but
        // never draw anything until it's been done.
        if (p.info.configure != null) {
            runCatching {
                host(activity).startAppWidgetConfigureActivityForResult(activity, p.id, 0, REQUEST_CONFIGURE, null)
            }.onFailure {
                CrashLog.event(TAG, "setup screen didn't open; adding anyway", it)
                finish(activity)
            }
        } else {
            finish(activity)
        }
    }

    private fun finish(context: Context) {
        val p = pending ?: return
        pending = null
        val store = MetroTileStore.get(context)
        if (p.replaceTileId != null) {
            val old = store.setTileWidget(p.replaceTileId, p.id, sizeFor(context, p.info))
            if (old != 0 && old != p.id) delete(context, old)
        } else {
            store.addWidget(p.info.provider, p.id, sizeFor(context, p.info))
        }
        CrashLog.event(TAG, "added ${p.info.provider.flattenToShortString()} as ${p.id}")
    }

    private fun cancel(context: Context) {
        val p = pending ?: return
        pending = null
        delete(context, p.id)
    }

    /** Routes the bind / configure results. Returns true if the result was ours. */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean {
        when (requestCode) {
            REQUEST_BIND -> if (resultCode == Activity.RESULT_OK) {
                afterBind(activity)
            } else {
                CrashLog.event(TAG, "bind declined ($resultCode)")
                cancel(activity)
                Toast.makeText(activity, "Widget not added", Toast.LENGTH_SHORT).show()
            }
            REQUEST_CONFIGURE -> if (resultCode == Activity.RESULT_OK) {
                finish(activity)
            } else {
                CrashLog.event(TAG, "setup cancelled ($resultCode)")
                cancel(activity)
            }
            else -> return false
        }
        return true
    }

    /** Smallest tile the widget fits in, from its minimum size. */
    fun sizeFor(context: Context, info: AppWidgetProviderInfo): TileSize {
        val d = context.resources.displayMetrics.density
        val wDp = info.minWidth / d
        val hDp = info.minHeight / d
        return when {
            wDp <= 180 && hDp <= 180 -> TileSize.MEDIUM
            hDp <= 180 -> TileSize.WIDE
            else -> TileSize.LARGE
        }
    }

    /** The app's own launcher activity, for opening it from its widget tile. */
    fun appFor(context: Context, provider: ComponentName): ComponentName? =
        runCatching {
            context.getSystemService(LauncherApps::class.java)
                ?.getActivityList(provider.packageName, Process.myUserHandle())
                ?.firstOrNull()?.componentName
        }.getOrNull()

    /** Tells the widget the space it really has, so it picks the right layout. */
    fun updateSize(view: AppWidgetHostView, widthPx: Int, heightPx: Int) {
        val d = view.resources.displayMetrics.density
        // The host view's own padding comes off the space the widget gets.
        val w = (widthPx - view.paddingLeft - view.paddingRight) / d
        val h = (heightPx - view.paddingTop - view.paddingBottom) / d
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(null, w.toInt(), h.toInt(), w.toInt(), h.toInt())
            }
        }
    }

    private val SHADES = listOf(10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

    /**
     * Material You colours built from [seed] (the tile's colour), for widgets that draw with the
     * system palette: they take the tile's colour instead of the wallpaper's.
     */
    fun paletteFor(context: Context, seed: Int): SparseIntArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val scheme = ColorScheme(seed)
            val out = SparseIntArray()
            val res = context.resources
            listOf(
                "accent1" to scheme.accent1.allShades,
                "accent2" to scheme.accent2.allShades,
                "accent3" to scheme.accent3.allShades,
                "neutral1" to scheme.neutral1.allShades,
                "neutral2" to scheme.neutral2.allShades,
            ).forEach { (name, shades) ->
                val white = res.getIdentifier("system_${name}_0", "color", "android")
                if (white != 0) out.put(white, android.graphics.Color.WHITE)
                SHADES.zip(shades).forEach { (shade, color) ->
                    val id = res.getIdentifier("system_${name}_$shade", "color", "android")
                    if (id != 0) out.put(id, color)
                }
            }
            out
        }.onFailure { CrashLog.event(TAG, "couldn't build widget colours", it) }.getOrNull()
    }

    private class PaneWidgetHost(context: Context, id: Int) : AppWidgetHost(context, id) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            PaneWidgetHostView(context)
    }
}
