package app.lawnchair.metro.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews

/**
 * A widget drawn as a Pane tile.
 *
 * Widgets built the Material You way mark their card with `@android:id/background`. Unless the
 * tile is set to keep the widget's own look, that card is cleared, so the widget's content sits
 * straight on the tile's colour (or on the background, for see-through tiles) with square edges.
 * Widgets that don't mark a card keep theirs and fill the tile edge to edge.
 */
class PaneWidgetHostView(context: Context) : AppWidgetHostView(context) {

    /** Keep the widget's own card instead of the tile's colour. */
    var ownBackground = false
        set(value) {
            if (field == value) return
            field = value
            restyle()
        }

    private var hasCard = false

    /** True once the widget has drawn something (until then Android shows "Loading…"). */
    var hasContent = false
        private set

    /** Called when [hasContent] changes. */
    var onContentChanged: (() -> Unit)? = null

    /** The screen the view was made for (it's inflated with a plain context; see MetroWidgets). */
    var owner: Context? = null

    /** Set when Android couldn't build the widget's content ("Can't load widget"). */
    private var failed = false

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        failed = false
        super.updateAppWidget(remoteViews)
        val had = hasContent
        hasContent = remoteViews != null && !failed
        if (failed && remoteViews != null) {
            // Find out why, for the crash report: build it once more and keep the error.
            val error = runCatching { remoteViews.apply(context, this) }.exceptionOrNull()
            app.lawnchair.metro.CrashLog.event(
                "widget",
                "couldn't show ${appWidgetInfo?.provider?.flattenToShortString()}",
                error,
            )
        }
        restyle()
        if (had != hasContent) onContentChanged?.invoke()
    }

    /** Android's "Can't load widget": the tile shows the app's icon instead (see TileView). */
    override fun getErrorView(): View {
        failed = true
        return View(context)
    }

    /** Edge to edge: the widget's own padding is enough; Android's default border isn't added. */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) = super.setPadding(0, 0, 0, 0)

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        post { restyle() }
    }

    /** Clears the widget's card, or leaves it; sets the padding to match. */
    private fun restyle() {
        val card = findViewById<View>(android.R.id.background)
        hasCard = card != null
        if (card != null && !ownBackground) {
            if (card.background != null) card.background = null
            card.clipToOutline = false
            // Some widgets round their inner panels too; let the tile's square edge win.
            (card as? ViewGroup)?.clipChildren = true
        }
    }
}
