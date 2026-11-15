package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedHomeTransitionPolicyTest {
    @Test fun homeLeavesPinnedTaskOnMainDisplay() {
        assertTrue(PinnedHomeTransitionPolicy.shouldKeepOnMain(true, true))
    }

    @Test fun otherAppSwitchStillUsesExistingAutoReturn() {
        assertFalse(PinnedHomeTransitionPolicy.shouldKeepOnMain(true, false))
    }

    @Test fun unpinnedAppKeepsExistingPolicy() {
        assertFalse(PinnedHomeTransitionPolicy.shouldKeepOnMain(false, true))
    }
}
