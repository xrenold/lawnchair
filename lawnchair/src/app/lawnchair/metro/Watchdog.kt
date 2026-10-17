package app.lawnchair.metro

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Notices when Pane stalls. While Pane is in front, a background thread checks every second
 * that the main thread is still answering. If it hasn't for two seconds, what the main thread
 * is busy with is written to the crash log's events, and how long the stall lasted once it
 * ends. Android's own freeze report (after five seconds) then comes with what led up to it, and
 * shorter stalls (lag) show up in the next report too.
 */
object Watchdog {

    private const val CHECK_MS = 1000L
    private const val STALL_MS = 2000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastAnswer = 0L
    @Volatile private var thread: Thread? = null
    private val ping = Runnable { lastAnswer = SystemClock.uptimeMillis() }

    /** Starts watching (Pane came to the front). */
    fun start() {
        if (thread != null) return
        lastAnswer = SystemClock.uptimeMillis()
        thread = Thread({ watch() }, "pane-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops watching (Pane went to the back). */
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun watch() {
        val me = Thread.currentThread()
        var stalledSince = 0L
        while (thread === me) {
            main.post(ping)
            try {
                Thread.sleep(CHECK_MS)
            } catch (e: InterruptedException) {
                return
            }
            val now = SystemClock.uptimeMillis()
            val silent = now - lastAnswer
            if (silent >= STALL_MS && stalledSince == 0L) {
                stalledSince = lastAnswer
                val stack = Looper.getMainLooper().thread.stackTrace
                    .take(25).joinToString("\n") { "    at $it" }
                CrashLog.event("stall", "Pane hasn't responded for ${silent / 1000.0}s, busy with:\n$stack")
            } else if (silent < STALL_MS && stalledSince != 0L) {
                CrashLog.event("stall", "responding again after ${(lastAnswer - stalledSince) / 1000.0}s")
                stalledSince = 0L
            }
        }
    }
}
