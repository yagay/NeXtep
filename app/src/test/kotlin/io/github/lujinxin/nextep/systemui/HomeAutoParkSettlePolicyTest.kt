package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAutoParkSettlePolicyTest {
    private fun decide(
        home: Boolean = true, sameHomeTask: Boolean = true,
        onMain: Boolean = true, exact: Boolean = true, checks: Int = 0,
    ) = HomeAutoParkSettlePolicy.decide(home, sameHomeTask, onMain, exact, checks)

    @Test fun homeNeedsTwoStableSamplesBeforeTaskMove() {
        assertEquals(HomeAutoParkSettlePolicy.Decision.WAIT, decide(checks = 0))
        assertEquals(HomeAutoParkSettlePolicy.Decision.WAIT, decide(checks = 1))
        assertEquals(HomeAutoParkSettlePolicy.Decision.PARK, decide(checks = 2))
    }

    @Test fun abandonWhenUserLeavesLauncherOrTaskHasChanged() {
        assertEquals(HomeAutoParkSettlePolicy.Decision.CANCEL, decide(home = false))
        assertEquals(HomeAutoParkSettlePolicy.Decision.CANCEL, decide(sameHomeTask = false))
        assertEquals(HomeAutoParkSettlePolicy.Decision.CANCEL, decide(onMain = false))
        assertEquals(HomeAutoParkSettlePolicy.Decision.CANCEL, decide(exact = false))
    }
}
