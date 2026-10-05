package app.lawnchair.metro.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.Toast
import app.lawnchair.metro.data.MetroTileStore
import app.lawnchair.metro.data.TileSize

/**
 * Hosts Android widgets on Start, as tiles.
 *
 * Metro keeps its own widget host (separate from Lawnchair's hidden home screen). Adding a widget
 * follows Android's rules: Metro asks for permission to bind it if needed, runs the widget's
 * setup screen if it has one, then pins it as a tile sized to fit.
 */
object MetroWidgets {

    private const val HOST_ID = 0x4D45 // "ME"
    const val REQUEST_BIND = 7301
    const val REQUEST_CONFIGURE = 7302

    private var host: AppWidgetHost? = null
    private var pending: Pair<Int, AppWidgetProviderInfo>? = null

    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    fun startListening(context: Context) = runCatching { host(context).startListening() }
    fun stopListening(context: Context) = runCatching { host(context).stopListening() }

    fun createView(context: Context, widgetId: Int): AppWidgetHostView? {
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId) ?: return null
        return runCatching { host(context).createView(context, widgetId, info) }.getOrNull()
    }

    fun delete(context: Context, widgetId: Int) = runCatching { host(context).deleteAppWidgetId(widgetId) }

    /** All widgets the user can add. */
    fun providers(context: Context): List<AppWidgetProviderInfo> =
        AppWidgetManager.getInstance(context).getInstalledProvidersForProfile(android.os.Process.myUserHandle())

    /** Starts adding [info]: bind (asking permission if needed), configure, then pin. */
    fun add(activity: Activity, info: AppWidgetProviderInfo) {
        val id = host(activity).allocateAppWidgetId()
        val ok = AppWidgetManager.getInstance(activity).bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        pending = id to info
        if (ok) {
            afterBind(activity)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            activity.startActivityForResult(intent, REQUEST_BIND)
        }
    }

    private fun afterBind(activity: Activity) {
        val (id, info) = pending ?: return
        if (info.configure != null) {
            runCatching {
                host(activity).startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
            }.onFailure { finish(activity) }
        } else {
            finish(activity)
        }
    }

    private fun finish(context: Context) {
        val (id, info) = pending ?: return
        pending = null
        MetroTileStore.get(context).addWidget(info.provider, id, sizeFor(context, info))
    }

    private fun cancel(context: Context) {
        val (id, _) = pending ?: return
        pending = null
        delete(context, id)
    }

    /** Routes the bind / configure results. Returns true if the result was ours. */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean {
        when (requestCode) {
            REQUEST_BIND -> if (resultCode == Activity.RESULT_OK) afterBind(activity) else {
                cancel(activity)
                Toast.makeText(activity, "Widget not added", Toast.LENGTH_SHORT).show()
            }
            REQUEST_CONFIGURE -> if (resultCode == Activity.RESULT_OK) finish(activity) else cancel(activity)
            else -> return false
        }
        return true
    }

    /** Smallest tile the widget fits in, from its minimum size. */
    private fun sizeFor(context: Context, info: AppWidgetProviderInfo): TileSize {
        val d = context.resources.displayMetrics.density
        val wDp = info.minWidth / d
        val hDp = info.minHeight / d
        return when {
            wDp <= 180 && hDp <= 180 -> TileSize.MEDIUM
            hDp <= 180 -> TileSize.WIDE
            else -> TileSize.LARGE
        }
    }

    /** Tells the widget the space it really has, so it picks the right layout. */
    fun updateSize(view: AppWidgetHostView, widthPx: Int, heightPx: Int) {
        val d = view.resources.displayMetrics.density
        val w = widthPx / d
        val h = heightPx / d
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(null, w.toInt(), h.toInt(), w.toInt(), h.toInt())
            }
        }
    }
}
