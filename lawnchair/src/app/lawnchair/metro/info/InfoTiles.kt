package app.lawnchair.metro.info

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.lawnchair.metro.CrashLog
import app.lawnchair.preferences.PreferenceManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs

/** Tiles that show information from the phone rather than notifications. */
enum class InfoKind { CALENDAR, WEATHER, CLOCK, PHOTOS }

/** One calendar event occurrence. Times are local epoch millis (all-day events at local midnight). */
data class CalEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
    val color: Int,
)

data class WeatherHour(val time: Long, val temp: Int, val code: Int)
data class WeatherDay(val day: Long, val high: Int, val low: Int, val code: Int)

data class Weather(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val high: Int,
    val low: Int,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
    val fetchedAt: Long,
)

data class NextAlarm(val time: Long, val showIntent: PendingIntent?)

/**
 * Data for the live info tiles: your Google Calendar agenda, weather for your area, the next
 * alarm and recent camera photos. Each comes from the phone (calendar storage, alarm manager,
 * media store), except weather, which comes from Open-Meteo (free, no account).
 *
 * Start calls [start] while it's on screen; listeners hear about any change, plus a tick every
 * minute so countdowns ("in 25 min") and the date stay current.
 */
object InfoTiles {

    private val CALENDAR_PKGS = setOf("com.google.android.calendar", "com.samsung.android.calendar")
    private val WEATHER_PKGS = setOf("com.sec.android.daemonapp", "com.google.android.apps.weather")
    private val CLOCK_PKGS = setOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock")
    private val PHOTOS_PKGS = setOf("com.google.android.apps.photos")

    /** Apps you chose for each info tile (Live tile apps setting); missing = automatic. */
    @Volatile private var overrides: Map<InfoKind, String> = emptyMap()

    /** Reads the Live tile apps choices. Call before deciding tile kinds. */
    @JvmStatic
    fun loadOverrides(context: Context) {
        val p = PreferenceManager.getInstance(context)
        overrides = buildMap {
            p.metroAppCalendar.get().takeIf { it.isNotEmpty() }?.let { put(InfoKind.CALENDAR, it) }
            p.metroAppPhotos.get().takeIf { it.isNotEmpty() }?.let { put(InfoKind.PHOTOS, it) }
            p.metroAppWeather.get().takeIf { it.isNotEmpty() }?.let { put(InfoKind.WEATHER, it) }
            p.metroAppClock.get().takeIf { it.isNotEmpty() }?.let { put(InfoKind.CLOCK, it) }
        }
    }

    /** The preference holding the chosen app for [kind]. */
    fun appPref(context: Context, kind: InfoKind) = PreferenceManager.getInstance(context).let {
        when (kind) {
            InfoKind.CALENDAR -> it.metroAppCalendar
            InfoKind.PHOTOS -> it.metroAppPhotos
            InfoKind.WEATHER -> it.metroAppWeather
            InfoKind.CLOCK -> it.metroAppClock
        }
    }

    /**
     * Which info tile an app's tile becomes, if any: the app you chose for a kind, otherwise
     * the usual apps for it (unless you picked a different app for that kind).
     */
    @JvmStatic
    fun kindOf(pkg: String, label: CharSequence? = null): InfoKind? {
        overrides.entries.firstOrNull { it.value == pkg }?.let { return it.key }
        val auto = autoKindOf(pkg, label) ?: return null
        return if (overrides.containsKey(auto)) null else auto
    }

