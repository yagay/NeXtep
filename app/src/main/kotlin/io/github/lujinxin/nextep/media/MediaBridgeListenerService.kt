package io.github.lujinxin.nextep.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
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
 * One system media card for an audibly playing NeXtep VirtualDisplay task.
 * No target-app Hook, fake playback engine, or synthetic transport controls.
 * Real app-owned MediaSessions always take priority.
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
    private var lastDockReportMs = 0L
    private var lastDecision: String? = null
    private var lastNativeSummary: String? = null
    private var publishGeneration = 0

    private fun traceDecision(value: String) {
        if (lastDecision == value) return
        lastDecision = value
        NeXtepLog.info("media_bridge", "DOCK_DECISION $value")
    }
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
            CHANNEL, "NeXtep 槽位媒体", NotificationManager.IMPORTANCE_LOW))
        runCatching {
            sessions?.addOnActiveSessionsChangedListener(activeSessionsListener, listenerId, handler)
        }.onFailure { NeXtepLog.warn("media_bridge", "SESSION_LISTENER_FAILED", it) }
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
        NeXtepLog.info("media_bridge",
            "DOCK_MEDIA_LISTENER_CONNECTED notificationsEnabled=" +
                "${notifications?.areNotificationsEnabled() == true} permissionGranted=" +
                (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED))
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
        if (sbn?.packageName == packageName) {
            if (sbn.id == NOTIFICATION_ID) {
                NeXtepLog.info("media_bridge",
                    "BRIDGE_NOTIFICATION_LISTENER_POSTED id=${sbn.id} " +
                        "actions=${sbn.notification.actions?.size ?: 0} " +
                        "style=${sbn.notification.extras.getString(Notification.EXTRA_TEMPLATE)}")
            }
            return
        }
        handler.post { if (connected) refresh() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == packageName) {
            if (sbn.id == NOTIFICATION_ID && connected) {
                NeXtepLog.warn("media_bridge",
                    "BRIDGE_NOTIFICATION_LISTENER_REMOVED whileConnected=true")
            }
            return
        }
        handler.post { if (connected) refresh() }
    }

    private fun disconnect() {
        connected = false
        if (active === this) active = null
        handler.removeCallbacks(refreshTask)
        runCatching { sessions?.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        candidates.clear()
        lastDockReportMs = 0
        lastDecision = null
        lastNativeSummary = null
        retire()
    }

    /** Each report replaces the complete verified four-slot occupancy snapshot. */
    private fun observeDock(
        occupied: Set<String>,
        audible: Set<String>,
        uidReadable: Boolean,
    ) {
        if (!connected) return
        lastDockReportMs = SystemClock.elapsedRealtime()
        val confirmed = audible.intersect(occupied)
        candidates.keys.retainAll(confirmed)
        for (pkg in confirmed) {
            candidates[pkg] = MediaBridgeSnapshot(
                packageName = pkg,
                state = MediaBridgeContract.STATE_PLAYING,
                source = "systemui-dock-audio",
                title = null, // The audio framework does not expose episode metadata.
                artist = null,
                durationMs = -1L,
                positionMs = -1L,
                controllable = false, // Don't invent controls for inaccessible players.
                receivedAtMs = lastDockReportMs,
            )
        }
        NeXtepLog.debug("media_bridge",
            "DOCK_REPORT occupied=${occupied.size} audible=${confirmed.size} " +
                "uidReadable=$uidReadable")
        refresh()
    }

    /**
     * Some apps keep a MediaSession token alive without publishing a system
     * media notification. Suppressing every such token hid real, confirmed
     * dock audio on OxygenOS 17. Only an actually posted, active media-style
     * notification is sufficient proof that SystemUI has a native media card.
     */
    private fun originalSessionPackages(): Set<String> = runCatching {
        val notificationsWithTokens = runCatching {
            activeNotifications.orEmpty().mapNotNull { sbn ->
                val token = sbn.notification.extras.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
                if (token != null) sbn.packageName to token else null
            }
        }.onFailure {
            NeXtepLog.warn("media_bridge", "NATIVE_NOTIFICATION_SCAN_FAILED", it)
        }.getOrDefault(emptyList())

        val native = sessions?.getActiveSessions(listenerId).orEmpty()
            .filter { it.packageName != packageName }
        val preferred = native.filter { controller ->
            val hasMediaCard = notificationsWithTokens.any { (pkg, token) ->
                pkg == controller.packageName && token == controller.sessionToken
            }
            val state = runCatching { controller.playbackState?.state }.getOrNull()
            NativeMediaCardPolicy.shouldPreferNative(hasMediaCard, state)
        }.map { it.packageName }.toSet()

        val nativeSummary = "sessions=${native.size} nativeCards=" +
            "${notificationsWithTokens.size} preferred=${preferred.sorted()}"
        if (lastNativeSummary != nativeSummary) {
            lastNativeSummary = nativeSummary
            NeXtepLog.info("media_bridge", "NATIVE_CHECK $nativeSummary")
        }
        preferred
    }.onFailure { NeXtepLog.warn("media_bridge", "NATIVE_SESSION_QUERY_FAILED", it) }
        .getOrDefault(emptySet())

    private fun refresh() {
        if (!connected) return
        val settings = SettingsRepository(this)
        val now = SystemClock.elapsedRealtime()
        if (!settings.isMediaBridgeEnabled()) {
            traceDecision("DISABLED")
            candidates.clear()
            retire()
            return
        }
        if (now - lastDockReportMs > DOCK_REPORT_TTL_MS) {
            traceDecision("DOCK_REPORT_EXPIRED")
            candidates.clear()
            retire()
            return
        }
        candidates.entries.removeAll { (_, sample) ->
            now - sample.receivedAtMs > MediaBridgePolicy.PLAYING_STALE_MS
        }
        if (candidates.isEmpty()) {
            traceDecision("NO_CONFIRMED_DOCK_AUDIO")
            retire()
            return
        }
        val native = originalSessionPackages()
        val best = MediaBridgePolicy.choose(candidates.values, now, native)
        if (best == null) {
            traceDecision("SUPPRESSED_NATIVE_MEDIA packages=${native.sorted()}")
            retire()
            return
        }
        traceDecision("PROXY_ELIGIBLE pkg=${best.packageName} native=${native.sorted()}")
        if (session == null || selected?.packageName != best.packageName ||
            selected?.state != best.state) {
            runCatching { publish(best) }.onFailure {
                NeXtepLog.warn("media_bridge", "DOCK_MEDIA_PUBLISH_FAILED", it)
                retire()
            }
        }
    }

    private fun mediaAppIntent(targetPackage: String): PendingIntent {
        // Different players must never share a PendingIntent; package extras
        // alone are NOT part of Android PendingIntent identity.
        val open = Intent(this, MediaOpenActivity::class.java)
            .setData(Uri.Builder().scheme("nextep")
                .authority("media-open").appendPath(targetPackage).build())
            .putExtra(TriggerBroadcastContract.EXTRA_MEDIA_APP_PACKAGE, targetPackage)
        return PendingIntent.getActivity(this, 1803, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureSession(): MediaSession {
        session?.let { return it }
        return MediaSession(this, "NeXtepDockMedia").apply {
            // A real, clickable action is exposed instead of pretending to
            // support pause/next for a third-party video engine.
            setCallback(object : MediaSession.Callback() {
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action != ACTION_OPEN_MEDIA_APP) return
                    val pkg = selected?.packageName ?: return
                    runCatching { mediaAppIntent(pkg).send() }
                        .onFailure {
                            NeXtepLog.warn("media_bridge", "OPEN_MEDIA_APP_ACTION_FAILED", it)
                        }
                }
            }, handler)
            // Target task is assigned when a concrete dock app is selected.
            session = this
            // Activating after metadata/state are set prevents an initial
            // empty session from being selected by OxygenOS SystemUI.
        }
    }

    /** Readback validates framework registration, not actual OEM carousel rendering. */
    private fun verifyPublication(generation: Int, phase: String) {
        if (generation != publishGeneration || !connected || selected == null) return
        val current = session ?: return
        val own = runCatching {
            notifications?.activeNotifications?.firstOrNull {
                it.id == NOTIFICATION_ID && it.packageName == packageName
            }
        }.onFailure {
            NeXtepLog.warn("media_bridge", "READBACK_NOTIFICATION_QUERY_FAILED", it)
        }.getOrNull()
        val listener = runCatching {
            activeNotifications?.firstOrNull {
                it.id == NOTIFICATION_ID && it.packageName == packageName
            }
        }.onFailure {
            NeXtepLog.warn("media_bridge", "READBACK_LISTENER_QUERY_FAILED", it)
        }.getOrNull()
        val posted = own ?: listener
        val postedToken = posted?.notification?.extras?.getParcelable(
            Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        val managerSession = runCatching {
            sessions?.getActiveSessions(listenerId)?.firstOrNull {
                it.sessionToken == current.sessionToken
            }
        }.onFailure {
            NeXtepLog.warn("media_bridge", "READBACK_SESSION_QUERY_FAILED", it)
        }.getOrNull()
        val playback = managerSession?.playbackState ?: current.controller.playbackState
        val channelImportance = notifications?.getNotificationChannel(CHANNEL)?.importance ?: -1
        NeXtepLog.info("media_bridge",
            "BRIDGE_READBACK phase=$phase ownPosted=${own != null} " +
                "listenerPosted=${listener != null} " +
                "mediaTemplate=${posted?.notification?.extras?.getString(Notification.EXTRA_TEMPLATE)} " +
                "tokenMatch=${postedToken == current.sessionToken} " +
                "notificationActions=${posted?.notification?.actions?.size ?: -1} " +
                "sessionListed=${managerSession != null} active=${current.isActive} " +
                "state=${playback?.state ?: -1} customActions=${playback?.customActions?.size ?: -1} " +
                "channelImportance=$channelImportance")
    }

    private fun publish(sample: MediaBridgeSnapshot) {
        val media = ensureSession()
        selected = sample
        val appIntent = mediaAppIntent(sample.packageName)
        media.setSessionActivity(appIntent)
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
        if (art != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
        }
        media.setMetadata(metadata.build())
        media.setPlaybackState(PlaybackState.Builder()
            // No fake play/pause/seek actions. SystemUI may still show the
            // one supported custom action for opening the NeXtep window.
            .setActions(0L)
            .addCustomAction(ACTION_OPEN_MEDIA_APP,
                getString(R.string.media_bridge_open_app), R.drawable.ic_nextep_tile)
            .setState(PlaybackState.STATE_PLAYING,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1f, SystemClock.elapsedRealtime())
            .build())
        media.isActive = true
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nextep_tile)
            .setContentTitle(title)
            .setContentText(appName)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setContentIntent(appIntent)
            .addAction(Notification.Action.Builder(R.drawable.ic_nextep_tile,
                getString(R.string.media_bridge_open_app), appIntent).build())
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken)
                .setShowActionsInCompactView(0))
            .apply { if (art != null) setLargeIcon(art) }
            .build()
        val permissionGranted = checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = notifications?.areNotificationsEnabled() == true
        if (!permissionGranted || !notificationsEnabled) {
            NeXtepLog.warn("media_bridge",
                "MEDIA_NOTIFICATION_PERMISSION_BLOCKED granted=$permissionGranted " +
                    "notificationsEnabled=$notificationsEnabled")
        }
        val posted = runCatching {
            requireNotNull(notifications) { "NotificationManager unavailable" }
                .notify(NOTIFICATION_ID, notification)
            true
        }.onFailure {
            NeXtepLog.warn("media_bridge", "NOTIFICATION_FAILED", it)
        }.getOrDefault(false)
        if (!posted) {
            traceDecision("NOTIFICATION_POST_FAILED")
            retire()
            return
        }
        val generation = ++publishGeneration
        handler.postDelayed({ verifyPublication(generation, "early") }, 650L)
        handler.postDelayed({ verifyPublication(generation, "settled") }, 2_000L)
        NeXtepLog.info("media_bridge",
            "DOCK_MEDIA_PUBLISHED pkg=${sample.packageName} metadata=app-label " +
                "source=${sample.source} controls=open-module-only " +
                "notificationsEnabled=$notificationsEnabled granted=$permissionGranted")
    }

    private fun retire() {
        if (selected == null && session == null) return
        publishGeneration++
        NeXtepLog.info("media_bridge", "DOCK_MEDIA_RETIRED pkg=${selected?.packageName ?: "none"}")
        selected = null
        runCatching { notifications?.cancel(NOTIFICATION_ID) }
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
        private const val ACTION_OPEN_MEDIA_APP =
            "io.github.lujinxin.nextep.media.OPEN_MEDIA_APP"
        private const val CHANNEL = "nextep_media_bridge"
        private const val NOTIFICATION_ID = 18022
        private const val DOCK_REPORT_TTL_MS = 20_000L
        @Volatile private var active: MediaBridgeListenerService? = null

        internal fun acceptDock(
            occupied: Set<String>,
            audible: Set<String>,
            uidReadable: Boolean,
        ) {
            val target = active
            target?.handler?.post {
                if (active === target) target.observeDock(occupied, audible, uidReadable)
            }
        }
    }
}
