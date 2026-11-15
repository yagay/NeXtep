package io.github.lujinxin.nextep.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedTaskIdentityContractTest {
    private val pin = PinnedTaskIdentityContract.Identity(1, 123, 10123, 0, 6, "com.example.player")

    @Test fun samePinnedTaskRemainsEligibleWhenMovedToPhysicalDisplay() {
        val restored = PinnedTaskIdentityContract.decode(
            PinnedTaskIdentityContract.encode(listOf(pin)), 6)[123]
        assertEquals(pin, restored)
        assertTrue(PinnedTaskIdentityContract.matches(restored, 123, 10123, "com.example.player", 0, null))
        assertTrue(PinnedTaskIdentityContract.matches(restored, 123, 10123, "com.example.player", 42, 1))
    }

    @Test fun unrelatedAppsDisplaysAndBootsAreNotProtected() {
        assertFalse(PinnedTaskIdentityContract.matches(pin, 124, 10123, "com.example.player", 0, null))
        assertFalse(PinnedTaskIdentityContract.matches(pin, 123, 10001, "com.example.player", 0, null))
        assertFalse(PinnedTaskIdentityContract.matches(pin, 123, 10123, "com.example.other", 0, null))
        assertFalse(PinnedTaskIdentityContract.matches(pin, 123, 10123, "com.example.player", 42, 2))
        assertFalse(PinnedTaskIdentityContract.matches(pin, 123, 10123, "com.example.player", 42, null))
        assertTrue(PinnedTaskIdentityContract.decode(PinnedTaskIdentityContract.encode(listOf(pin)), 7).isEmpty())
    }

    @Test fun malformedAndDuplicateRegistrationsFailClosed() {
        assertTrue(PinnedTaskIdentityContract.decode("1:123:10123:0:6:com.example.player;1:124:10123:0:6:com.example.player", 6).isEmpty())
        assertTrue(PinnedTaskIdentityContract.decode("1:123:10123:0:6:com.example.player;2:123:10123:0:6:com.example.player", 6).isEmpty())
        assertTrue(PinnedTaskIdentityContract.decode("1:123:10123:0:6:com.example.player;broken", 6).isEmpty())
        assertEquals("", PinnedTaskIdentityContract.encode(listOf(pin.copy(uid=1000))))
    }
}
