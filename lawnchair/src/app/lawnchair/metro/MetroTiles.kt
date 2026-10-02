package app.lawnchair.metro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import app.lawnchair.preferences.PreferenceManager

/**
 * Windows Phone ("Metro") style live tiles.
 *
 * Draws the square tile behind home screen icons and widgets, and the small
 * bottom-left label used on Metro tiles. All sizes are in dp and converted here
 * so callers only pass a view and a canvas.
 */
object MetroTiles {

    /** Gap between neighbouring tiles is twice this inset. */
    private const val TILE_INSET_DP = 3f
    private const val LABEL_PADDING_DP = 7f
    private const val LABEL_TEXT_SP = 12f
    private const val FROSTED_BORDER_DP = 1f

    /** Windows Phone cobalt, used before Android 12 where Material You colours don't exist. */
    private const val FALLBACK_ACCENT = 0xFF1BA1E2.toInt()
    private const val FROSTED_FILL = 0x33FFFFFF
    private const val FROSTED_BORDER = 0x55FFFFFF

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tileRect = RectF()

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getInstance(context).metroTiles.get()

    @JvmStatic
    fun isFrosted(context: Context): Boolean =
        PreferenceManager.getInstance(context).metroFrostedTiles.get()

    @JvmStatic
    fun useBlackBackground(context: Context): Boolean =
        PreferenceManager.getInstance(context).metroBlackBackground.get()

    /** Solid tile colour: the Material You accent on Android 12+, Metro cobalt before that. */
    @JvmStatic
    fun accentColor(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getColor(android.R.color.system_accent1_600)
        } else {
            FALLBACK_ACCENT
        }

    /** The tile's rectangle inside [view], leaving half a gutter on each side. */
    @JvmStatic
    fun getTileRect(view: View, out: RectF) {
        val inset = dp(view.context, TILE_INSET_DP)
        out.set(
            view.scrollX + inset,
            view.scrollY + inset,
            view.scrollX + view.width - inset,
            view.scrollY + view.height - inset,
        )
    }

    /** Draws the tile background (solid accent or frosted glass) filling [view]. */
    @JvmStatic
    fun drawBackground(view: View, canvas: Canvas) {
        val context = view.context
        getTileRect(view, tileRect)
        if (tileRect.width() <= 0f || tileRect.height() <= 0f) return

        if (isFrosted(context)) {
            fillPaint.color = FROSTED_FILL
            canvas.drawRect(tileRect, fillPaint)
            val stroke = dp(context, FROSTED_BORDER_DP)
            borderPaint.strokeWidth = stroke
            borderPaint.color = FROSTED_BORDER
            val half = stroke / 2f
            canvas.drawRect(
                tileRect.left + half,
                tileRect.top + half,
                tileRect.right - half,
                tileRect.bottom - half,
                borderPaint,
            )
        } else {
            fillPaint.color = accentColor(context)
            canvas.drawRect(tileRect, fillPaint)
        }
    }

    /**
     * Draws [label] in the bottom-left corner of the tile, ellipsized to fit.
     * [alpha] (0-255) lets the launcher fade the label, e.g. while dragging.
     */
    @JvmStatic
    fun drawLabel(view: View, canvas: Canvas, label: CharSequence?, alpha: Int) {
        if (label.isNullOrEmpty() || alpha <= 0) return
        val context = view.context
        getTileRect(view, tileRect)
        val padding = dp(context, LABEL_PADDING_DP)
        val available = tileRect.width() - padding * 2
        if (available <= 0f) return

        labelPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            LABEL_TEXT_SP,
            context.resources.displayMetrics,
        )
        labelPaint.color = Color.WHITE
        labelPaint.alpha = alpha.coerceIn(0, 255)

        val text = TextUtils.ellipsize(label, labelPaint, available, TextUtils.TruncateAt.END)
        val baseline = tileRect.bottom - padding - labelPaint.descent()
        canvas.drawText(text, 0, text.length, tileRect.left + padding, baseline, labelPaint)
    }

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
}
