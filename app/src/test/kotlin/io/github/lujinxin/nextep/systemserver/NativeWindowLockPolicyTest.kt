package io.github.lujinxin.nextep.systemserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWindowLockPolicyTest {
    private fun check(
        enabled: Boolean = true,
        autoDisabled: Boolean = false,
        expectedTaskId: Int = 26336,
        actualTaskId: Int = 26336,
        expectedUid: Int = 10123,
        actualUid: Int = 10123,
        displayId: Int = 0,
        windowMode: Int? = 1,
        vendorScenario: Int? = 1,
        physicallyOff: Boolean = true,
        lockPrearmed: Boolean = false,
        finishing: Boolean = false,
        verifiedLockSession: Boolean = false,
    ) = NativeWindowLockPolicy.eligible(
        enabled, autoDisabled, expectedTaskId, actualTaskId,
        expectedUid, actualUid, displayId, windowMode, vendorScenario,
        physicallyOff, lockPrearmed, finishing, verifiedLockSession
    )

    @Test fun verifiedNativeTaskCanBeProtectedWhileScreenOff() {
        assertTrue(check())
        assertTrue(check(windowMode = 100, vendorScenario = 0))
        assertTrue(check(physicallyOff = false, lockPrearmed = true))
    }

    @Test fun verifiedNativeWindowRetainsProtectionAfterOemModeDemotion() {
        assertTrue(check(windowMode = 1, vendorScenario = 0,
            verifiedLockSession = true))
        assertFalse(check(windowMode = 1, vendorScenario = 0,
            verifiedLockSession = false))
        assertFalse(check(windowMode = 1, vendorScenario = 0,
            verifiedLockSession = true, actualUid = 20234))
        assertFalse(check(windowMode = 1, vendorScenario = 0,
            verifiedLockSession = true, actualTaskId = 26337))
        assertFalse(check(windowMode = 1, vendorScenario = 0,
            verifiedLockSession = true, physicallyOff = false))
    }

    @Test fun neverProtectUnrelatedTaskOrUid() {
        assertFalse(check(actualTaskId = 26337))
        assertFalse(check(actualUid = 10124))
        assertFalse(check(expectedUid = 999))
        assertFalse(check(displayId = 7))
        assertFalse(check(windowMode = 1, vendorScenario = 0))
    }

    @Test fun userPolicyAndSafetyConditionsFailClosed() {
        assertFalse(check(enabled = false))
        assertFalse(check(autoDisabled = true))
        assertFalse(check(physicallyOff = false, lockPrearmed = false))
        assertFalse(check(finishing = true))
    }
}
