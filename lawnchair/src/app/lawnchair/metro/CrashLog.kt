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

    /** SimpleDateFormat isn't thread-safe; events come from several threads. */
    private fun now(): String = synchronized(stamp) { stamp.format(Date()) }

    private fun dir(context: Context) = File(context.filesDir, "metro_crashes").apply { mkdirs() }

    /** Installs the crash handler (call once, early, from the application). */
    @JvmStatic
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        // The last run's events, kept for a report on how it ended (see recordExitReasons).
        runCatching {
            val log = File(dir(app), "events.log")
            val prev = File(dir(app), "events_prev.log")
            if (log.exists()) {
                prev.delete()
                log.renameTo(prev)
            }
        }
        ensureHandler()
        event("app", "started")
        // Ends Android caused that no Java handler sees: a freeze (ANR), a native crash, being
        // killed for memory. Android keeps a record; it's read at the next start.
        Thread { runCatching { recordExitReasons(app) } }.start()
    }

    private var handler: Thread.UncaughtExceptionHandler? = null

    /**
     * Installs the crash handler, or installs it again if something set its own after (code
     * that replaces the handler without passing errors on would hide every crash).
     */
    @JvmStatic
    fun ensureHandler() {
        val app = appContext ?: return
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current != null && current === handler) return
        val h = Thread.UncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            current?.uncaughtException(thread, error)
        }
        handler = h
        Thread.setDefaultUncaughtExceptionHandler(h)
    }

    /** Android's record of how Pane last ended, written as reports for the ends that matter. */
    private fun recordExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
        val p = context.getSharedPreferences("metro_crash", Context.MODE_PRIVATE)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        if (exits.isEmpty()) return
        val newest = exits.maxOf { it.timestamp }
        if (!p.contains("lastExit")) {
            // First time: only what happens from now on counts.
            p.edit().putLong("lastExit", newest).apply()
            return
        }
        val last = p.getLong("lastExit", 0L)
        p.edit().putLong("lastExit", maxOf(last, newest)).apply()
        val ours = dir(context).listFiles { f -> f.name.startsWith("crash_") }.orEmpty()
            .mapNotNull { it.name.removePrefix("crash_").removeSuffix(".txt").toLongOrNull() }
        for (e in exits.filter { it.timestamp > last }.sortedBy { it.timestamp }) {
            val reason = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> "froze and was closed by Android (not responding)"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed in native code"
                android.app.ApplicationExitInfo.REASON_CRASH -> {
                    // Java crashes are already reported by the handler; only add one it missed.
                    if (ours.any { kotlin.math.abs(it - e.timestamp) < 10_000L }) continue
                    "crashed"
                }
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "was closed by Android to free memory"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "was closed for using too much power or memory"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                // Ordinary ends: Pane updated, closed from settings or Recents, or restarting itself.
                android.app.ApplicationExitInfo.REASON_EXIT_SELF,
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED,
                android.app.ApplicationExitInfo.REASON_USER_STOPPED,
                android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED,
                android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE,
                -> continue
                // Anything else (killed by a signal, "other": phone makers' own memory killers
                // often report this way) is worth a report too.
                else -> "was closed by Android (reason ${e.reason}, status ${e.status})"
            }
            val trace = if (e.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                // The main thread's stack shows what it was stuck on.
                runCatching {
                    e.traceInputStream?.bufferedReader()?.useLines { lines -> lines.take(250).joinToString("\n") }
                }.getOrNull()
            } else {
                null
            }
            val text = buildString {
                appendLine("Metro crash report")
                appendLine("Time: ${synchronized(stamp) { stamp.format(Date(e.timestamp)) }}")
                appendLine("Metro: ${versionName(context)}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Thread: -")
                appendLine()
                appendLine("Pane $reason.")
                e.description?.let { appendLine("Android says: $it") }
                appendLine("Memory: ${e.pss / 1024} MB in use (${e.rss / 1024} MB resident)")
                if (trace != null) {
                    appendLine()
                    appendLine(trace)
                }
                // What Pane was doing before it ended (events, freezes; see Watchdog).
                val before = if (e.timestamp != newest) emptyList() else runCatching { File(dir(context), "events_prev.log").readLines().takeLast(60) }.getOrDefault(emptyList())
                if (before.isNotEmpty()) {
                    appendLine()
                    appendLine("Recent events:")
                    before.forEach { appendLine(it) }
                }
            }
            File(dir(context), "crash_${e.timestamp}.txt").writeText(text)
            p.edit().putBoolean("unseen", true).apply()
        }
        dir(context).listFiles { f -> f.name.startsWith("crash_") }.orEmpty().sortedByDescending { it.name }
            .drop(MAX_REPORTS).forEach { it.delete() }
    }

    /** Notes a non-fatal problem; included in the next report. */
    @JvmStatic
    fun event(tag: String, message: String, error: Throwable? = null) {
        val line = "${now()} [$tag] $message" + (error?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        synchronized(events) {
            events.addLast(line)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        // Also kept on disk: a freeze or a kill leaves no time to write anything afterwards.
        val app = appContext ?: return
        writer.execute {
            runCatching {
                val f = File(dir(app), "events.log")
                if (f.length() > 64_000) f.writeText(f.readLines().takeLast(200).joinToString("\n", postfix = "\n"))
                f.appendText(line + "\n")
            }
        }
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
    }.getOrDefault("?")

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val recent = synchronized(events) { events.toList().takeLast(40) }
        val text = buildString {
            appendLine("Metro crash report")
            appendLine("Time: ${now()}")
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
