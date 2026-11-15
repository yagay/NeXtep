package io.github.lujinxin.nextep.startup

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemStartupPolicyTest {
    @Test fun enabledFeaturesAndPermissionsRestoreTogether() {
        assertEquals(SystemStartupPolicy.Plan(true, true),
            SystemStartupPolicy.plan(true, true, true, true, true, true))
    }

    @Test fun disabledAutostartNeverStartsEitherComponent() {
        assertEquals(SystemStartupPolicy.Plan(false, false),
            SystemStartupPolicy.plan(false, true, true, true, true, true))
    }

    @Test fun lockedUserDefersCredentialProtectedSettings() {
        assertEquals(SystemStartupPolicy.Plan(false, false),
            SystemStartupPolicy.plan(true, false, true, true, true, true))
    }

    @Test fun eachFeatureKeepsItsOwnEnableAndPermissionGate() {
        assertEquals(SystemStartupPolicy.Plan(false, true),
            SystemStartupPolicy.plan(true, true, false, true, true, true))
        assertEquals(SystemStartupPolicy.Plan(true, false),
            SystemStartupPolicy.plan(true, true, true, true, true, false))
        assertEquals(SystemStartupPolicy.Plan(false, false),
            SystemStartupPolicy.plan(true, true, true, false, false, true))
    }
}
