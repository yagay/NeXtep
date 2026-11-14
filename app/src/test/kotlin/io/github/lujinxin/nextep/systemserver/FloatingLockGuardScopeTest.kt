package io.github.lujinxin.nextep.systemserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLockGuardScopeTest {
    @Test fun acceptsOnlySystemUiOwnedFloatingSlots() {
        assertTrue(FloatingLockGuardScope.isEligibleDisplay(
            24, "NeXtep-floating-slot-0", 5, 1000, "com.android.systemui"))
        assertTrue(FloatingLockGuardScope.isEligibleDisplay(
            25, "NeXtep-floating-slot-3", 5, null, "com.android.systemui"))
    }

    @Test fun ordinaryWorkspaceAndOtherVirtualDisplaysRemainOutsideGuard() {
        assertFalse(FloatingLockGuardScope.isEligibleDisplay(
            24, "NeXtep-slot-0", 5, 1000, "com.android.systemui"))
        assertFalse(FloatingLockGuardScope.isEligibleDisplay(
            26, "NeXtep-floating-slot-4", 5, 1000, "com.android.systemui"))
        assertFalse(FloatingLockGuardScope.isEligibleDisplay(
            2, "OtherApp-virtual-display", 5, 1000, "com.android.systemui"))
    }

    @Test fun rejectsPhysicalOrUntrustedDisplayOwners() {
        assertFalse(FloatingLockGuardScope.isEligibleDisplay(
            0, "NeXtep-floating-slot-0", 5, 1000, "com.android.systemui"))
        assertFalse(FloatingLockGuardScope.isEligibleDisplay(
            20, "NeXtep-floating-slot-0", 1, 1000, "com.android.systemui"))
        assertFalse(FloatingLockGuardScope.isEligibleDisplay(
            21, "NeXtep-floating-slot-0", 5, 10234, "com.other.app"))
    }
}
