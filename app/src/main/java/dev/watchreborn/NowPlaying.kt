package dev.watchreborn

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** The newest readable notification for a package (2.8.0: the peek). */
data class NotifPeek(val pkg: String, val title: String, val text: String, val postTime: Long, val seq: Long)

/**
 * Notification listener that enables MediaSession access for the Smart Stack's Now Playing card
 * and tracks which packages currently have active notifications for honeycomb/list notification dots.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        refreshBadges()
    }

    override fun onListenerDisconnected() {
        instance = null
        activePackages = emptySet()
        activeSnippets = emptyMap()
        latestPeek = null
        onBadgesChanged?.invoke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refreshBadges()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refreshBadges()

    private fun refreshBadges() {
        val sbns = runCatching {
            activeNotifications?.filter { !it.isOngoing }
        }.getOrNull().orEmpty()
        val pkgs = sbns.mapNotNull { it.packageName }.toSet()
        val snippets = HashMap<String, String>()
        for (sbn in sbns) {
            val pkg = sbn.packageName ?: continue
            val ex = sbn.notification?.extras ?: continue
            val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val combined = when {
                title.isNotEmpty() && text.isNotEmpty() -> "$title: $text"
                title.isNotEmpty() -> title
                text.isNotEmpty() -> text
                else -> ""
            }
            if (combined.isNotEmpty() && pkg !in snippets) {
                snippets[pkg] = combined
            }
        }
        // 2.8.0: the newest readable notification per package, so the
        // launcher can sprout a peek pill from the owning bubble. Group
        // summaries are skipped while any real child exists.
        val usable = sbns.filter { (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0 }.ifEmpty { sbns }
        val newest = usable.mapNotNull { sbn ->
            val pkg = sbn.packageName ?: return@mapNotNull null
            val ex = sbn.notification?.extras ?: return@mapNotNull null
            val t = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val b = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (t.isEmpty() && b.isEmpty()) null else NotifPeek(pkg, t, b, sbn.postTime, 0L)
        }.maxByOrNull { it.postTime }
        if (pkgs != activePackages || snippets != activeSnippets) {
            activePackages = pkgs
            activeSnippets = snippets
            if (newest == null) {
                latestPeek = null
            } else if (latestPeek?.pkg != newest.pkg || latestPeek?.postTime != newest.postTime ||
                latestPeek?.title != newest.title || latestPeek?.text != newest.text
            ) {
                latestPeek = newest.copy(seq = ++peekSeqCounter)
            }
            onBadgesChanged?.invoke()
        }
    }

    companion object {
        @Volatile var instance: NowPlayingListener? = null
            private set
        @Volatile var activePackages: Set<String> = emptySet()
            private set
        @Volatile var activeSnippets: Map<String, String> = emptyMap()
            private set
        /** The single newest notification across packages (2.8.0 peek
         *  source); [NotifPeek.seq] increments only when its identity
         *  changes, so observers can spot genuine arrivals. */
        @Volatile var latestPeek: NotifPeek? = null
            private set
        private var peekSeqCounter = 0L
        @Volatile var onBadgesChanged: (() -> Unit)? = null
    }
}

/**
 * Tracks active media sessions for the Smart Stack Now Playing card and provides playback controls.
 */
class NowPlaying(private val context: Context, private val artPx: Int, private val onChange: () -> Unit) {
    class Track(val title: String, val artist: String?, val playing: Boolean, val art: Bitmap?, val pkg: String)

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var lastArt: Pair<Any?, Bitmap?>? = null

    @Volatile var track: Track? = null
        private set

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onSessionDestroyed() = pick()
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    val allowed: Boolean
        get() = runCatching { sessions.getActiveSessions(listener); true }.getOrDefault(false)

    fun start() {
        NowPlayingListener.onBadgesChanged = { main.post(onChange) }
        runCatching { sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main) }
        pick()
    }

    fun stop() {
        NowPlayingListener.onBadgesChanged = null
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun pick() {
        val all = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList())
        val next = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }
        update()
    }

    private fun update() {
        val c = controller
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val now = if (c == null || title.isNullOrBlank()) null else Track(
            title = title,
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            art = art(meta),
            pkg = c.packageName,
        )
        val old = track
        if (old?.title == now?.title && old?.artist == now?.artist && old?.playing == now?.playing && old?.art === now?.art && old?.pkg == now?.pkg) return
        track = now
        onChange()
    }

    private fun art(meta: MediaMetadata?): Bitmap? {
        val src = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        lastArt?.let { (from, scaled) -> if (from === src) return scaled }
        val scaled = src?.let { runCatching {
            val side = minOf(it.width, it.height)
            val square = Bitmap.createBitmap(it, (it.width - side) / 2, (it.height - side) / 2, side, side)
            Bitmap.createScaledBitmap(square, artPx, artPx, true)
        }.getOrNull() }
        lastArt = src to scaled
        return scaled
    }

    fun playPause() {
        val t = controller?.transportControls ?: return
        if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
    }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    fun open(): Boolean {
        val c = controller ?: return false
        c.sessionActivity?.let { pi ->
            val options = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic().apply {
                @Suppress("DEPRECATION")
                setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle() else null
            return runCatching { pi.send(context, 0, null, null, null, null, options); true }.getOrDefault(false)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(c.packageName) ?: return false
        context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
