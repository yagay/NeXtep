package io.github.lujinxin.nextep.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPadFocusPolicyTest {
    private val all = setOf(
        "TRUSTED", "OWN_FOCUS", "OWN_DISPLAY_GROUP", "STEAL_TOP_FOCUS_DISABLED",
        "ALWAYS_UNLOCKED",
    )

    @Test fun standardModePreservesExistingNonStealingFocusPreference() {
        val candidates = FloatingPadFocusPolicy.candidates(all, padMode = false)
        assertEquals("STEAL_TOP_FOCUS_DISABLED", candidates.first().last())
        assertTrue("OWN_FOCUS" in candidates.first())
        assertEquals(listOf("OWN_CONTENT_ONLY", "PRESENTATION"), candidates.last())
    }

    @Test fun padModePrefersIndependentFocusWithoutBlockingIME() {
        val candidates = FloatingPadFocusPolicy.candidates(all, padMode = true)
        assertTrue("TRUSTED" in candidates.first())
        assertTrue("OWN_FOCUS" in candidates.first())
        assertTrue("OWN_DISPLAY_GROUP" in candidates.first())
        assertFalse("STEAL_TOP_FOCUS_DISABLED" in candidates.first())
        assertTrue(candidates.any { "STEAL_TOP_FOCUS_DISABLED" in it })
        assertEquals(listOf("OWN_CONTENT_ONLY", "PRESENTATION"), candidates.last())
    }

    @Test fun padModeFallsBackWhenVendorRejectsDisplayGroup() {
        val candidates = FloatingPadFocusPolicy.candidates(all, padMode = true)
        assertTrue(candidates.any {
            "OWN_FOCUS" in it && "OWN_DISPLAY_GROUP" !in it
        })
        assertEquals(candidates.distinct(), candidates)
    }

    @Test fun unsupportedFocusNeverRequestedWithoutTrustedDisplay() {
        val candidates = FloatingPadFocusPolicy.candidates(
            setOf("OWN_FOCUS", "STEAL_TOP_FOCUS_DISABLED"), padMode = true,
        )
        assertTrue(candidates.all { "OWN_FOCUS" !in it })
        assertTrue(candidates.all { "STEAL_TOP_FOCUS_DISABLED" !in it })
    }

    @Test fun unavailableOEMFlagsKeepSimpleVirtualDisplayFallback() {
        assertEquals(
            listOf(listOf("OWN_CONTENT_ONLY", "PRESENTATION")),
            FloatingPadFocusPolicy.candidates(emptySet(), padMode = true),
        )
    }

    @Test fun padModeTriesIsolatedUnlockedDisplayBeforeLegacyFallback() {
        val candidates = FloatingPadFocusPolicy.candidates(all, padMode = true)
        assertTrue("ALWAYS_UNLOCKED" in candidates.first())
        assertTrue("OWN_DISPLAY_GROUP" in candidates.first())
        assertTrue("TRUSTED" in candidates.first())
        assertTrue(candidates.drop(1).all { "ALWAYS_UNLOCKED" !in it })
        assertEquals(listOf("OWN_CONTENT_ONLY", "PRESENTATION"), candidates.last())
    }

    @Test fun standardModeNeverRequestsUnlockedDisplay() {
        val candidates = FloatingPadFocusPolicy.candidates(all, padMode = false)
        assertTrue(candidates.all { "ALWAYS_UNLOCKED" !in it })
    }

    @Test fun cannotRequestUnlockedDisplayWithoutOwnDisplayGroup() {
        val candidates = FloatingPadFocusPolicy.candidates(
            all - "OWN_DISPLAY_GROUP", padMode = true,
        )
        assertTrue(candidates.all { "ALWAYS_UNLOCKED" !in it })
    }
}
