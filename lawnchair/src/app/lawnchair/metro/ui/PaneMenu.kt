package app.lawnchair.metro.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import app.lawnchair.metro.theme.MetroTheme
import app.lawnchair.metro.theme.PaneFonts

/**
 * Pane's long-press menu: square, dark, Selawik, items as written (Pane's own are lowercase).
 *
 * It shows an ordinary [Menu] (built with a PopupMenu's menu, so callers keep using
 * add/addSubMenu/setCheckable), but draws it itself instead of the phone's popup style. A
 * submenu replaces the list in place, with its title on top to go back. Checked items are in
 * the accent colour. The menu swings open from the top edge, like a page turning toward you.
 */
object PaneMenu {

    fun show(
        anchor: View,
        menu: Menu,
        onPick: PopupMenu.OnMenuItemClickListener,
        gravity: Int = Gravity.START,
        onDismiss: (() -> Unit)? = null,
    ) {
        val context = anchor.context
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(BACKGROUND)
            addView(list)
        }
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels - dp(context, 32f)).coerceAtMost(dp(context, 300f))
        val window = PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(context, 12f).toFloat()
            animationStyle = 0
            setOnDismissListener { onDismiss?.invoke() }
        }

        fun fill(current: Menu, title: CharSequence?) {
            list.removeAllViews()
            if (title != null) {
                list.addView(row(context, "‹  ${title}", header = true).apply {
                    setOnClickListener { fill(menu, null) }
                })
            }
            for (i in 0 until current.size()) {
                val item = current.getItem(i)
                if (!item.isVisible) continue
                val checked = item.isCheckable && item.isChecked
                val label = if (item.hasSubMenu()) "${item.title}  ›" else item.title.toString()
                list.addView(row(context, label, checked = checked).apply {
                    isEnabled = item.isEnabled
                    alpha = if (item.isEnabled) 1f else 0.4f
                    setOnClickListener {
                        val sub = item.subMenu
                        if (item.hasSubMenu() && sub != null) {
                            fill(sub, item.title)
                        } else {
                            window.dismiss()
                            onPick.onMenuItemClick(item)
                        }
                    }
                })
            }
        }
        fill(menu, null)

        // Below the anchor when there's room, otherwise above it (PopupWindow flips for us).
        window.showAsDropDown(anchor, 0, 0, gravity)
        scroll.pivotY = 0f
        scroll.rotationX = -55f
        scroll.alpha = 0f
        scroll.cameraDistance = 8000 * dm.density
        scroll.animate().rotationX(0f).alpha(1f).setDuration(220)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun row(context: Context, text: String, checked: Boolean = false, header: Boolean = false) = TextView(context).apply {
        this.text = text
        typeface = if (header) PaneFonts.semibold else PaneFonts.regular
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (header) 14f else 19f)
        setTextColor(
            when {
                header -> 0x99FFFFFF.toInt()
                checked -> readable(MetroTheme.accent(context))
                else -> Color.WHITE
            },
        )
        val h = dp(context, 20f)
        setPadding(h, dp(context, if (header) 14f else 12f), h, dp(context, if (header) 6f else 12f))
        isClickable = true
        isFocusable = true
        background = context.getDrawable(android.R.drawable.list_selector_background)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Dark accents lifted toward white so checked items stay readable on the dark menu. */
    internal fun readable(color: Int): Int {
        val lum = androidx.core.graphics.ColorUtils.calculateLuminance(color)
        return if (lum < 0.18) androidx.core.graphics.ColorUtils.blendARGB(color, Color.WHITE, 0.45f) else color
    }

    internal fun dp(context: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    internal const val BACKGROUND = 0xFF1F1F1F.toInt()
}

/** Shortcut for the usual case: [PopupMenu] as a menu builder, shown Pane's way. */
fun PopupMenu.showPane(anchor: View, gravity: Int = Gravity.START, onDismiss: (() -> Unit)? = null, onPick: (MenuItem) -> Boolean) =
    PaneMenu.show(anchor, menu, { onPick(it) }, gravity, onDismiss)
