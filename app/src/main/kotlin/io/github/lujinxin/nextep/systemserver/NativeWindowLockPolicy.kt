package io.github.lujinxin.nextep.systemserver

/**
 * Pure, fail-closed eligibility for an OEM native window during lock.
 * Package name alone can never qualify; both task identity and application
 * UID must match the SystemUI-confirmed identity.
 */
internal object NativeWindowLockPolicy {
    fun eligible(
        enabled: Boolean,
        autoDisabled: Boolean,
        expectedTaskId: Int,
        actualTaskId: Int,
        expectedUid: Int,
        actualUid: Int,
        displayId: Int,
        windowMode: Int?,
        vendorScenario: Int?,
        physicallyOff: Boolean,
        lockPrearmed: Boolean,
        finishing: Boolean,
        verifiedLockSession: Boolean = false,
    ): Boolean =
        enabled && !autoDisabled && !finishing &&
            expectedTaskId > 0 && actualTaskId == expectedTaskId &&
            expectedUid >= 10000 && actualUid == expectedUid &&
            displayId == 0 && (physicallyOff || lockPrearmed) &&
            (windowMode == 100 || vendorScenario == 1 || vendorScenario == 2 ||
                verifiedLockSession)
}
