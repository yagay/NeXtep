package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSlotTransitionPolicyTest {
    @Test fun homeNeverMovesAppEvenIfAutoAndPinnedAreEnabled() {
        assertFalse(AutoSlotTransitionPolicy.permitsAutomaticMove(
            enabled = true, selected = true, nextIsHome = true))
    }

    @Test fun pinAloneIsNotAutomaticMoveConsent() {
        assertFalse(AutoSlotTransitionPolicy.permitsAutomaticMove(
            enabled = false, selected = true, nextIsHome = false))
    }

    @Test fun automaticMoveRequiresSelectedPackage() {
        assertFalse(AutoSlotTransitionPolicy.permitsAutomaticMove(
            enabled = true, selected = false, nextIsHome = false))
    }

    @Test fun explicitlySelectedAppCanMoveOnOtherAppTransition() {
        assertTrue(AutoSlotTransitionPolicy.permitsAutomaticMove(
            enabled = true, selected = true, nextIsHome = false))
    }
}
