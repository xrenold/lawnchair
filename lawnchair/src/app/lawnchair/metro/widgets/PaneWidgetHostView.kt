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

    /** The size last sent to the widget's app (see MetroWidgets.updateSize). */
    var sentWidth = 0
    var sentHeight = 0

    /** The failure has been logged (once is enough: the app may resend its widget often). */
    private var failureLogged = false

    /** Set when Android couldn't build the widget's content ("Can't load widget"). */
    private var failed = false

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        failed = false
        super.updateAppWidget(remoteViews)
        val had = hasContent
        hasContent = remoteViews != null && !failed
        if (failed && remoteViews != null && !failureLogged) {
            failureLogged = true
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

    /**
     * Makes the widget see-through: its card is cleared so the content sits straight on the tile
     * (its colour, or the background for see-through tiles). The card is the view marked
     * `@android:id/background` (Material You widgets) or, for other widgets, any background that
     * covers most of the widget. Smaller backgrounds (buttons, chips) and pictures (a map, album
     * art) are kept. Not done when the tile keeps the widget's own look.
     */
    private fun restyle() {
        val card = findViewById<View>(android.R.id.background)
        hasCard = card != null
        if (ownBackground) return
        if (card != null) clearCard(card)
        if (width > 0 && height > 0) clearLargeBackgrounds(this, width * height)
    }

    private fun clearCard(v: View) {
        if (v.background != null) v.background = null
        v.clipToOutline = false
    }

    private fun clearLargeBackgrounds(group: ViewGroup, area: Int) {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c.background != null && c.width * c.height >= area * 0.6f) clearCard(c)
            if (c is ViewGroup) clearLargeBackgrounds(c, area)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Sizes are known only after layout: clear the big backgrounds now (and after updates).
        restyle()
    }
}
