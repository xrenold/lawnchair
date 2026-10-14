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

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
        restyle()
    }

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
        // A cleared card leaves the content needing room from the tile's edge; a widget with
        // its own card already has it.
        val pad = if (hasCard && !ownBackground) dp(6f) else 0
        if (paddingLeft != pad || paddingTop != pad) setPadding(pad, pad, pad, pad)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
