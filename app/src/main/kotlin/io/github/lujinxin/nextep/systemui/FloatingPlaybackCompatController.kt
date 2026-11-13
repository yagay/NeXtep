package io.github.lujinxin.nextep.systemui

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.util.concurrent.Executors

/**
 * Opt-in MediaSession adapter for the *floating* workspace only.
 *
 * This is deliberately not a synthetic Activity.onResume hook. MediaSession
 * is the player's own public transport API; it is safer than intercepting
 * every pause() method in a foreign application. A single recovery is possible
 * only when we saw the SAME session PLAYING while the panel was visible and it
 * unexpectedly changed to PAUSED shortly after the user folded the panel.
 *
 * SystemUI may have MEDIA_CONTENT_CONTROL. Some OEMs do not grant it:
 * access failure is logged once, and the feature fails closed.
 */
internal class FloatingPlaybackCompatController(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val mediaManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NeXtep-FloatingPlayback").apply { isDaemon = true }
    }

    private data class Playing(val packageName: String, val at: Long)
    private data class Config(val enabled: Boolean, val autoResume: Boolean, val packages: Set<String>)
    @Volatile private var config = Config(false, false, emptySet())
    @Volatile private var slotPackages: Set<String> = emptySet()
    @Volatile private var generation = 0L
    private var visible = false
    private var lastPlaying: Map<MediaSession.Token, Playing> = emptyMap()
    private var accessFailureLogged = false

    fun configure(enabled: Boolean, autoResume: Boolean, packages: Set<String>) {
        val next = Config(enabled, autoResume, packages.toSet())
        if (config == next) return
        config = next
        generation += 1
        lastPlaying = emptyMap()
        handler.removeCallbacks(sampleVisible)
        NeXtepLog.info("playback_compat",
            "Policy enabled=$enabled auto=$autoResume appCount=${packages.size}; " +
                "MediaSession-only, never changes audio-focus policy")
        if (visible) handler.post(sampleVisible)
    }

    fun onSlotPackagesChanged(packages: Set<String>) {
        val previous = slotPackages
        slotPackages = packages.toSet()
        val setting = config
        if (!visible || !setting.enabled) return
        // When a playing app is moved from display 0 into a floating slot,
        // its MediaSession may pause even though the panel is still visible.
        // The visible monitor observes selected packages on BOTH displays,
        // but only tries recovery once the app actually occupies a slot.
        val added = packages - previous
        val now = SystemClock.elapsedRealtime()
        val eligible = lastPlaying.filterValues {
            it.packageName in added && it.packageName in setting.packages &&
                now - it.at in 0L..3_000L
        }
        if (setting.autoResume && eligible.isNotEmpty()) {
            val token = ++generation
            NeXtepLog.info("playback_compat",
                "Selected playing app entered floating slot count=${eligible.size}")
            scheduleRecovery(token, now, eligible, requireHidden = false)
        }
        handler.removeCallbacks(sampleVisible)
        handler.post(sampleVisible)
    }

    fun onVisibilityChanged(show: Boolean, packages: Set<String>, lockEvent: Boolean = false) {
        slotPackages = packages.toSet()
        visible = show
        val nextGeneration = ++generation
        handler.removeCallbacks(sampleVisible)
        val setting = config
        if (!setting.enabled || setting.packages.isEmpty()) return
        if (show) {
            handler.post(sampleVisible)
            return
        }
        if (lockEvent || !setting.autoResume) {
            NeXtepLog.info("playback_compat",
                "Hidden without playback control lock=$lockEvent auto=${setting.autoResume}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val eligible = lastPlaying.filterValues { state ->
            state.packageName in setting.packages &&
                state.packageName in packages && now - state.at in 0L..3_000L
        }
        if (eligible.isEmpty()) {
            NeXtepLog.info("playback_compat",
                "No recent playing MediaSession at fold; no auto-play attempt")
            return
        }
        NeXtepLog.info("playback_compat", "Fold observed ${eligible.size} recently playing session(s)")
        scheduleRecovery(nextGeneration, now, eligible, requireHidden = true)
    }

    private fun scheduleRecovery(token: Long, eventAt: Long,
                                 eligible: Map<MediaSession.Token, Playing>,
                                 requireHidden: Boolean) {
        val tried = mutableSetOf<MediaSession.Token>()
        // Only two checks and at most ONE play command per session.
        // A later manual pause is never subjected to a retry loop.
        for (delay in listOf(850L, 1_850L)) {
            handler.postDelayed({
                if (generation == token && (!requireHidden || !visible)) {
                    worker.execute { tryRecover(token, eventAt, eligible, tried, requireHidden) }
                }
            }, delay)
        }
    }

    fun shutdown() {
        generation += 1
        visible = false
        lastPlaying = emptyMap()
        slotPackages = emptySet()
        handler.removeCallbacks(sampleVisible)
    }

    private val sampleVisible: Runnable = object : Runnable {
        override fun run() {
            val setting = config
            if (!visible || !setting.enabled || setting.packages.isEmpty()) return
            val token = generation
            // Observe only user-selected apps, including a player that is
            // *about to be moved* from the main screen into an empty slot.
            val tracked = setting.packages
            worker.execute {
                val controllers = sessionsOrNull()
                val at = SystemClock.elapsedRealtime()
                val playing = controllers.orEmpty().mapNotNull { controller ->
                    if (controller.packageName !in tracked ||
                        controller.playbackState?.state != PlaybackState.STATE_PLAYING) null
                    else controller.sessionToken to Playing(controller.packageName, at)
                }.toMap()
                handler.post {
                    if (generation != token || !visible) return@post
                    // Preserve short-lived playing history so a session does
                    // not disappear when the user folds between two polls.
                    lastPlaying = (lastPlaying.filterValues { at - it.at <= 3_000L } + playing)
                    handler.postDelayed(this, 1_350L)
                }
            }
        }
    }

    private fun sessionsOrNull(): List<MediaController>? = runCatching {
        checkNotNull(mediaManager) { "MediaSessionManager unavailable" }.getActiveSessions(null)
    }.onFailure { error ->
        if (!accessFailureLogged) {
            accessFailureLogged = true
            NeXtepLog.warn("playback_compat",
                "Cannot read active MediaSessions; auto recovery unavailable " +
                    "(SystemUI may lack MEDIA_CONTENT_CONTROL)", error)
        }
    }.getOrNull()

    private fun tryRecover(
        token: Long,
        foldedAt: Long,
        eligible: Map<MediaSession.Token, Playing>,
        tried: MutableSet<MediaSession.Token>,
        requireHidden: Boolean,
    ) {
        if (generation != token || (requireHidden && visible)) return
        val setting = config
        val now = SystemClock.elapsedRealtime()
        val controllers = sessionsOrNull() ?: return
        val locked = keyguardManager?.isKeyguardLocked ?: true
        val interactive = powerManager?.isInteractive == true
        val normalAudio = audioManager?.mode == AudioManager.MODE_NORMAL
        val otherPlayingTokens = controllers.filter {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }.map { it.sessionToken }.toSet()
        controllers.forEach { controller ->
            val session = controller.sessionToken
            val before = eligible[session] ?: return@forEach
            val state = controller.playbackState
            val canPlay = state != null &&
                (state.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)) != 0L
            val shouldPlay = FloatingPlaybackRecoveryPolicy.mayRecover(
                enabled = setting.enabled,
                autoResume = setting.autoResume,
                appSelected = controller.packageName in setting.packages,
                stillInFloatingSlot = controller.packageName in slotPackages,
                previouslyPlaying = before.packageName == controller.packageName,
                sameSession = true,
                nowPaused = state?.state == PlaybackState.STATE_PAUSED,
                supportsPlay = canPlay,
                otherMediaPlaying = otherPlayingTokens.any { it != session },
                displayInteractive = interactive,
                keyguardLocked = locked,
                audioModeNormal = normalAudio,
                triedAlready = session in tried,
                millisecondsSinceFold = now - foldedAt,
            )
            if (!shouldPlay) return@forEach
            // The generation can change while a Binder query is in progress.
            if (generation != token || (requireHidden && visible) ||
                !tried.add(session)) return@forEach
            runCatching { controller.transportControls.play() }
                .onSuccess {
                    NeXtepLog.info("playback_compat",
                        "One-shot MediaSession play requested package=${controller.packageName}, " +
                            "eventAgeMs=${now - foldedAt} hidden=$requireHidden; " +
                            "not a guarantee of actual playback")
                }.onFailure { error ->
                    NeXtepLog.warn("playback_compat",
                        "Session rejected recovery for package=${controller.packageName}", error)
                }
        }
    }
}
