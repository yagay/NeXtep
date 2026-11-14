package io.github.lujinxin.nextep.systemui

/**
 * Explicit user-initiated, audio-only continuation. Never shows task video
 * on keyguard or changes Android activity visibility / audio-focus ownership.
 */
internal object FloatingLockscreenPlaybackPolicy {
    fun mayResume(
        enabled: Boolean,
        optedIn: Boolean,
        appSelected: Boolean,
        stillInFloatingSlot: Boolean,
        previouslyPlaying: Boolean,
        sameSession: Boolean,
        paused: Boolean,
        supportsPlay: Boolean,
        screenOff: Boolean,
        audioModeNormal: Boolean,
        otherMediaPlaying: Boolean,
        alreadyAttempted: Boolean,
        millisecondsSinceLock: Long,
    ): Boolean = enabled && optedIn && appSelected && stillInFloatingSlot &&
        previouslyPlaying && sameSession && paused && supportsPlay &&
        screenOff && audioModeNormal && !otherMediaPlaying && !alreadyAttempted &&
        millisecondsSinceLock in 0L..5_000L
}
