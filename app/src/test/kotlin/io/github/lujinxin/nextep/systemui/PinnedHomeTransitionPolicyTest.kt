package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedHomeTransitionPolicyTest {
    @Test fun homeAutoParksPinnedTaskIntoReservedSlot() {
        assertTrue(PinnedHomeTransitionPolicy.shouldAutoParkOnHome(true, true))
        assertTrue(PinnedHomeTransitionPolicy.isPinnedHomePark(true, "home"))
    }

    @Test fun unrelatedForegroundChangesDoNotLaunchHome() {
        assertFalse(PinnedHomeTransitionPolicy.shouldAutoParkOnHome(true, false))
        assertFalse(PinnedHomeTransitionPolicy.isPinnedHomePark(true, "background"))
        assertFalse(PinnedHomeTransitionPolicy.isPinnedHomePark(true, "lock"))
    }

    @Test fun unpinnedTasksRetainExistingAutoParkBehavior() {
        assertFalse(PinnedHomeTransitionPolicy.shouldAutoParkOnHome(false, true))
        assertFalse(PinnedHomeTransitionPolicy.isPinnedHomePark(false, "home"))
    }
}
