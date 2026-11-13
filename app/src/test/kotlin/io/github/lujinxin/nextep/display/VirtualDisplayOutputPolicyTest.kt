package io.github.lujinxin.nextep.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayOutputPolicyTest {
    @Test fun originalWorkspaceStillDisconnectsAtLock() {
        assertFalse(VirtualDisplayOutputPolicy.connectedOnSuspend(false))
        assertFalse(VirtualDisplayOutputPolicy.connectedDuringSuspend(false, screenAwake = false))
        assertTrue(VirtualDisplayOutputPolicy.connectedDuringSuspend(false, screenAwake = true))
    }

    @Test fun floatingWorkspaceKeepsItsOutputDuringLock() {
        assertTrue(VirtualDisplayOutputPolicy.connectedOnSuspend(true))
        assertTrue(VirtualDisplayOutputPolicy.connectedDuringSuspend(true, screenAwake = false))
        assertTrue(VirtualDisplayOutputPolicy.connectedDuringSuspend(true, screenAwake = true))
    }

    @Test fun hidingFloatingDockDoesNotRequireSuspension() {
        // The hidden floating dock normally leaves the coordinator active
        // and its VirtualDisplay surfaces connected. Even explicit lock
        // suspension cannot disconnect an opted-in floating output.
        for (screenAwake in listOf(false, true)) {
            assertTrue(VirtualDisplayOutputPolicy.connectedDuringSuspend(true, screenAwake))
        }
    }
}
