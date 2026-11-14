package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingSlotPlaybackScopeTest {
    @Test fun automaticModeProtectsOnlyAppsPresentInFloatingSlots() {
        assertTrue(FloatingSlotPlaybackScope.isEligible(
            "com.example.player", false, emptySet(), true, setOf("com.example.player")))
        assertFalse(FloatingSlotPlaybackScope.isEligible(
            "com.example.other", false, emptySet(), true, setOf("com.example.player")))
    }

    @Test fun removingAppFromSlotRevokesAutomaticScope() {
        assertFalse(FloatingSlotPlaybackScope.isEligible(
            "com.example.player", false, emptySet(), true, emptySet()))
    }

    @Test fun disablingAutoProtectionRespectsManualSelection() {
        assertTrue(FloatingSlotPlaybackScope.isEligible(
            "com.example.player", true, setOf("com.example.player"), false, emptySet()))
        assertFalse(FloatingSlotPlaybackScope.isEligible(
            "com.example.other", true, setOf("com.example.player"), false, setOf("com.example.other")))
        assertFalse(FloatingSlotPlaybackScope.enabled(false, false))
    }
}
