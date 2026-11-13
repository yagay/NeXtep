package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPlaybackRecoveryPolicyTest {
    private fun allows(
        enabled: Boolean = true,
        autoResume: Boolean = true,
        appSelected: Boolean = true,
        stillInFloatingSlot: Boolean = true,
        previouslyPlaying: Boolean = true,
        sameSession: Boolean = true,
        nowPaused: Boolean = true,
        supportsPlay: Boolean = true,
        otherMediaPlaying: Boolean = false,
        displayInteractive: Boolean = true,
        keyguardLocked: Boolean = false,
        audioModeNormal: Boolean = true,
        triedAlready: Boolean = false,
        millisecondsSinceFold: Long = 950L,
    ) = FloatingPlaybackRecoveryPolicy.mayRecover(
        enabled, autoResume, appSelected, stillInFloatingSlot, previouslyPlaying, sameSession,
        nowPaused, supportsPlay, otherMediaPlaying, displayInteractive,
        keyguardLocked, audioModeNormal, triedAlready, millisecondsSinceFold,
    )

    @Test fun recentSelfPausedMediaSessionCanRecoverOnce() { assertTrue(allows()) }

    @Test fun neverResumesWithoutExplicitConsentAndSelectedApp() {
        assertFalse(allows(enabled = false))
        assertFalse(allows(autoResume = false))
        assertFalse(allows(appSelected = false))
        assertFalse(allows(stillInFloatingSlot = false))
    }

    @Test fun noMediaSessionOrHistoricalPlaybackMeansNoIntervention() {
        assertFalse(allows(previouslyPlaying = false))
        assertFalse(allows(sameSession = false))
        assertFalse(allows(nowPaused = false))
        assertFalse(allows(supportsPlay = false))
    }

    @Test fun neverInterferesWithCallsLockedDisplayOrAudioFocus() {
        assertFalse(allows(otherMediaPlaying = true))
        assertFalse(allows(audioModeNormal = false))
        assertFalse(allows(displayInteractive = false))
        assertFalse(allows(keyguardLocked = true))
    }

    @Test fun neverLoopsOrResumesLaterManualPause() {
        assertFalse(allows(triedAlready = true))
        assertFalse(allows(millisecondsSinceFold = 3_501L))
        assertFalse(allows(millisecondsSinceFold = -1L))
    }
}
