package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingDockSessionPolicyTest {
    private val policy = FloatingDockSessionPolicy

    @Test fun normalCollapseOnlyHidesExistingTaskDisplays() {
        assertFalse(policy.shouldReleaseDisplays(
            FloatingDockSessionPolicy.CloseReason.USER_HIDE,
        ))
    }

    @Test fun lockingScreenDoesNotDestroyRunningApps() {
        assertFalse(policy.shouldReleaseDisplays(
            FloatingDockSessionPolicy.CloseReason.SCREEN_LOCK,
        ))
    }

    @Test fun disablingFeatureParksTasksAndReleasesDisplaysSafely() {
        assertTrue(policy.shouldReleaseDisplays(
            FloatingDockSessionPolicy.CloseReason.FEATURE_DISABLED,
        ))
    }

    @Test fun originalWorkspaceAndSlotTestsMustNotShareFloatingDisplays() {
        assertTrue(policy.shouldReleaseDisplays(
            FloatingDockSessionPolicy.CloseReason.OPEN_ORIGINAL_WORKSPACE,
        ))
        assertTrue(policy.shouldReleaseDisplays(
            FloatingDockSessionPolicy.CloseReason.SLOT_TEST_SWITCH,
        ))
    }
}
