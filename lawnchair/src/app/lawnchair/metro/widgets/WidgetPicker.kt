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

/** Pane's list of an app's widgets, with previews: pick one to show it as the app's tile. */
object WidgetPicker {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun show(activity: Activity, items: List<AppWidgetProviderInfo>, onPick: (AppWidgetProviderInfo) -> Unit) {
        val pm = activity.packageManager
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt() }
        lateinit var dialog: Dialog
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        items.forEach { info ->
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
                            text = MetroWidgets.sizeFor(activity, info).label.lowercase() + " tile or bigger"
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
                val d = runCatching {
                    info.loadPreviewImage(activity, activity.resources.displayMetrics.densityDpi)
                        ?: info.loadIcon(activity, activity.resources.displayMetrics.densityDpi)
                }.getOrNull()
                main.post { image.setImageDrawable(d) }
            }
        }
        dialog = PaneDialog.custom(activity, "choose a widget", list, buttons = listOf("cancel" to {}))
    }
}
