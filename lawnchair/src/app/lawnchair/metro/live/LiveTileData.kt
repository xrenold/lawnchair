package app.lawnchair.metro.live

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import com.android.launcher3.notification.NotificationListener
import java.util.concurrent.Executors

/** One message or notification shown on a live tile. */
data class LiveItem(
    val title: CharSequence?,
    val text: CharSequence?,
    val image: Bitmap?,
    val time: Long,
)

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
    /** Newest-first messages, for wide and large tiles that show several at once. */
    val items: List<LiveItem> = emptyList(),
    /** Post time of the newest message, so the Start screen can flip a tile when one arrives. */
    val latestTime: Long = 0L,
    /**
     * Something in progress (music, a download, navigation, a call). The tile keeps showing it
     * instead of flipping back and forth.
     */
    val ongoing: Boolean = false,
    /** Progress 0..1 for downloads and uploads, -1 if none, 2 if indeterminate. */
    val progress: Float = -1f,
    /** Media session controls, for play/pause and skip on wide and large music tiles. */
    val controller: MediaController? = null,
    val isPlaying: Boolean = false,
    /** Notifications the Start panel can show (calls left out). */
    val panelCount: Int = 0,
    /** Turn-by-turn navigation: [image] is the next turn's arrow, [title] the distance to it. */
    val nav: Boolean = false,
    /** Extra line for navigation (usually time left and arrival). */
    val subText: CharSequence? = null,
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
                // Many updates change nothing a tile shows (a progress tick, a re-post): skip those.
                if (result == snapshot) return@post
                snapshot = result
                listeners.toList().forEach { it(result) }
            }
        }
    }

    private fun build(context: Context): Map<String, LiveInfo> {
        val listener = NotificationListener.getInstanceIfConnected() ?: return emptyMap()
        val active: Array<StatusBarNotification> = runCatching { listener.activeNotifications }.getOrNull() ?: emptyArray()

        // Drop cached content for notifications that are gone.
        val activeKeys = active.mapTo(HashSet()) { it.key }
        itemCache.keys.retainAll(activeKeys)
        iconCache.keys.retainAll(activeKeys)

        val byPackage = HashMap<String, LiveInfo>()
        active
            .filter { isUserFacing(it) }
            .groupBy { it.packageName }
            .forEach { (pkg, list) ->
                val count = list.sumOf { sbn -> sbn.notification.number.takeIf { it > 0 } ?: 1 }
                val items = list
                    .flatMap { sbn -> cachedItems(context, sbn) }
                    .sortedByDescending { it.time }
                    .take(MAX_ITEMS)
                val first = items.firstOrNull()
                byPackage[pkg] = LiveInfo(
                    pkg, count, first?.title, first?.text, first?.image,
                    items = items,
                    latestTime = list.maxOf { it.postTime },
                    panelCount = list.count { !isCall(it) },
                )
            }

        // Ongoing work with something to show: downloads/uploads with progress, navigation,
        // calls, timers. It takes over the tile's back face for as long as it runs.
        active
            .filter { isOngoingWorth(it) }
            .sortedByDescending { it.postTime }
            .distinctBy { it.packageName }
            .forEach { sbn ->
                val e = sbn.notification.extras
                val max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                val value = e.getInt(Notification.EXTRA_PROGRESS, 0)
                val indeterminate = e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                val progress = when {
                    indeterminate -> 2f
                    max > 0 -> (value.toFloat() / max).coerceIn(0f, 1f)
                    else -> -1f
                }
                val prev = byPackage[sbn.packageName]
                val image = cachedIcon(context, sbn)
                val nav = sbn.notification.category == Notification.CATEGORY_NAVIGATION
                byPackage[sbn.packageName] = (prev ?: LiveInfo(sbn.packageName)).copy(
                    title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    text = (e.getCharSequence(Notification.EXTRA_TEXT) ?: e.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString(),
                    image = image,
                    ongoing = true,
                    progress = progress,
                    nav = nav,
                    subText = if (nav) e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() else null,
                )
            }

        // Music: the playing media session's artwork and track, replacing that app's message info.
        val sessions = runCatching {
            context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, NotificationListener::class.java))
        }.getOrNull().orEmpty()
        // Android hands out new controller objects each time; reuse ours per session so an
        // unchanged song compares equal and doesn't redraw anything.
        controllers.keys.retainAll(sessions.map { it.sessionToken }.toSet())
        for (fresh in sessions) {
            val controller = controllers.getOrPut(fresh.sessionToken) { fresh }
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
            // Keep the app's unread count; the music itself replaces the message content.
            val scaled = art?.let { a ->
                // Apps hand over a new bitmap object each time; key on what identifies the art.
                val k = "$pkg|$title|$artist|${a.width}x${a.height}"
                artCache?.takeIf { it.first == k }?.second ?: scaleDown(a).also { artCache = k to it }
            }
            byPackage[pkg] = LiveInfo(
                pkg, byPackage[pkg]?.count ?: 0, title, artist, scaled,
                isMusic = true,
                ongoing = true,
                controller = controller,
                isPlaying = state == PlaybackState.STATE_PLAYING,
            )
        }
        return byPackage
    }

    private const val MAX_ITEMS = 6

    // Built content per notification (key → postTime and items), so a change to one
    // notification doesn't re-decode every sender's picture. Only touched on the worker thread.
    private val itemCache = HashMap<String, Pair<Long, List<LiveItem>>>()
    private val iconCache = HashMap<String, Pair<Long, Bitmap?>>()
    private var artCache: Pair<String, Bitmap>? = null
    private val controllers = HashMap<android.media.session.MediaSession.Token, android.media.session.MediaController>()

    private fun cachedItems(context: Context, sbn: StatusBarNotification): List<LiveItem> {
        itemCache[sbn.key]?.let { (t, items) -> if (t == sbn.postTime) return items }
        return itemsOf(context, sbn).also { itemCache[sbn.key] = sbn.postTime to it }
    }

    private fun cachedIcon(context: Context, sbn: StatusBarNotification): Bitmap? {
        val old = iconCache[sbn.key]
        old?.let { (t, bmp) -> if (t == sbn.postTime) return bmp }
        var bmp = runCatching { sbn.notification.getLargeIcon()?.let { loadIcon(context, it) } }.getOrNull()
        // Navigation re-posts every few seconds, usually with the same arrow: keep the same
        // picture then, so the tile sees nothing changed and doesn't redraw.
        val prevBmp = old?.second
        if (bmp != null && prevBmp != null && runCatching { bmp!!.sameAs(prevBmp) }.getOrDefault(false)) bmp = prevBmp
        iconCache[sbn.key] = sbn.postTime to bmp
        return bmp
    }

    /**
     * The app's notifications for the Start notification panel, newest first. Calls are left
     * out: they need no action from Start.
     */
    @JvmStatic
    fun notificationsFor(pkg: String): List<StatusBarNotification> {
        val listener = NotificationListener.getInstanceIfConnected() ?: return emptyList()
        val active = runCatching { listener.activeNotifications }.getOrNull() ?: return emptyList()
        return active.filter { it.packageName == pkg && isUserFacing(it) && !isCall(it) }
            .sortedByDescending { it.postTime }
    }

    /** True when the app has notifications the panel can show. */
    @JvmStatic
    fun hasPanelContent(pkg: String): Boolean {
        // From the snapshot already in memory: no call to Android on every touch.
        val info = snapshot[pkg] ?: return false
        return info.panelCount > 0 && !info.isMusic
    }

    /** Dismisses a notification everywhere, including the notification shade. */
    @JvmStatic
    fun dismiss(key: String) {
        runCatching { NotificationListener.getInstanceIfConnected()?.cancelNotification(key) }
        requestRefresh()
    }

    private fun isCall(sbn: StatusBarNotification): Boolean {
        val c = sbn.notification.category
        return c == Notification.CATEGORY_CALL || c == "missed_call"
    }

    /**
     * Messages in a notification: each message of a chat (MessagingStyle) separately, with
     * its sender, or the notification's title and text otherwise.
     */
    private fun itemsOf(context: Context, sbn: StatusBarNotification): List<LiveItem> {
        val n = sbn.notification
        val extras = n.extras
        val picture = runCatching { n.getLargeIcon()?.let { loadIcon(context, it) } }.getOrNull()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messages.isNullOrEmpty()) {
            return messages.takeLast(MAX_ITEMS).mapNotNull { m ->
                val b = m as? android.os.Bundle ?: return@mapNotNull null
                val text = b.getCharSequence("text") ?: return@mapNotNull null
                val sender = b.getCharSequence("sender")
                    ?: (b.getParcelable<android.app.Person>("sender_person"))?.name
                val title = when {
                    conversation != null && sender != null -> "$sender · $conversation"
                    else -> sender ?: conversation ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                }
                LiveItem(title, text, picture, b.getLong("time", sbn.postTime))
            }
        }
        val title = conversation ?: extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        return listOf(LiveItem(title, text, picture, sbn.postTime))
    }

    /** Ongoing notifications worth a tile: progress, navigation, calls, stopwatch/timers. */
    private fun isOngoingWorth(sbn: StatusBarNotification): Boolean {
        if (!sbn.isOngoing) return false
        val n = sbn.notification
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false // handled as music
        if (n.extras.getCharSequence(Notification.EXTRA_TITLE) == null) return false
        val hasProgress = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
            n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        return hasProgress || n.category in setOf(
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_STOPWATCH,
        )
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
