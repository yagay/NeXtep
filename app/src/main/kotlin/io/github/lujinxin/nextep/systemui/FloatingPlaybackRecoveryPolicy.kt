package io.github.lujinxin.nextep.systemui

/** Conservative, testable guard for a single MediaSession auto-resume attempt. */
internal object FloatingPlaybackRecoveryPolicy {
    fun mayRecover(
        enabled: Boolean,
        autoResume: Boolean,
        appSelected: Boolean,
        stillInFloatingSlot: Boolean,
        previouslyPlaying: Boolean,
        sameSession: Boolean,
        nowPaused: Boolean,
        supportsPlay: Boolean,
        otherMediaPlaying: Boolean,
        displayInteractive: Boolean,
        keyguardLocked: Boolean,
        audioModeNormal: Boolean,
        triedAlready: Boolean,
        millisecondsSinceFold: Long,
    ): Boolean = enabled && autoResume && appSelected && stillInFloatingSlot &&
        previouslyPlaying && sameSession && nowPaused && supportsPlay && !otherMediaPlaying &&
        displayInteractive && !keyguardLocked && audioModeNormal && !triedAlready &&
        millisecondsSinceFold in 0L..3_500L
}
