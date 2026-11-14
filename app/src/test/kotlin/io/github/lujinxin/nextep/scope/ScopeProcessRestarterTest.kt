package io.github.lujinxin.nextep.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeProcessRestarterTest {
    @Test fun onlyKnownNoncriticalProcessesAreEligible() {
        val allowed = ScopeProcessRestarter.Target.entries.filter { it.canRestart }
        assertEquals(5, allowed.size)
        assertEquals(
            setOf(
                "com.android.systemui",
                "com.oplus.launcher",
                "com.android.launcher",
                "com.oplus.multiapp",
                "com.coloros.assistantscreen",
            ),
            allowed.mapNotNull { it.processName }.toSet(),
        )
        assertTrue(allowed.all { it.processName?.matches(Regex("[a-z0-9.]+")) == true })
    }

    @Test fun coreSystemServerIsNeverRestartableIndividually() {
        val framework = ScopeProcessRestarter.Target.SYSTEM_FRAMEWORK
        assertFalse(framework.canRestart)
        assertEquals(null, framework.processName)
    }
}
