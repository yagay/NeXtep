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
    private data class Config(
        val enabled: Boolean, val autoResume: Boolean,
        val lockAudio: Boolean, val packages: Set<String>,
        val autoFloatingSlots: Boolean,
    ) {
        fun active(): Boolean = enabled || autoFloatingSlots
        fun autoResumeEnabled(): Boolean =
            (enabled && autoResume) || autoFloatingSlots
        fun lockAudioEnabled(): Boolean =
            (enabled && lockAudio) || autoFloatingSlots
        fun includes(name: String, joinedSlots: Set<String>): Boolean =
            (enabled && name in packages) ||
                (autoFloatingSlots && name in joinedSlots)
    }
    @Volatile private var config = Config(false, false, false, emptySet(), false)
    @Volatile private var slotPackages: Set<String> = emptySet()
    @Volatile private var generation = 0L
    private var visible = false
    private var lastPlaying: Map<MediaSession.Token, Playing> = emptyMap()
    @Volatile private var recentControllers: Map<MediaSession.Token, MediaController> = emptyMap()
    private var accessFailureLogged = false
    private val lockWakeLock = runCatching {
        powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeXtep:floating-lock-audio")
    }.getOrNull()

    private fun releaseLockWakeLock() {
        runCatching {
            lockWakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { NeXtepLog.warn("playback_compat", "Wake lock release failed", it) }
    }

    fun configure(
        enabled: Boolean, autoResume: Boolean, lockAudio: Boolean, packages: Set<String>,
        autoFloatingSlots: Boolean = false,
    ) {
        val next = Config(enabled, autoResume, lockAudio, packages.toSet(), autoFloatingSlots)
        if (config == next) return
        config = next
        generation += 1
        lastPlaying = emptyMap()
        recentControllers = emptyMap()
        handler.removeCallbacks(sampleVisible)
        releaseLockWakeLock()
        NeXtepLog.info("playback_compat",
            "Policy enabled=$enabled auto=$autoResume lockAudio=$lockAudio " +
                "appCount=${packages.size} autoFloatingSlots=$autoFloatingSlots; " +
                "MediaSession-only, never changes audio-focus policy")
        if (next.active() &&
            (visible || (next.lockAudioEnabled() && powerManager?.isInteractive == true)))
            handler.post(sampleVisible)
    }

    fun onSlotPackagesChanged(packages: Set<String>) {
        val previous = slotPackages
        slotPackages = packages.toSet()
        val setting = config
        if (!visible || !setting.active()) return
        // When a playing app is moved from display 0 into a floating slot,
        // its MediaSession may pause even though the panel is still visible.
        // The visible monitor observes selected packages on BOTH displays,
        // but only tries recovery once the app actually occupies a slot.
        val added = packages - previous
        val now = SystemClock.elapsedRealtime()
        val eligible = lastPlaying.filterValues {
            it.packageName in added && setting.includes(it.packageName, packages) &&
                now - it.at in 0L..3_000L
        }
        if (setting.autoResumeEnabled() && eligible.isNotEmpty()) {
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
        if (!setting.active()) return
        if (show) {
            handler.post(sampleVisible)
            return
        }
        if (lockEvent || !setting.autoResumeEnabled()) {
            NeXtepLog.info("playback_compat",
                "Hidden without folding recovery lock=$lockEvent auto=${setting.autoResumeEnabled()}")
            if (!lockEvent && setting.lockAudioEnabled() && powerManager?.isInteractive == true)
                handler.post(sampleVisible)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val eligible = lastPlaying.filterValues { state ->
            setting.includes(state.packageName, packages) &&
                state.packageName in packages && now - state.at in 0L..3_000L
        }
        if (eligible.isEmpty()) {
            NeXtepLog.info("playback_compat",
                "No recent playing MediaSession at fold; no auto-play attempt")
            // A user may leave the panel folded for minutes before locking.
            // Keep sampling at low frequency while interactive so the
            // last PLAYING state is still available at the eventual lock.
            if (setting.lockAudioEnabled() && powerManager?.isInteractive == true)
                handler.post(sampleVisible)
            return
        }
        NeXtepLog.info("playback_compat", "Fold observed ${eligible.size} recently playing session(s)")
        scheduleRecovery(nextGeneration, now, eligible, requireHidden = true)
        if (setting.lockAudioEnabled() && powerManager?.isInteractive == true)
            handler.post(sampleVisible)
    }

    /**
     * Called on every SCREEN_OFF even if the dock was folded a long time ago.
     * Never reveals a private virtual-display frame over the keyguard.
     * Attempts one MediaSession transport play only for a recently playing
     * session the user explicitly selected for lockscreen audio.
     */
    fun onScreenLocked(packages: Set<String>) {
        slotPackages = packages.toSet()
        val token = ++generation
        handler.removeCallbacks(sampleVisible)
        val setting = config
        if (!setting.active() || !setting.lockAudioEnabled() || packages.isEmpty()) return
        val lockedAt = SystemClock.elapsedRealtime()
        // Take a fresh snapshot at SCREEN_OFF rather than relying solely on
        // the periodic UI sampler (which can be cancelled by dock collapse).
        val cachedPlaying = lastPlaying.filterValues {
            it.packageName in packages && setting.includes(it.packageName, packages) &&
                lockedAt - it.at in 0L..8_000L
        }
        runCatching { lockWakeLock?.acquire(4_500L) }.onFailure {
            NeXtepLog.warn("playback_compat", "Short CPU wake lock unavailable", it)
        }
        worker.execute {
            val active = sessionsOrNull().orEmpty()
            val fresh = active.mapNotNull { controller ->
                if (controller.packageName !in packages ||
                    !setting.includes(controller.packageName, packages) ||
                    controller.playbackState?.state != PlaybackState.STATE_PLAYING) null
                else controller.sessionToken to Playing(controller.packageName, lockedAt)
            }.toMap()
            val eligible = cachedPlaying + fresh
            val observed = active.filter { it.sessionToken in eligible.keys }
                .associateBy { it.sessionToken }
            handler.post {
                if (generation != token) return@post
                recentControllers = recentControllers.filterKeys { it in eligible.keys } + observed
                if (eligible.isEmpty()) {
                    NeXtepLog.info("playback_compat",
                        "Lock recovery skipped: no selected PLAYING MediaSession in " +
                            "recent history or screen-off snapshot; packages=${packages.size} " +
                            "activeSessions=${active.size}")
                    releaseLockWakeLock()
                    return@post
                }
                NeXtepLog.info("playback_compat",
                    "Lock audio tracking cached=${cachedPlaying.size} fresh=${fresh.size} " +
                        "eligible=${eligible.size} activeSessions=${active.size}")
                val tried = mutableSetOf<MediaSession.Token>()
                for (delay in listOf(700L, 1_650L)) {
                    handler.postDelayed({
                        if (generation == token) worker.execute {
                            tryRecoverOnLock(token, lockedAt, eligible, tried)
                        }
                    }, delay)
                }
                handler.postDelayed({
                    if (generation == token) releaseLockWakeLock()
                }, 2_650L)
            }
        }
    }

    fun onScreenUnlocked() {
        generation++
        releaseLockWakeLock()
        handler.removeCallbacks(sampleVisible)
        if (config.active() && (visible || config.lockAudioEnabled()))
            handler.post(sampleVisible)
    }

    private fun tryRecoverOnLock(
        token: Long,
        lockedAt: Long,
        eligible: Map<MediaSession.Token, Playing>,
        tried: MutableSet<MediaSession.Token>,
    ) {
        if (generation != token || powerManager?.isInteractive != false) return
        val setting = config
        if (!setting.active() || !setting.lockAudioEnabled()) return
        val activeControllers = sessionsOrNull() ?: return
        // A player may mark its session inactive on Activity.onStop without
        // destroying the MediaSession binder. Reuse the verified controller
        // observed while the app was actually PLAYING; never invent one.
        val previouslyObserved = recentControllers.filterKeys { it in eligible.keys }
        val controllers = (previouslyObserved +
            activeControllers.associateBy { it.sessionToken }).values.toList()
        val now = SystemClock.elapsedRealtime()
        val audioNormal = audioManager?.mode == AudioManager.MODE_NORMAL
        val playingTokens = activeControllers.filter {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }.map { it.sessionToken }.toSet()
        controllers.forEach { controller ->
            val session = controller.sessionToken
            val prior = eligible[session] ?: return@forEach
            val state = controller.playbackState
            val canPlay = state != null &&
                (state.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)) != 0L
            val allowed = FloatingLockscreenPlaybackPolicy.mayResume(
                enabled = setting.active(),
                optedIn = setting.lockAudioEnabled(),
                appSelected = setting.includes(controller.packageName, slotPackages),
                stillInFloatingSlot = controller.packageName in slotPackages,
                previouslyPlaying = prior.packageName == controller.packageName,
                sameSession = true,
                paused = state?.state == PlaybackState.STATE_PAUSED,
                supportsPlay = canPlay,
                screenOff = powerManager?.isInteractive == false,
                audioModeNormal = audioNormal,
                otherMediaPlaying = playingTokens.any { it != session },
                alreadyAttempted = session in tried,
                millisecondsSinceLock = now - lockedAt,
            )
            if (!allowed) {
                NeXtepLog.info("playback_compat",
                    "Lock audio skipped package=${controller.packageName} state=${state?.state} " +
                        "supportsPlay=$canPlay screenOff=${powerManager?.isInteractive == false} " +
                        "audioNormal=$audioNormal competing=${playingTokens.any { it != session }} " +
                        "sessionStillSelected=${controller.packageName in slotPackages} " +
                        "ageMs=${now - lockedAt}")
                return@forEach
            }
            if (generation != token || !tried.add(session)) return@forEach
            runCatching { controller.transportControls.play() }
                .onSuccess {
                    NeXtepLog.info("playback_compat",
                        "Lock audio one-shot PLAY sent package=${controller.packageName} " +
                            "display=off; activity lifecycle unchanged")
                }.onFailure {
                    NeXtepLog.warn("playback_compat",
                        "Lock audio MediaSession PLAY rejected package=${controller.packageName}", it)
                }
        }
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
        recentControllers = emptyMap()
        slotPackages = emptySet()
        handler.removeCallbacks(sampleVisible)
        releaseLockWakeLock()
    }

    private val sampleVisible: Runnable = object : Runnable {
        override fun run() {
            val setting = config
            if ((!visible && !setting.lockAudioEnabled()) || !setting.active() ||
                (!setting.autoFloatingSlots && setting.packages.isEmpty()) ||
                powerManager?.isInteractive != true) return
            val token = generation
            // When automatic slot protection is enabled, cache only sessions
            // that are actually PLAYING before a task enters the virtual slot.
            // Recovery later remains restricted to packages STILL in slots.
            val tracked = setting.packages + slotPackages
            worker.execute {
                val controllers = sessionsOrNull()
                val at = SystemClock.elapsedRealtime()
                val trackedControllers = controllers.orEmpty()
                    .filter { setting.autoFloatingSlots || it.packageName in tracked }
                    .take(32)
                    .associateBy { it.sessionToken }
                val playing = trackedControllers.values.mapNotNull { controller ->
                    if ((!setting.autoFloatingSlots && controller.packageName !in tracked) ||
                        controller.playbackState?.state != PlaybackState.STATE_PLAYING) null
                    else controller.sessionToken to Playing(controller.packageName, at)
                }.toMap()
                handler.post {
                    if (generation != token ||
                        (!visible && !config.lockAudioEnabled()) ||
                        powerManager?.isInteractive != true) return@post
                    // Preserve short-lived playing history so a session does
                    // not disappear when the user folds between two polls.
                    val manuallyPaused = trackedControllers.values.filter {
                        it.playbackState?.state == PlaybackState.STATE_PAUSED
                    }.map { it.sessionToken }.toSet()
                    lastPlaying = (lastPlaying.filterValues { at - it.at <= 8_000L }
                        .filterKeys { it !in manuallyPaused } + playing)
                    recentControllers = (recentControllers.filterKeys { it in lastPlaying.keys } +
                        trackedControllers.filterKeys { it in lastPlaying.keys })
                    handler.postDelayed(this, if (visible) 1_350L else 2_200L)
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
                enabled = setting.active(),
                autoResume = setting.autoResumeEnabled(),
                appSelected = setting.includes(controller.packageName, slotPackages),
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
