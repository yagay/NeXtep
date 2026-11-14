package io.github.lujinxin.nextep.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBubbleWakePolicyTest {
    private val policy = FloatingBubbleWakePolicy

    @Test fun screenOffHidesWithoutRemovingOverlay() {
        assertEquals(FloatingBubbleWakePolicy.Action.HIDE, policy.decide(
            FloatingBubbleWakePolicy.Event.SCREEN_OFF, interactive = false, enabled = true, overlayAllowed = true,
        ))
    }

    @Test fun screenOnRestoresBeforeUserHasUnlocked() {
        assertEquals(FloatingBubbleWakePolicy.Action.SHOW, policy.decide(
            FloatingBubbleWakePolicy.Event.SCREEN_ON, interactive = true, enabled = true, overlayAllowed = true,
        ))
        assertEquals(FloatingBubbleWakePolicy.Action.WAIT_FOR_SCREEN, policy.decide(
            FloatingBubbleWakePolicy.Event.SCREEN_ON, interactive = false, enabled = true, overlayAllowed = true,
        ))
    }

    @Test fun userPresentRetriesAfterWake() {
        assertEquals(FloatingBubbleWakePolicy.Action.SHOW, policy.decide(
            FloatingBubbleWakePolicy.Event.USER_PRESENT, interactive = true, enabled = true, overlayAllowed = true,
        ))
    }

    @Test fun noPermissionOrDisabledMeansRemove() {
        for (event in FloatingBubbleWakePolicy.Event.entries) {
            assertEquals(FloatingBubbleWakePolicy.Action.REMOVE, policy.decide(
                event, interactive = true, enabled = false, overlayAllowed = true,
            ))
            assertEquals(FloatingBubbleWakePolicy.Action.REMOVE, policy.decide(
                event, interactive = true, enabled = true, overlayAllowed = false,
            ))
        }
    }

    @Test fun taskDockCanNeverOpenOnKeyguard() {
        assertFalse(policy.mayOpenTaskDock(keyguardLocked = true, interactive = true))
        assertFalse(policy.mayOpenTaskDock(keyguardLocked = false, interactive = false))
        assertTrue(policy.mayOpenTaskDock(keyguardLocked = false, interactive = true))
    }
}
