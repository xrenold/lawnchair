package app.lawnchair.metro.info

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import app.lawnchair.metro.data.TileSize
import java.util.Calendar
import java.util.Locale

/**
 * Draws the faces of the info tiles (calendar, weather, next alarm) in the Windows Phone style:
 * big thin numbers, lowercase words, and the app's name in the bottom-left like every tile.
 * Photos are drawn by [PhotoSlideshow].
 *
 * [k] scales type and spacing with the tile's cell size (1.0 on 4 columns, ~0.7 on 6).
 */
class InfoPainter(private val context: Context) {

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = light }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Event rectangles on wide/large calendar tiles, for tapping an event. */
    val eventHits = ArrayList<Pair<RectF, CalEvent>>()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    private fun line(canvas: Canvas, s: CharSequence, x: Float, baseline: Float, maxW: Float, p: TextPaint) {
        val t = TextUtils.ellipsize(s, p, maxW.coerceAtLeast(1f), TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, x, baseline, p)
    }

    private fun dim(c: Int) = ColorUtils.setAlphaComponent(c, 0xB8)

    // ---- Calendar ----------------------------------------------------------------------------

    private fun now() = System.currentTimeMillis()
    private fun today() = InfoTiles.dayOf(now())

    /** Events still to come today (including all-day ones), in order. */
    fun todayEvents(): List<CalEvent> {
        val t0 = today()
        val t1 = t0 + InfoTiles.DAY
        return InfoTiles.events.filter { it.begin < t1 && it.end > now().coerceAtLeast(if (it.allDay) t0 else 0) }
    }

    /** The next timed event (or an all-day one today), for the medium tile's content side. */
    fun nextEvent(): CalEvent? = InfoTiles.events.firstOrNull { !it.allDay && it.end > now() } ?: todayEvents().firstOrNull()

    private fun timeOf(t: Long): String = DateFormat.getTimeFormat(context).format(java.util.Date(t))

    /** "now", "in 25 min", "10:30", "all day", or "tomorrow 9:00" for later days. */
    fun whenText(e: CalEvent, withDay: Boolean = false): String {
        val n = now()
        if (e.allDay) return "all day"
        if (n >= e.begin && n < e.end) return "now · until ${timeOf(e.end)}"
        val mins = (e.begin - n) / 60_000L
        if (mins in 0..59) return "in ${mins.coerceAtLeast(1)} min"
        val dayDiff = ((InfoTiles.dayOf(e.begin) - today()) / InfoTiles.DAY).toInt()
        val time = timeOf(e.begin)
        return when {
            !withDay || dayDiff == 0 -> time
            dayDiff == 1 -> "tomorrow $time"
            else -> dayName(e.begin, short = true) + " " + time
        }
    }

    fun dayName(t: Long, short: Boolean = false): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return (c.getDisplayName(Calendar.DAY_OF_WEEK, if (short) Calendar.SHORT else Calendar.LONG, Locale.getDefault()) ?: "").lowercase()
    }

    private fun dayNumber(): String = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString()

    /** Calendar front: the date, WP style. */
    fun drawCalendarFront(canvas: Canvas, w: Float, h: Float, size: TileSize, k: Float, on: Int) {
        eventHits.clear()
        val pad = dp(10f) * k
        when (size) {
            TileSize.SMALL -> {
                big.color = on
                big.textSize = h * 0.44f
                val num = dayNumber()
                val nw = big.measureText(num)
                text.color = on
                text.textSize = maxOf(sp(10f) * k, sp(9f))
                val day = dayName(now(), short = true)
                val total = big.textSize * 0.78f + text.textSize + dp(2f)
                val top = (h - total) / 2f
                canvas.drawText(num, (w - nw) / 2f, top + big.textSize * 0.78f, big)
                canvas.drawText(day, (w - text.measureText(day)) / 2f, top + total, text)
            }
            TileSize.MEDIUM -> {
                big.color = on
                big.textSize = h * 0.40f
                canvas.drawText(dayNumber(), pad - dp(2f), pad + big.textSize * 0.80f, big)
                text.color = on
                text.textSize = maxOf(sp(15f) * k, sp(11f))
                canvas.drawText(dayName(now()), pad, pad + big.textSize * 0.80f + text.textSize + dp(6f) * k, text)
            }
            TileSize.WIDE -> drawCalendarWide(canvas, w, h, k, on)
            TileSize.LARGE -> drawCalendarLarge(canvas, w, h, k, on)
        }
    }

    /** Medium content side: the next event. Returns false if there is none. */
    fun drawCalendarBack(canvas: Canvas, w: Float, h: Float, k: Float, on: Int, footerTop: Float): Boolean {
        val e = nextEvent() ?: return false
        val pad = dp(10f) * k
        big.color = on
        big.textSize = maxOf(sp(17f) * k, sp(13f))
        val title = android.text.StaticLayout.Builder.obtain(e.title, 0, e.title.length, TextPaint(big), (w - pad * 2).toInt().coerceAtLeast(1))
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        canvas.save()
        canvas.translate(pad, pad)
        title.draw(canvas)
        canvas.restore()
        var y = pad + title.height + dp(4f) * k
        text.textSize = maxOf(sp(13f) * k, sp(11f))
        text.color = on
        y += text.textSize
        if (y < footerTop) line(canvas, whenText(e, withDay = true), pad, y, w - pad * 2, text)
        e.location?.let {
            text.color = dim(on)
            y += text.textSize + dp(3f) * k
            if (y < footerTop) line(canvas, it, pad, y, w - pad * 2, text)
        }
        return true
    }

    private fun eventRows(canvas: Canvas, list: List<CalEvent>, x: Float, top: Float, right: Float, bottom: Float, k: Float, on: Int, withDay: Boolean): Float {
        var y = top
        val rowGap = dp(8f) * k
        val titleSize = maxOf(sp(14f) * k, sp(11.5f))
        val timeSize = maxOf(sp(12f) * k, sp(10f))
        for (e in list) {
            val rowH = titleSize + timeSize + dp(3f) * k
            if (y + rowH > bottom) break
            // A thin bar in the event's calendar colour.
            bar.color = if (e.color != 0) ColorUtils.setAlphaComponent(e.color, 255) else on
            canvas.drawRect(x, y + dp(2f), x + dp(3f) * k, y + rowH - dp(1f), bar)
            val tx = x + dp(9f) * k
            text.color = on
            text.textSize = titleSize
            line(canvas, e.title, tx, y + titleSize * 0.92f, right - tx, text)
            text.textSize = timeSize
            text.color = dim(on)
            line(canvas, whenText(e, withDay), tx, y + titleSize + dp(3f) * k + timeSize * 0.92f, right - tx, text)
            eventHits += RectF(x, y - rowGap / 2, right, y + rowH + rowGap / 2) to e
            y += rowH + rowGap
        }
        return y
    }

    private fun drawCalendarWide(canvas: Canvas, w: Float, h: Float, k: Float, on: Int) {
        val pad = dp(10f) * k
        // Date block on the left.
        big.color = on
        big.textSize = h * 0.40f
        canvas.drawText(dayNumber(), pad - dp(2f), pad + big.textSize * 0.80f, big)
        text.color = on
        text.textSize = maxOf(sp(14f) * k, sp(11f))
        canvas.drawText(dayName(now()), pad, pad + big.textSize * 0.80f + text.textSize + dp(6f) * k, text)
        val left = w * 0.36f
        val today = todayEvents()
        if (today.isNotEmpty()) {
            eventRows(canvas, today.take(3), left, pad, w - pad, h - pad, k, on, withDay = false)
        } else {
            // Nothing left today: straight to what's next.
            val next = InfoTiles.events.filter { InfoTiles.dayOf(it.begin) > today() }.take(3)
            if (next.isNotEmpty()) eventRows(canvas, next, left, pad, w - pad, h - pad, k, on, withDay = true)
        }
    }

    private fun drawCalendarLarge(canvas: Canvas, w: Float, h: Float, k: Float, on: Int) {
        val pad = dp(12f) * k
        big.color = on
        big.textSize = maxOf(sp(26f) * k, sp(18f))
        canvas.drawText(dayName(now()) + " " + dayNumber(), pad, pad + big.textSize * 0.85f, big)
        var y = pad + big.textSize + dp(12f) * k
        val bottom = h - pad - maxOf(sp(13f) * k, sp(11f)) - dp(8f) * k // leave room for the label
        val t1 = today() + InfoTiles.DAY
        val today = todayEvents()
        val tomorrow = InfoTiles.events.filter { InfoTiles.dayOf(it.begin) == t1 || (it.allDay && it.begin <= t1 && it.end > t1) }
        if (today.isNotEmpty()) {
            y = eventRows(canvas, today.take(5), pad, y, w - pad, bottom, k, on, withDay = false)
        }
        val shown = eventHits.size
        if (tomorrow.isNotEmpty() && shown < 5 && y < bottom - dp(30f) * k) {
            text.color = dim(on)
            text.textSize = maxOf(sp(12f) * k, sp(10f))
            canvas.drawText("tomorrow", pad, y + text.textSize, text)
            y += text.textSize + dp(8f) * k
            eventRows(canvas, tomorrow.take(5 - shown), pad, y, w - pad, bottom, k, on, withDay = false)
        }
    }

    // ---- Weather ---------------------------------------------------------------------------------

    /** Returns false when there's no forecast yet (the tile then shows its normal icon). */
    fun drawWeather(canvas: Canvas, w: Float, h: Float, size: TileSize, k: Float, on: Int): Boolean {
        val wx = InfoTiles.weather ?: return false
        val pad = dp(10f) * k
        big.color = on
        text.color = on
        val temp = "${wx.temp}°"
        if (size == TileSize.SMALL) {
            big.textSize = h * 0.42f
            canvas.drawText(temp, (w - big.measureText(temp)) / 2f + big.textSize * 0.08f, (h + big.textSize * 0.7f) / 2f, big)
            return true
        }
        val cell = h / size.rowSpan * 2f // height of a medium tile
        big.textSize = cell * 0.36f
        canvas.drawText(temp, pad - dp(2f), pad + big.textSize * 0.82f, big)
        var y = pad + big.textSize * 0.82f + dp(4f) * k
        text.textSize = maxOf(sp(13f) * k, sp(10.5f))
        y += text.textSize
        val leftW = if (size == TileSize.MEDIUM) w - pad * 2 else w * 0.42f - pad
        line(canvas, InfoTiles.describe(wx.code), pad, y, leftW, text)
        text.color = dim(on)
        y += text.textSize + dp(2f) * k
        line(canvas, "${wx.high}° / ${wx.low}°", pad, y, leftW, text)

        if (size == TileSize.WIDE || size == TileSize.LARGE) {
            // Next few hours, in columns on the right.
            val hours = wx.hours.take(4)
            val left = w * 0.44f
            val colW = (w - pad - left) / hours.size.coerceAtLeast(1)
            hours.forEachIndexed { i, hr ->
                val cx = left + colW * i
                text.color = dim(on)
                text.textSize = maxOf(sp(11f) * k, sp(9.5f))
                val label = if (i == 0 && hr.time <= now()) "now" else hourLabel(hr.time)
                canvas.drawText(label, cx, pad + text.textSize, text)
                big.textSize = maxOf(sp(20f) * k, sp(15f))
                canvas.drawText("${hr.temp}°", cx, pad + text.textSize + dp(6f) * k + big.textSize * 0.85f, big)
            }
        }
        if (size == TileSize.LARGE) {
            // The next days, one per row.
            var ry = cell + dp(6f) * k
            val rowH = maxOf(sp(14f) * k, sp(11f)) + dp(10f) * k
            val bottom = h - pad - maxOf(sp(13f) * k, sp(11f)) - dp(8f) * k
            for (d in wx.days.drop(1)) {
                if (ry + rowH > bottom) break
                text.textSize = maxOf(sp(14f) * k, sp(11f))
                text.color = on
                canvas.drawText(dayName(d.day, short = true), pad, ry + text.textSize, text)
                text.color = dim(on)
                line(canvas, InfoTiles.describe(d.code), pad + w * 0.18f, ry + text.textSize, w * 0.45f, text)
                val hl = "${d.high}° / ${d.low}°"
                text.color = on
                canvas.drawText(hl, w - pad - text.measureText(hl), ry + text.textSize, text)
                ry += rowH
            }
        }
        return true
    }

    private fun hourLabel(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return if (DateFormat.is24HourFormat(context)) {
            "%02d".format(c.get(Calendar.HOUR_OF_DAY))
        } else {
            val hr = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            "$hr " + (if (c.get(Calendar.AM_PM) == Calendar.AM) "am" else "pm")
        }
    }

    // ---- Next alarm ------------------------------------------------------------------------------

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringRect = RectF()

    /**
     * The alarm glyph inside a ring that fills up as the alarm gets closer: empty a day ahead,
     * half full 12 hours before, nearly full in the last hour.
     */
    private fun drawAlarmRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, k: Float, on: Int, until: Long, glyph: (Canvas, Float, Float, Float) -> Unit) {
        // Just the clock glyph (the progress ring was dropped); it marks the tile as an alarm.
        val g = radius * 1.5f
        glyph(canvas, cx - g / 2f, cy - g / 2f, g)
    }

    /** "in 7 h 20 min", "in 45 min". */
    private fun untilText(until: Long): String {
        val mins = (until / 60_000L).coerceAtLeast(1)
        val h = mins / 60
        val m = mins % 60
        return when {
            h == 0L -> "in $m min"
            m == 0L -> "in $h h"
            else -> "in $h h $m min"
        }
    }

    /**
     * Next alarm: the time with the clock glyph in its filling ring. [glyph] draws the app's
     * glyph into a square (left, top, size). Returns false when no alarm is set (normal icon).
     */
    fun drawAlarm(canvas: Canvas, w: Float, h: Float, size: TileSize, k: Float, on: Int, glyph: (Canvas, Float, Float, Float) -> Unit): Boolean {
        val a = InfoTiles.alarm ?: return false
        val until = a.time - now()
        if (until < 0) return false
        val time = timeOf(a.time)
        big.color = on
        if (size == TileSize.SMALL) {
            // Ring and glyph on top, the time below.
            val r = minOf(w, h) * 0.2f
            drawAlarmRing(canvas, w / 2f, h * 0.36f, r, k, on, until, glyph)
            big.textSize = h * 0.2f
            while (big.measureText(time) > w * 0.86f && big.textSize > 6f) big.textSize *= 0.92f
            canvas.drawText(time, (w - big.measureText(time)) / 2f, h * 0.84f, big)
            return true
        }
        val pad = dp(10f) * k
        val cell = h / size.rowSpan * 2f
        val r = cell * 0.13f
        drawAlarmRing(canvas, w - pad - r - dp(2f), pad + r + dp(2f), r, k, on, until, glyph)
        big.textSize = cell * 0.30f
        while (big.measureText(time) > w - pad * 3 - r * 2 && big.textSize > 6f) big.textSize *= 0.92f
        canvas.drawText(time, pad - dp(1f), pad + big.textSize * 0.85f, big)
        text.color = on
        text.textSize = maxOf(sp(13f) * k, sp(10.5f))
        val dayDiff = ((InfoTiles.dayOf(a.time) - today()) / InfoTiles.DAY).toInt()
        val whenLabel = when (dayDiff) {
            0 -> "today"
            1 -> "tomorrow"
            else -> dayName(a.time)
        }
        line(canvas, "$whenLabel · ${untilText(until)}", pad, pad + big.textSize * 0.85f + text.textSize + dp(6f) * k, w - pad * 2, text)
        return true
    }
}
