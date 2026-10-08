package app.lawnchair.metro.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.lawnchair.metro.theme.MetroTheme
import app.lawnchair.metro.theme.PaneFonts

/**
 * Pane's dialog: a dark band across the top of the screen, a light lowercase title, content,
 * and outlined buttons at the bottom right. Replaces the phone's own dialogs in Pane.
 */
object PaneDialog {

    /** A message with an OK button. */
    fun message(context: Context, title: String, text: String) {
        val body = text(context, text, 15f, 0xD9FFFFFF.toInt())
        show(context, title, body, buttons = listOf("ok" to {}))
    }

    /** A list of choices; tapping one picks it and closes. [selected] is shown in the accent colour. */
    fun choose(context: Context, title: String, labels: List<String>, selected: Int = -1, onPick: (Int) -> Unit) {
        lateinit var dialog: Dialog
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.forEachIndexed { i, label ->
            list.addView(item(context, label, i == selected).apply {
                setOnClickListener {
                    dialog.dismiss()
                    onPick(i)
                }
            })
        }
        dialog = show(context, title, list, buttons = listOf("cancel" to {}))
    }

    /** Several choices that can each be on or off; [onDone] gets the result when "done" is tapped. */
    fun chooseMany(context: Context, title: String, labels: List<String>, checked: BooleanArray, onDone: (BooleanArray) -> Unit) {
        val state = checked.copyOf()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.forEachIndexed { i, label ->
            val row = item(context, label, state[i], box = true)
            row.setOnClickListener {
                state[i] = !state[i]
                styleItem(context, row, label, state[i], box = true)
            }
            list.addView(row)
        }
        show(context, title, list, buttons = listOf("cancel" to {}, "done" to { onDone(state) }))
    }

    private fun show(context: Context, title: String, content: View, buttons: List<Pair<String, () -> Unit>>): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pad = PaneMenu.dp(context, 24f)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PaneMenu.BACKGROUND)
            setPadding(pad, PaneMenu.dp(context, 20f), pad, PaneMenu.dp(context, 16f))
        }
        column.addView(text(context, title.lowercase(), 26f, Color.WHITE, PaneFonts.light))
        val maxBody = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxBody, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = PaneMenu.dp(context, 14f)
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.forEach { (label, action) ->
            row.addView(button(context, label) {
                dialog.dismiss()
                action()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = PaneMenu.dp(context, 10f)
            })
        }
        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = PaneMenu.dp(context, 18f)
        })
        // If the window reaches under the status bar, keep the title clear of it.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(pad, PaneMenu.dp(context, 20f) + top, pad, PaneMenu.dp(context, 16f))
            insets
        }
        dialog.setContentView(column)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setWindowAnimations(0)
        }
        dialog.show()
        // Swings down from the top edge.
        column.pivotY = 0f
        column.rotationX = -45f
        column.alpha = 0f
        column.cameraDistance = 8000 * context.resources.displayMetrics.density
        column.animate().rotationX(0f).alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        return dialog
    }

    private fun item(context: Context, label: String, on: Boolean, box: Boolean = false) = TextView(context).apply {
        setPadding(0, PaneMenu.dp(context, 11f), 0, PaneMenu.dp(context, 11f))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        isClickable = true
        isFocusable = true
        background = context.getDrawable(android.R.drawable.list_selector_background)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        styleItem(context, this, label, on, box)
    }

    private fun styleItem(context: Context, view: TextView, label: String, on: Boolean, box: Boolean) {
        view.typeface = PaneFonts.regular
        // Multi-choice rows lead with a square tick box; single choices just take the accent.
        view.text = if (box) (if (on) "■  " else "□  ") + label else label
        view.setTextColor(if (on) PaneMenu.readable(MetroTheme.accent(context)) else Color.WHITE)
    }

    private fun button(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        typeface = PaneFonts.semibold
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = PaneMenu.dp(context, 96f)
        setPadding(PaneMenu.dp(context, 18f), PaneMenu.dp(context, 9f), PaneMenu.dp(context, 18f), PaneMenu.dp(context, 9f))
        background = GradientDrawable().apply { setStroke(PaneMenu.dp(context, 2f), Color.WHITE) }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun text(context: Context, value: String, sp: Float, color: Int, face: android.graphics.Typeface = PaneFonts.regular) =
        TextView(context).apply {
            text = value
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            setLineSpacing(0f, 1.12f)
        }
}