    private fun autoKindOf(pkg: String, label: CharSequence?): InfoKind? = when {
        pkg in CALENDAR_PKGS -> InfoKind.CALENDAR
        pkg in WEATHER_PKGS -> InfoKind.WEATHER
        pkg in CLOCK_PKGS -> InfoKind.CLOCK
        pkg in PHOTOS_PKGS -> InfoKind.PHOTOS
        label?.toString()?.trim()?.equals("weather", ignoreCase = true) == true -> InfoKind.WEATHER
        else -> null
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val locationExecutor = Executors.newSingleThreadExecutor()
    /** Weather waits on location and the network; kept off [worker] so calendar and photos don't queue. */
    private val weatherWorker = Executors.newSingleThreadExecutor()
    /** Photo scoring can take a few seconds the first time; it never delays the calendar. */
    private val photoWorker = Executors.newSingleThreadExecutor()
    private var calendarObserving = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var appContext: Context? = null
    private var started = false

    @Volatile var events: List<CalEvent> = emptyList()
        private set
    @Volatile var weather: Weather? = null
        private set
    @Volatile var alarm: NextAlarm? = null
        private set
    @Volatile var photos: List<Uri> = emptyList()
        private set

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The permission each kind needs (null = none). */
    fun permissionFor(kind: InfoKind): String? = when (kind) {
        InfoKind.CALENDAR -> Manifest.permission.READ_CALENDAR
        InfoKind.WEATHER -> Manifest.permission.ACCESS_COARSE_LOCATION
        InfoKind.PHOTOS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        InfoKind.CLOCK -> null
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    private val calendarObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshCalendar()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> refreshAlarm()
                Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> refreshAll()
                Intent.ACTION_TIME_TICK -> {
                    maybeRefreshWeather()
                    notifyChanged()
                }
            }
        }
    }

    /** Starts watching for changes (call when Start is shown). */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!started) {
            started = true
            val filter = IntentFilter().apply {
                addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIME_TICK)
            }
            runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
        }
        refreshAll()
    }

    fun stop() {
        val app = appContext ?: return
        if (!started) return
        started = false
        runCatching { app.unregisterReceiver(receiver) }
        runCatching { app.contentResolver.unregisterContentObserver(calendarObserver) }
        calendarObserving = false
    }

    /** Re-reads everything, e.g. after a permission was granted. */
    fun refreshAll() {
        refreshCalendar()
        refreshAlarm()
        refreshPhotos()
        maybeRefreshWeather(force = weather == null)
    }

    // ---- Calendar --------------------------------------------------------------------------

    fun refreshCalendar() {
        val app = appContext ?: return
        if (!hasPermission(app, Manifest.permission.READ_CALENDAR)) return
        if (started && !calendarObserving) {
            calendarObserving = runCatching {
                app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
            }.isSuccess
        }
        worker.execute {
            events = runCatching { queryEvents(app) }.getOrDefault(events)
            notifyChanged()
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** All-day events are stored at UTC midnight; move them to local midnight of the same date. */
    private fun utcDayToLocal(utc: Long): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
        return Calendar.getInstance().apply {
            clear()
            set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun queryEvents(context: Context): List<CalEvent> {
        val prefs = PreferenceManager.getInstance(context)
        val hidden = prefs.metroHiddenCalendars.get().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        val allDayOn = prefs.metroAllDayEvents.get()
        val from = startOfToday()
        val to = from + 8 * DAY
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from - DAY) // all-day events start at UTC midnight
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val out = ArrayList<CalEvent>()
        context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(7) in hidden) continue
                if (c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val allDay = c.getInt(4) == 1
                if (allDay && !allDayOn) continue
                val begin = if (allDay) utcDayToLocal(c.getLong(2)) else c.getLong(2)
                val end = if (allDay) utcDayToLocal(c.getLong(3)) else c.getLong(3)
                if (end <= System.currentTimeMillis() && !allDay) continue
                if (allDay && end <= from) continue
                out += CalEvent(
                    c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() } ?: "(no title)",
                    begin, end, allDay, c.getString(5)?.takeIf { it.isNotBlank() }, c.getInt(6),
                )
            }
        }
        // All-day events first within each day, then by start time.
        return out.sortedWith(compareBy<CalEvent>({ dayOf(it.begin.coerceAtLeast(from)) }, { !it.allDay }, { it.begin }))
    }

    fun dayOf(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Calendars on the phone, for the settings list: id, name, colour. */
    fun calendars(context: Context): List<Triple<Long, String, Int>> {
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) return emptyList()
        return runCatching {
            val out = ArrayList<Triple<Long, String, Int>>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_COLOR),
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null,
            )?.use { c -> while (c.moveToNext()) out += Triple(c.getLong(0), c.getString(1) ?: "Calendar", c.getInt(2)) }
            out
        }.getOrDefault(emptyList())
    }

    /** Opens one event in the calendar app. */
    fun openEvent(context: Context, e: CalEvent) {
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // ---- Alarm -------------------------------------------------------------------------------

    fun refreshAlarm() {
        val app = appContext ?: return
        val next = runCatching { app.getSystemService(AlarmManager::class.java)?.nextAlarmClock }.getOrNull()
        alarm = next?.let { NextAlarm(it.triggerTime, it.showIntent) }
        notifyChanged()
    }

    /** True when an alarm is set within the next day. */
    fun alarmSoon(): Boolean {
        val a = alarm ?: return false
        return a.time - System.currentTimeMillis() in 0..DAY
    }

    // ---- Photos ------------------------------------------------------------------------------

    /** Full photo access, or Android 14's "selected photos" access. */
    fun hasPhotoAccess(context: Context): Boolean =
        hasPermission(context, permissionFor(InfoKind.PHOTOS)!!) ||
            (Build.VERSION.SDK_INT >= 34 && hasPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))

    fun refreshPhotos() {
        val app = appContext ?: return
        if (!hasPhotoAccess(app)) return
        photoWorker.execute {
            photos = runCatching { queryPhotos(app) }.getOrDefault(photos)
            notifyChanged()
        }
    }

    /** Photos from [photos] that suit wide tiles (landscape-shaped). */
    @Volatile var landscapePhotos: Set<Uri> = emptySet()
        private set

    /**
     * Camera photos on the phone from the last 30 days that pass the quality filter (see
     * PhotoQuality): no blurry, dark, blown-out, document or duplicate shots. The best 40 by
     * score are kept, shown newest first.
     */
    private fun queryPhotos(context: Context): List<Uri> {
        val since = System.currentTimeMillis() - 30 * DAY
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val (selection, folder) = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to "DCIM/Camera%"
        } else {
            "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATA} LIKE ?" to "%/DCIM/Camera/%"
        }
        class Candidate(val uri: Uri, val taken: Long, val q: PhotoQuality.Result)
        val all = ArrayList<Candidate>()
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN), selection,
            arrayOf(since.toString(), folder), "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            var looked = 0
            while (c.moveToNext() && looked < 200) {
                looked++
                val id = c.getLong(0)
                val uri = ContentUris.withAppendedId(collection, id)
                val q = PhotoQuality.evaluate(context, id, uri) ?: continue
                all += Candidate(uri, c.getLong(1), q)
            }
        }
        // Moments: photos taken within ~10 minutes of each other. Each moment keeps its best
        // shot, plus a second only if it shows a clearly different scene.
        val kept = ArrayList<Candidate>()
        val byTime = all.sortedBy { it.taken }
        var i = 0
        while (i < byTime.size) {
            var j = i + 1
            while (j < byTime.size && byTime[j].taken - byTime[j - 1].taken < 10 * 60_000L) j++
            val moment = byTime.subList(i, j).sortedByDescending { it.q.score }
            val first = moment.first()
            kept += first
            moment.drop(1).firstOrNull { !PhotoQuality.sameScene(it.q, first.q) }?.let { kept += it }
            i = j
        }
        val best = kept.sortedByDescending { it.q.score }.take(40)
        // Varied order: each next photo is the one least like the one before.
        val ordered = ArrayList<Candidate>()
        val left = best.toMutableList()
        if (left.isNotEmpty()) ordered += left.removeAt(0)
        while (left.isNotEmpty()) {
            val prev = ordered.last()
            val next = left.maxByOrNull { PhotoQuality.difference(it.q, prev.q) + if (abs(it.taken - prev.taken) > 3_600_000L) 20 else 0 }!!
            left.remove(next)
            ordered += next
        }
        landscapePhotos = ordered.filter { it.q.landscape }.map { it.uri }.toSet()
        return ordered.map { it.uri }
    }

    // ---- Weather -----------------------------------------------------------------------------

    private var fetching = false

    private fun maybeRefreshWeather(force: Boolean = false) {
        val app = appContext ?: return
        val w = weather
        if (!force && w != null && System.currentTimeMillis() - w.fetchedAt < 45 * 60_000L) return
        if (fetching) return
        if (!hasPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (weather == null) loadCachedWeather(app)
            return
        }
        fetching = true
        weatherWorker.execute {
            val loc = lastLocation(app)
            val result = if (loc != null) {
                runCatching { fetchWeather(loc.latitude, loc.longitude) }
                    .onFailure { CrashLog.event("weather", "fetch failed", it) }
                    .getOrNull()
            } else {
                CrashLog.event("weather", "no location available")
                null
            }
            main.post {
                fetching = false
                if (result != null) {
                    weather = result
                    saveCachedWeather(app, result)
                    notifyChanged()
                } else if (weather == null) {
                    loadCachedWeather(app)
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun lastLocation(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val known = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (known != null) return known
        // Nothing cached: ask once for a fresh network fix (waits a few seconds at most).
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var fresh: Location? = null
        runCatching {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, locationExecutor) {
                fresh = it
                latch.countDown()
            }
        }.onFailure { return null }
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return fresh
    }

    private fun fetchWeather(lat: Double, lon: Double): Weather {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.US, lat, lon) +
                "&current=temperature_2m,weather_code,is_day" +
                "&hourly=temperature_2m,weather_code&forecast_hours=12" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=6" +
                "&timezone=auto&timeformat=unixtime",
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return parseWeather(JSONObject(body), System.currentTimeMillis())
    }

    private fun parseWeather(o: JSONObject, fetchedAt: Long): Weather {
        val cur = o.getJSONObject("current")
        val hourly = o.getJSONObject("hourly")
        val daily = o.getJSONObject("daily")
        val ht = hourly.getJSONArray("time")
        val htemp = hourly.getJSONArray("temperature_2m")
        val hcode = hourly.getJSONArray("weather_code")
        val hours = (0 until ht.length()).map { WeatherHour(ht.getLong(it) * 1000, Math.round(htemp.getDouble(it)).toInt(), hcode.getInt(it)) }
            .filter { it.time > System.currentTimeMillis() - 30 * 60_000L }
        val dt = daily.getJSONArray("time")
        val dmax = daily.getJSONArray("temperature_2m_max")
        val dmin = daily.getJSONArray("temperature_2m_min")
        val dcode = daily.getJSONArray("weather_code")
        val days = (0 until dt.length()).map {
            WeatherDay(dt.getLong(it) * 1000, Math.round(dmax.getDouble(it)).toInt(), Math.round(dmin.getDouble(it)).toInt(), dcode.getInt(it))
        }
        return Weather(
            Math.round(cur.getDouble("temperature_2m")).toInt(),
            cur.getInt("weather_code"),
            cur.optInt("is_day", 1) == 1,
            days.firstOrNull()?.high ?: 0,
            days.firstOrNull()?.low ?: 0,
            hours,
            days,
            fetchedAt,
        ).also { lastRaw = o.toString() }
    }

    private var lastRaw: String? = null

    private fun cache(context: Context) = context.getSharedPreferences("metro_weather", Context.MODE_PRIVATE)

    private fun saveCachedWeather(context: Context, w: Weather) {
        val raw = lastRaw ?: return
        cache(context).edit().putString("json", raw).putLong("at", w.fetchedAt).apply()
    }

    /** Last forecast we had, so the tile isn't blank offline (shown only if under 12 hours old). */
    private fun loadCachedWeather(context: Context) {
        weatherWorker.execute {
            val c = cache(context)
            val raw = c.getString("json", null) ?: return@execute
            val at = c.getLong("at", 0)
            if (System.currentTimeMillis() - at > 12 * 3_600_000L) return@execute
            val w = runCatching { parseWeather(JSONObject(raw), at) }.getOrNull() ?: return@execute
            main.post {
                if (weather == null) {
                    weather = w
                    notifyChanged()
                }
            }
        }
    }

    /** WMO weather code to a short lowercase description. */
    fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 66 -> "rain"
        65, 67 -> "heavy rain"
        71, 73, 75, 77 -> "snow"
        80, 81 -> "showers"
        82 -> "heavy showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "storm and hail"
        else -> ""
    }

    const val DAY = 86_400_000L
}
