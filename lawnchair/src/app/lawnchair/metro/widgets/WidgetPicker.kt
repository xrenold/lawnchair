package app.lawnchair.metro.widgets

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Collator
import java.util.concurrent.Executors

/** A simple list of every widget on the phone, grouped by app, with previews. */
object WidgetPicker {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun show(activity: Activity, onPick: (AppWidgetProviderInfo) -> Unit) {
        val pm = activity.packageManager
        val collator = Collator.getInstance()
        val items = MetroWidgets.providers(activity)
            .map { info ->
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
                    .getOrDefault(info.provider.packageName)
                Triple(info, app, info.loadLabel(pm) ?: "")
            }
            .sortedWith { a, b -> collator.compare(a.second, b.second).takeIf { it != 0 } ?: collator.compare(a.third, b.third) }

        val previews = HashMap<Int, Drawable?>()
        lateinit var dialog: AlertDialog
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt() }

        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                    addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(72f), dp(56f)))
                    addView(
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(14f), 0, 0, 0)
                            addView(TextView(activity).apply { typeface = app.lawnchair.metro.theme.PaneFonts.regular
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                setTextColor(Color.WHITE)
                            })
                            addView(TextView(activity).apply { typeface = app.lawnchair.metro.theme.PaneFonts.regular
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                setTextColor(0xA6FFFFFF.toInt())
                                typeface = app.lawnchair.metro.theme.PaneFonts.light
                            })
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
                val (info, app, label) = items[position]
                val image = row.getChildAt(0) as ImageView
                val texts = row.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = label
                (texts.getChildAt(1) as TextView).text = app
                image.tag = position
                if (previews.containsKey(position)) {
                    image.setImageDrawable(previews[position])
                } else {
                    image.setImageDrawable(null)
                    executor.execute {
                        val d = runCatching {
                            info.loadPreviewImage(activity, activity.resources.displayMetrics.densityDpi)
                                ?: info.loadIcon(activity, activity.resources.displayMetrics.densityDpi)
                        }.getOrNull()
                        main.post {
                            previews[position] = d
                            if (image.tag == position) image.setImageDrawable(d)
                        }
                    }
                }
                return row
            }
        }

        val list = ListView(activity).apply {
            this.adapter = adapter
            divider = null
            setOnItemClickListener { _, _, position, _ ->
                dialog.dismiss()
                onPick(items[position].first)
            }
        }
        dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add a widget")
            .setView(list)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }
}
