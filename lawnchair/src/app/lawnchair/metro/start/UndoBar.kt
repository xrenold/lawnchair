package app.lawnchair.metro.start

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.lawnchair.metro.theme.MetroTheme

/**
 * A slim bar at the bottom of Start after auto layout: what changed, UNDO, and (without Usage
 * access) a link to grant it. Hides itself after a few seconds.
 */
class UndoBar(context: Context) : LinearLayout(context) {

    private val message = TextView(context)
    private val hint = TextView(context)
    private val undo = TextView(context)
    private val hide = Runnable { dismiss() }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xF0202020.toInt())
        val pad = dp(16f).toInt()
        setPadding(pad, dp(12f).toInt(), pad, dp(12f).toInt())

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        message.setTextColor(Color.WHITE)
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        message.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        row.addView(message, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        undo.text = "UNDO"
        undo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        undo.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        undo.setPadding(dp(12f).toInt(), dp(6f).toInt(), 0, dp(6f).toInt())
        row.addView(undo)
        addView(row)

        hint.setTextColor(0xB3FFFFFF.toInt())
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        hint.setPadding(0, dp(6f).toInt(), 0, 0)
        addView(hint)
    }

    fun show(parent: FrameLayout, message: String, hint: String?, onUndo: () -> Unit, onHint: () -> Unit) {
        if (this.parent == null) {
            parent.addView(
                this,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
            )
        }
        val insets = rootWindowInsets
        @Suppress("DEPRECATION")
        val bottom = insets?.systemWindowInsetBottom ?: 0
        (layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom
        this.message.text = message
        this.hint.text = hint ?: ""
        this.hint.visibility = if (hint == null) GONE else VISIBLE
        this.hint.setOnClickListener {
            onHint()
            dismiss()
        }
        undo.setTextColor(MetroTheme.accent(context))
        undo.setOnClickListener {
            onUndo()
            dismiss()
        }
        visibility = VISIBLE
        translationY = dp(120f)
        animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        removeCallbacks(hide)
        postDelayed(hide, if (hint == null) 6000 else 9000)
    }

    fun dismiss() {
        removeCallbacks(hide)
        animate().translationY(height.toFloat() + dp(40f)).setDuration(200)
            .withEndAction { visibility = GONE }.start()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
