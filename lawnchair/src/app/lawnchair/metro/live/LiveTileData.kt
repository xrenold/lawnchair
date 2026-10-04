package app.lawnchair.metro.live

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import com.android.launcher3.notification.NotificationListener
import java.util.concurrent.Executors

/** What a live tile can show for one app. */
data class LiveInfo(
    val packageName: String,
    /** Unread notifications (WP-style count). */
    val count: Int = 0,
    /** Sender / headline of the newest notification. */
    val title: CharSequence? = null,
    /** Body text of the newest notification. */
    val text: CharSequence? = null,
    /** Sender's picture for messages, or album art for music. */
    val image: Bitmap? = null,
    /** True when [image] is album art from a playing media session. */
    val isMusic: Boolean = false,
)

/**
 * Feeds live tiles from the launcher's notification listener (the same service Lawnchair uses
 * for notification dots) and from active media sessions.
 *
 * Launcher3's [NotificationListener] calls [onNotificationsChanged] on every change; we rebuild
 * a per-app snapshot on a background thread and hand it to the Start screen on the main thread.
 */
object LiveTileData {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(Map<String, LiveInfo>) -> Unit>()

    @Volatile
    var snapshot: Map<String, LiveInfo> = emptyMap()
        private set

    private var appContext: Context? = null
    private val refreshRunnable = Runnable { refreshNow() }

    @JvmStatic
    fun addListener(context: Context, listener: (Map<String, LiveInfo>) -> Unit) {
        appContext = context.applicationContext
        listeners += listener
        listener(snapshot)
        requestRefresh()
    }

    @JvmStatic
    fun removeListener(listener: (Map<String, LiveInfo>) -> Unit) {
        listeners -= listener
    }

    /** Called by Launcher3's NotificationListener on connect, post, remove and disconnect. */
    @JvmStatic
    fun onNotificationsChanged(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        requestRefresh()
    }

    /** True when the launcher can read notifications (needed for live tiles). */
    @JvmStatic
    fun hasAccess(): Boolean = NotificationListener.getInstanceIfConnected() != null

    private fun requestRefresh() {
        // Coalesce bursts (e.g. a group of messages arriving together).
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, 250)
    }

    private fun refreshNow() {
        val context = appContext ?: return
        worker.execute {
            val result = runCatching { build(context) }.getOrDefault(emptyMap())
            main.post {
                snapshot = result
                listeners.toList().forEach { it(result) }
            }
        }
    }

    private fun build(context: Context): Map<String, LiveInfo> {
        val listener = NotificationListener.getInstanceIfConnected() ?: return emptyMap()
        val active: Array<StatusBarNotification> = runCatching { listener.activeNotifications }.getOrNull() ?: emptyArray()

        val byPackage = HashMap<String, LiveInfo>()
        active
            .filter { isUserFacing(it) }
            .sortedByDescending { it.postTime }
            .forEach { sbn ->
                val pkg = sbn.packageName
                val prev = byPackage[pkg]
                val number = sbn.notification.number.takeIf { it > 0 } ?: 1
                if (prev == null) {
                    val extras = sbn.notification.extras
                    val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                        ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                    val text = lastMessageText(sbn.notification)
                        ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    val image = sbn.notification.getLargeIcon()?.let { loadIcon(context, it) }
                    byPackage[pkg] = LiveInfo(pkg, number, title, text, image)
                } else {
                    byPackage[pkg] = prev.copy(count = prev.count + number)
                }
            }

        // Music: the playing media session's artwork and track, replacing that app's message info.
        val sessions = runCatching {
            context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, NotificationListener::class.java))
        }.getOrNull().orEmpty()
        for (controller in sessions) {
            val state = controller.playbackState?.state
            if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_PAUSED) continue
            val meta = controller.metadata ?: continue
            val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val title = meta.getText(MediaMetadata.METADATA_KEY_TITLE) ?: continue
            val artist = meta.getText(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val pkg = controller.packageName
            byPackage[pkg] = LiveInfo(pkg, 0, title, artist, art?.let(::scaleDown), isMusic = true)
        }
        return byPackage
    }

    /** Ignore ongoing, silent-summary and media-style notifications when counting. */
    private fun isUserFacing(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.isOngoing) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return false
        return n.extras.getCharSequence(Notification.EXTRA_TITLE) != null ||
            n.extras.getCharSequence(Notification.EXTRA_TEXT) != null
    }

    private fun lastMessageText(n: Notification): CharSequence? {
        val messages = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? android.os.Bundle ?: return null
        return last.getCharSequence("text")
    }

    private fun loadIcon(context: Context, icon: Icon): Bitmap? = runCatching {
        val d = icon.loadDrawable(context) ?: return null
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(android.graphics.Canvas(bmp))
        bmp
    }.getOrNull()

    private fun scaleDown(src: Bitmap): Bitmap {
        val max = 720
        if (src.width <= max && src.height <= max) return src
        val s = max.toFloat() / maxOf(src.width, src.height)
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
    }
}
