package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertFalse
import org.junit.Test

class PinnedHomeTransitionPolicyTest {
    @Test fun homeNeverAutoParksPinnedTask() {
        assertFalse(PinnedHomeTransitionPolicy.shouldAutoParkOnHome(true, true))
        assertFalse(PinnedHomeTransitionPolicy.isPinnedHomePark(true, "home"))
    }

    @Test fun otherTransitionsAreNotMislabelledAsHome() {
        assertFalse(PinnedHomeTransitionPolicy.shouldAutoParkOnHome(true, false))
        assertFalse(PinnedHomeTransitionPolicy.isPinnedHomePark(true, "background"))
        assertFalse(PinnedHomeTransitionPolicy.isPinnedHomePark(true, "lock"))
    }

    @Test fun unpinnedTaskNeverTriggersHomePark() {
        assertFalse(PinnedHomeTransitionPolicy.shouldAutoParkOnHome(false, true))
        assertFalse(PinnedHomeTransitionPolicy.isPinnedHomePark(false, "home"))
    }
}
