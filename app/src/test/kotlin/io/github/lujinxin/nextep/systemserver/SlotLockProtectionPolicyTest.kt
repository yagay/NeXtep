package io.github.lujinxin.nextep.systemserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotLockProtectionPolicyTest {
    private fun decide(
        enabled: Boolean = true,
        matching: Boolean = true,
        finishing: Boolean = false,
        screenOff: Boolean = false,
        sleeping: Boolean = false,
        prearmed: Boolean = false,
    ) = SlotLockProtectionPolicy.eligible(enabled, matching, finishing, screenOff, sleeping, prearmed)

    @Test fun lockedScreenKeepsLiveSlotProtectedWithoutPrearm() {
        assertTrue(decide(screenOff = true))
        assertTrue(decide(sleeping = true))
    }

    @Test fun earlyArmIsOnlyTransitionFallback() {
        assertTrue(decide(prearmed = true))
        assertFalse(decide())
    }

    @Test fun disabledOrForeignOrFinishingTasksAreNeverProtected() {
        assertFalse(decide(enabled = false, screenOff = true))
        assertFalse(decide(matching = false, screenOff = true))
        assertFalse(decide(finishing = true, screenOff = true))
    }
}
