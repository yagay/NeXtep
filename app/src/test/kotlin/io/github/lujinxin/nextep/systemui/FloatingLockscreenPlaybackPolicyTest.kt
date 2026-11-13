package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLockscreenPlaybackPolicyTest {
    private fun recover(
        enabled: Boolean = true,
        optedIn: Boolean = true,
        appSelected: Boolean = true,
        stillInFloatingSlot: Boolean = true,
        previouslyPlaying: Boolean = true,
        sameSession: Boolean = true,
        paused: Boolean = true,
        supportsPlay: Boolean = true,
        screenOff: Boolean = true,
        audioModeNormal: Boolean = true,
        otherMediaPlaying: Boolean = false,
        alreadyAttempted: Boolean = false,
        millisecondsSinceLock: Long = 950L,
    ) = FloatingLockscreenPlaybackPolicy.mayResume(
        enabled, optedIn, appSelected, stillInFloatingSlot, previouslyPlaying,
        sameSession, paused, supportsPlay, screenOff, audioModeNormal,
        otherMediaPlaying, alreadyAttempted, millisecondsSinceLock,
    )

    @Test fun previouslyPlayingSelectedSlotMayResumeOnScreenOffOnce() {
        assertTrue(recover())
    }

    @Test fun neverPlaysWithoutExplicitSelectionAndOptIn() {
        assertFalse(recover(enabled = false))
        assertFalse(recover(optedIn = false))
        assertFalse(recover(appSelected = false))
        assertFalse(recover(stillInFloatingSlot = false))
    }

    @Test fun neverStartsAnIdleOrAlreadyStoppedPlayer() {
        assertFalse(recover(previouslyPlaying = false))
        assertFalse(recover(sameSession = false))
        assertFalse(recover(paused = false))
        assertFalse(recover(supportsPlay = false))
    }

    @Test fun noCallsCompetingAudioOrManualFollowUp() {
        assertFalse(recover(audioModeNormal = false))
        assertFalse(recover(otherMediaPlaying = true))
        assertFalse(recover(alreadyAttempted = true))
    }

    @Test fun doesNotResumeWhenDisplayIsInteractiveOrHoursLater() {
        assertFalse(recover(screenOff = false))
        assertFalse(recover(millisecondsSinceLock = -1L))
        assertFalse(recover(millisecondsSinceLock = 5_001L))
    }
}
