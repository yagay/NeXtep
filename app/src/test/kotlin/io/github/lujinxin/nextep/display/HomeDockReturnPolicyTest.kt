package io.github.lujinxin.nextep.display

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDockReturnPolicyTest {
    private fun choose(
        pinned: Boolean = true, identity: Boolean = true,
        fullscreen: Boolean = true, age: Long = 1_000L,
        observed: Long? = null, attempts: Int = 0,
    ) = HomeDockReturnPolicy.decide(
        pinned, identity, fullscreen, age, observed, attempts)

    @Test fun observesBeforeRecovering() {
        assertEquals(HomeDockReturnPolicy.Decision.OBSERVE, choose())
        assertEquals(HomeDockReturnPolicy.Decision.WAIT, choose(observed = 200L))
        assertEquals(HomeDockReturnPolicy.Decision.RESTORE, choose(observed = 451L))
    }

    @Test fun protectsSecondSpontaneousPromotionWithinWindow() {
        assertEquals(HomeDockReturnPolicy.Decision.RESTORE,
            choose(age = 14_000L, observed = 650L, attempts = 1))
    }

    @Test fun neverLoopsForeverOrAfterProtectionWindow() {
        assertEquals(HomeDockReturnPolicy.Decision.RELEASE,
            choose(observed = 1000L, attempts = HomeDockReturnPolicy.MAX_RETURNS))
        assertEquals(HomeDockReturnPolicy.Decision.RELEASE,
            choose(age = 46_000L, observed = 1000L))
        assertEquals(HomeDockReturnPolicy.Decision.RELEASE,
            choose(age = -1L, observed = 1000L))
    }

    @Test fun nonPinnedChangedIdentityOrFreeformIsNeverCaptured() {
        assertEquals(HomeDockReturnPolicy.Decision.RELEASE, choose(pinned = false))
        assertEquals(HomeDockReturnPolicy.Decision.RELEASE, choose(identity = false))
        assertEquals(HomeDockReturnPolicy.Decision.RELEASE, choose(fullscreen = false))
    }
}
