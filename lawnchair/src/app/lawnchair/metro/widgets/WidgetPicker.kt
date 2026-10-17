package app.lawnchair.metro.widgets

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.lawnchair.metro.theme.PaneFonts
import app.lawnchair.metro.ui.PaneDialog
import java.util.concurrent.Executors

/**
 * Pane's list of widgets, with previews: one app's (to show on its tile, or pin), or every app's
 * (Start's "add widget"), sorted by app with the app's name under each widget.
 */
object WidgetPicker {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun show(activity: Activity, items: List<AppWidgetProviderInfo>, onPick: (AppWidgetProviderInfo) -> Unit) {
        val pm = activity.packageManager
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt() }
        lateinit var dialog: Dialog
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val manyApps = items.map { it.provider.packageName }.distinct().size > 1
        fun appName(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val names = HashMap<String, String>()
        val collator = java.text.Collator.getInstance()
        val sorted = if (manyApps) {
            items.sortedWith { a, b ->
                val an = names.getOrPut(a.provider.packageName) { appName(a.provider.packageName) }
                val bn = names.getOrPut(b.provider.packageName) { appName(b.provider.packageName) }
                collator.compare(an, bn).takeIf { it != 0 } ?: collator.compare(a.loadLabel(pm) ?: "", b.loadLabel(pm) ?: "")
            }
        } else {
            items
        }
        val thumbW = dp(96f)
        val thumbH = dp(72f)
        sorted.forEach { info ->
            val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10f), 0, dp(10f))
                isClickable = true
                isFocusable = true
                background = activity.getDrawable(android.R.drawable.list_selector_background)
                addView(image, LinearLayout.LayoutParams(dp(96f), dp(72f)))
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16f), 0, 0, 0)
                        addView(TextView(activity).apply {
                            typeface = PaneFonts.regular
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                            setTextColor(Color.WHITE)
                            text = (info.loadLabel(pm) ?: "").lowercase()
                        })
                        addView(TextView(activity).apply {
                            typeface = PaneFonts.light
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            setTextColor(0xA6FFFFFF.toInt())
                            val size = MetroWidgets.sizeFor(activity, info).label.lowercase() + " tile or bigger"
                            text = if (manyApps) "${names[info.provider.packageName]?.lowercase()} · $size" else size
                        })
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                setOnClickListener {
                    dialog.dismiss()
                    onPick(info)
                }
            }
            list.addView(row)
            executor.execute {
                // Previews are drawn small once: a full list of every app's widgets would
                // otherwise hold dozens of large pictures.
                val bmp = runCatching {
                    val d = info.loadPreviewImage(activity, activity.resources.displayMetrics.densityDpi)
                        ?: info.loadIcon(activity, activity.resources.displayMetrics.densityDpi)
                        ?: return@runCatching null
                    val iw = d.intrinsicWidth.takeIf { it > 0 } ?: thumbW
                    val ih = d.intrinsicHeight.takeIf { it > 0 } ?: thumbH
                    val scale = minOf(thumbW.toFloat() / iw, thumbH.toFloat() / ih, 1f)
                    val w = (iw * scale).toInt().coerceAtLeast(1)
                    val h = (ih * scale).toInt().coerceAtLeast(1)
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                        d.setBounds(0, 0, w, h)
                        d.draw(android.graphics.Canvas(b))
                    }
                }.getOrNull()
                main.post { image.setImageBitmap(bmp) }
            }
        }
        dialog = PaneDialog.custom(activity, "choose a widget", list, buttons = listOf("cancel" to {}))
    }
}
