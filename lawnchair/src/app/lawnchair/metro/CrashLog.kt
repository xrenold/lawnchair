package app.lawnchair.metro

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports, kept on the phone only. When Metro crashes, a short report (time, versions,
 * device and the stack trace, plus recent noteworthy events) is written before Android closes
 * it. Nothing is ever sent anywhere by itself: reports are listed in Metro settings and can be
 * shared from there, and Start offers to share one after a crash.
 *
 * [event] records "something went wrong but Metro carried on" moments (a widget failing to
 * load, a weather fetch failing), so slow-burning problems show up in reports too.
 */
object CrashLog {

    private const val MAX_REPORTS = 10
    private const val MAX_EVENTS = 200
    private var appContext: Context? = null
    private val events = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun dir(context: Context) = File(context.filesDir, "metro_crashes").apply { mkdirs() }

    /** Installs the crash handler (call once, early, from the application). */
    @JvmStatic
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Notes a non-fatal problem; included in the next report. */
    @JvmStatic
    fun event(tag: String, message: String, error: Throwable? = null) {
        val line = "${stamp.format(Date())} [$tag] $message" + (error?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        synchronized(events) {
            events.addLast(line)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
    }.getOrDefault("?")

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val recent = synchronized(events) { events.toList().takeLast(40) }
        val text = buildString {
            appendLine("Metro crash report")
            appendLine("Time: ${stamp.format(Date())}")
            appendLine("Metro: ${versionName(context)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(trace)
            if (recent.isNotEmpty()) {
                appendLine("Recent events:")
                recent.forEach { appendLine(it) }
            }
        }
        val d = dir(context)
        File(d, "crash_${System.currentTimeMillis()}.txt").writeText(text)
        // Keep the last few only.
        d.listFiles { f -> f.name.startsWith("crash_") }.orEmpty().sortedByDescending { it.name }
            .drop(MAX_REPORTS).forEach { it.delete() }
        context.getSharedPreferences("metro_crash", Context.MODE_PRIVATE).edit().putBoolean("unseen", true).commit()
    }

    /** Saved reports, newest first: (file, time label, first line of the error). */
    fun reports(context: Context): List<Triple<File, String, String>> =
        dir(context).listFiles { f -> f.name.startsWith("crash_") }.orEmpty().sortedByDescending { it.name }.map { f ->
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            val time = lines.firstOrNull { it.startsWith("Time: ") }?.removePrefix("Time: ") ?: f.name
            val headline = lines.dropWhile { !it.startsWith("Thread: ") }.drop(2).firstOrNull()?.take(120) ?: ""
            Triple(f, time, headline)
        }

    /** True once after a crash, so Start can offer to share the report. */
    fun takeUnseen(context: Context): Boolean {
        val p = context.getSharedPreferences("metro_crash", Context.MODE_PRIVATE)
        val unseen = p.getBoolean("unseen", false)
        if (unseen) p.edit().putBoolean("unseen", false).apply()
        return unseen
    }

    /** Opens the share sheet with a report's text (WhatsApp, email, copy…). */
    fun share(context: Context, report: File) {
        val text = runCatching { report.readText() }.getOrDefault("")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Metro crash report")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
