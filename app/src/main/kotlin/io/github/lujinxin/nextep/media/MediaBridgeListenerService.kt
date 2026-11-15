package io.github.lujinxin.nextep.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.MainActivity
import io.github.lujinxin.nextep.config.SettingsRepository
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * One passive proxy session, hosted by a user-enabled notification listener.
 * Never plays audio, never creates an extra decoder, never takes audio focus.
 * It only reflects trustworthy observations from selected LSPosed scopes.
 */
class MediaBridgeListenerService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val candidates = linkedMapOf<String, MediaBridgeSnapshot>()
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val sessions by lazy { getSystemService(MediaSessionManager::class.java) }
    private val listenerId by lazy { ComponentName(this, MediaBridgeListenerService::class.java) }
    private var session: MediaSession? = null
    private var selected: MediaBridgeSnapshot? = null
    private var connected = false
    private val refreshTask = object : Runnable {
        override fun run() {
            if (!connected) return
            refresh()
            handler.postDelayed(this, 5_000L)
        }
    }
    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        handler.post { if (connected) refresh() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        active = this
        notifications?.createNotificationChannel(NotificationChannel(
            CHANNEL, "NeXtep 通用媒体桥接", NotificationManager.IMPORTANCE_LOW))
        runCatching {
            sessions?.addOnActiveSessionsChangedListener(activeSessionsListener, listenerId, handler)
        }.onFailure { NeXtepLog.warn("media_bridge", "SESSION_LISTENER_FAILED", it) }
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
        NeXtepLog.info("media_bridge", "NOTIFICATION_LISTENER_CONNECTED")
    }

    override fun onListenerDisconnected() {
        disconnect()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == packageName) return
        handler.post { if (connected) refresh() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == packageName) return
        handler.post { if (connected) refresh() }
    }

    private fun disconnect() {
        connected = false
        if (active === this) active = null
        handler.removeCallbacks(refreshTask)
        runCatching { sessions?.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        candidates.clear()
        retire()
    }

    private fun observe(snapshot: MediaBridgeSnapshot) {
        if (!connected) return
        if (snapshot.state == MediaBridgeContract.STATE_STOPPED)
            candidates.remove(snapshot.packageName)
        else candidates[snapshot.packageName] = snapshot
        refresh()
    }

    private fun originalSessionPackages(): Set<String> = runCatching {
        sessions?.getActiveSessions(listenerId).orEmpty()
            .asSequence()
            .filter { it.packageName != packageName }
            .map(MediaController::getPackageName)
            .toSet()
    }.onFailure { NeXtepLog.warn("media_bridge", "NATIVE_SESSION_QUERY_FAILED", it) }
        .getOrDefault(emptySet())

    private fun refresh() {
        if (!connected) return
        val settings = SettingsRepository(this)
        if (!settings.isMediaBridgeEnabled()) {
            candidates.clear()
            retire()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val allow = settings.mediaBridgePackages()
        candidates.entries.removeAll { (pkg, sample) ->
            pkg !in allow || now - sample.receivedAtMs > MediaBridgePolicy.PAUSED_STALE_MS
        }
        val best = MediaBridgePolicy.choose(candidates.values, now, originalSessionPackages())
        if (best == null) {
            retire()
            return
        }
        if (best != selected) publish(best)
    }

    private fun ensureSession(): MediaSession {
        session?.let { return it }
        return MediaSession(this, "NeXtepUniversalMediaBridge").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command(MediaBridgeContract.COMMAND_PLAY)
                override fun onPause() = command(MediaBridgeContract.COMMAND_PAUSE)
                override fun onStop() {
                    command(MediaBridgeContract.COMMAND_PAUSE)
                    retire()
                }
            }, handler)
            val launch = Intent(this@MediaBridgeListenerService, MainActivity::class.java)
            setSessionActivity(PendingIntent.getActivity(
                this@MediaBridgeListenerService, 1801, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            isActive = true
            session = this
        }
    }

    private fun publish(sample: MediaBridgeSnapshot) {
        val media = ensureSession()
        selected = sample
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(sample.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sample.packageName)
        val art = runCatching {
            packageManager.getApplicationIcon(sample.packageName).toBitmap()
        }.getOrNull()
        val title = sample.title ?: appName
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, sample.artist ?: appName)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, appName)
        if (sample.durationMs > 0)
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, sample.durationMs)
        if (art != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
        media.setMetadata(metadata.build())
        val playing = sample.state == MediaBridgeContract.STATE_PLAYING
        val actions = if (sample.controllable)
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        else 0L
        media.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                if (sample.positionMs >= 0) sample.positionMs
                else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1f else 0f, SystemClock.elapsedRealtime())
            .build())
        val actionName = if (playing) MediaBridgeContract.COMMAND_PAUSE else MediaBridgeContract.COMMAND_PLAY
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nextep_tile)
            .setContentTitle(title)
            .setContentText(sample.artist ?: appName)
            .setShowWhen(false)
            .setOngoing(playing)
            .setContentIntent(media.sessionActivity)
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken))
            .apply {
                if (sample.controllable) {
                    val intent = Intent(this@MediaBridgeListenerService,
                        MediaBridgeControlReceiver::class.java)
                        .putExtra(MediaBridgeContract.EXTRA_COMMAND, actionName)
                    addAction(Notification.Action.Builder(
                        if (playing) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play,
                        if (playing) "暂停" else "播放",
                        PendingIntent.getBroadcast(this@MediaBridgeListenerService, 1802,
                            intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    ).build())
                }
                if (art != null) setLargeIcon(art)
            }
            .build()
        notifications?.notify(NOTIFICATION_ID, notification)
        NeXtepLog.info("media_bridge",
            "PUBLISHED pkg=${sample.packageName} state=${sample.state} " +
                "source=${sample.source} metadata=${sample.title != null} controls=${sample.controllable}")
    }

    private fun command(action: String) {
        val sample = selected ?: return
        if (!connected || !sample.controllable ||
            !SettingsRepository(this).isMediaBridgeEnabled()) return
        val intent = Intent(MediaBridgeContract.ACTION_COMMAND)
            .setPackage(sample.packageName)
            .putExtra(MediaBridgeContract.EXTRA_COMMAND, action)
        runCatching { sendBroadcast(intent) }.onFailure {
            NeXtepLog.warn("media_bridge", "COMMAND_FAILED pkg=${sample.packageName}", it)
        }
    }

    private fun retire() {
        if (selected == null && session == null) return
        NeXtepLog.info("media_bridge", "RETIRED pkg=${selected?.packageName ?: "none"}")
        selected = null
        notifications?.cancel(NOTIFICATION_ID)
        session?.runCatching { isActive = false; release() }
        session = null
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null && !bitmap.isRecycled)
            return bitmap
        val dim = 128
        val output = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val old = bounds
        setBounds(0, 0, dim, dim)
        draw(Canvas(output))
        bounds = old
        return output
    }

    companion object {
        private const val CHANNEL = "nextep_media_bridge"
        private const val NOTIFICATION_ID = 18022
        @Volatile private var active: MediaBridgeListenerService? = null

        fun accept(sample: MediaBridgeSnapshot) {
            val target = active
            target?.handler?.post { if (active === target) target.observe(sample) }
        }

        fun dispatchCommand(action: String) {
            val target = active
            target?.handler?.post { if (active === target) target.command(action) }
        }
    }
}

/** Notification actions remain confined to our own app process. */
class MediaBridgeControlReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val cmd = intent.getStringExtra(MediaBridgeContract.EXTRA_COMMAND)) {
            MediaBridgeContract.COMMAND_PLAY, MediaBridgeContract.COMMAND_PAUSE ->
                MediaBridgeListenerService.dispatchCommand(cmd)
        }
    }
}
