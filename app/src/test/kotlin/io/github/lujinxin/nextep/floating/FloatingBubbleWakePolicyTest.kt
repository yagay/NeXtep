package io.github.lujinxin.nextep.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBubbleWakePolicyTest {
    private val policy = FloatingBubbleWakePolicy

    @Test fun screenOffHidesWithoutRemovingOverlay() {
        assertEquals(policy.Action.HIDE, policy.decide(
            policy.Event.SCREEN_OFF, interactive = false, enabled = true, overlayAllowed = true,
        ))
    }

    @Test fun screenOnRestoresBeforeUserHasUnlocked() {
        assertEquals(policy.Action.SHOW, policy.decide(
            policy.Event.SCREEN_ON, interactive = true, enabled = true, overlayAllowed = true,
        ))
        assertEquals(policy.Action.WAIT_FOR_SCREEN, policy.decide(
            policy.Event.SCREEN_ON, interactive = false, enabled = true, overlayAllowed = true,
        ))
    }

    @Test fun userPresentRetriesAfterWake() {
        assertEquals(policy.Action.SHOW, policy.decide(
            policy.Event.USER_PRESENT, interactive = true, enabled = true, overlayAllowed = true,
        ))
    }

    @Test fun noPermissionOrDisabledMeansRemove() {
        for (event in policy.Event.entries) {
            assertEquals(policy.Action.REMOVE, policy.decide(
                event, interactive = true, enabled = false, overlayAllowed = true,
            ))
            assertEquals(policy.Action.REMOVE, policy.decide(
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
