package app.lawnchair.metro.start

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import app.lawnchair.metro.data.MetroTile
import app.lawnchair.metro.data.TileSize
import app.lawnchair.metro.theme.MetroTheme
import java.util.concurrent.Executors

/**
 * A single Start screen tile: coloured square, centred icon and bottom-left label.
 *
 * Icons prefer the app's monochrome (themed) layer drawn in white, which matches the flat
 * Windows Phone look; apps without one fall back to their normal full-colour icon.
 */
@SuppressLint("ViewConstructor")
class TileView(context: Context, var tile: MetroTile) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = sp(13f)
    }

    private var icon: Drawable? = null
    private var iconIsMonochrome = false
    var label: CharSequence = ""
        private set
    private var activityInfo: LauncherActivityInfo? = null

    /** Set by StartView in window mode: the tile is only a light tint over the wallpaper. */
    var windowMode = false

    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
        loadIconAsync()
    }

    fun setTileData(newTile: MetroTile) {
        val componentChanged = newTile.component != tile.component
        tile = newTile
        if (componentChanged) loadIconAsync()
        invalidate()
    }

    fun refreshColors() = invalidate()

    private fun loadIconAsync() {
        val target = tile.component
        ICON_EXECUTOR.execute {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            val info = runCatching {
                launcherApps?.getActivityList(target.packageName, Process.myUserHandle())
                    ?.firstOrNull { it.componentName == target }
                    ?: launcherApps?.getActivityList(target.packageName, Process.myUserHandle())?.firstOrNull()
            }.getOrNull()
            val density = resources.displayMetrics.densityDpi
            val full = runCatching { info?.getIcon(density) }.getOrNull()
            var mono: Drawable? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && full is AdaptiveIconDrawable) {
                mono = full.monochrome
            }
            val text = runCatching { info?.label }.getOrNull() ?: target.packageName
            MAIN.post {
                if (tile.component != target) return@post
                activityInfo = info
                iconIsMonochrome = mono != null
                icon = (mono ?: full)?.mutate()
                label = text
                contentDescription = text
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = MetroTheme.tileColor(context, tile.key, tile.color)
        val fill = if (windowMode) {
            ColorUtils.setAlphaComponent(base, if (MetroTheme.isTranslucent(context)) 0x55 else 0x00)
        } else {
            MetroTheme.tileFill(context, tile.key, tile.color)
        }
        if (Color.alpha(fill) > 0) {
            fillPaint.color = fill
            canvas.drawRect(0f, 0f, w, h, fillPaint)
        }

        val onColor = if (windowMode || MetroTheme.isTranslucent(context)) Color.WHITE else MetroTheme.onTileColor(base)

        // Icon: small and centred, nudged up on tiles that show a label.
        icon?.let { d ->
            val showsLabel = tile.size != TileSize.SMALL
            val shortSide = minOf(w, h)
            val scale = when {
                tile.size == TileSize.SMALL -> 0.56f
                iconIsMonochrome -> 0.52f
                else -> 0.40f
            }
            // Monochrome layers include adaptive-icon padding, so they're drawn larger.
            val size = (if (tile.size == TileSize.WIDE || tile.size == TileSize.LARGE) h * 0.5f else shortSide) * scale
            val cx = w / 2f
            val cy = h / 2f - if (showsLabel) h * 0.05f else 0f
            d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            d.colorFilter = if (iconIsMonochrome) PorterDuffColorFilter(onColor, PorterDuff.Mode.SRC_IN) else null
            d.draw(canvas)
        }

        // Label: bottom-left, hidden on small tiles (as on Windows Phone).
        if (tile.size != TileSize.SMALL && label.isNotEmpty()) {
            val pad = dp(8f)
            labelPaint.color = onColor
            val text = TextUtils.ellipsize(label, labelPaint, w - pad * 2, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, pad, h - pad - labelPaint.descent(), labelPaint)
        }
    }

    // Press feedback: the tile sinks slightly, like the WP tilt effect (full 3D tilt comes later).
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private val ICON_EXECUTOR = Executors.newSingleThreadExecutor()
        private val MAIN = Handler(Looper.getMainLooper())
    }
}
