package io.github.lujinxin.nextep.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMediaCardPolicyTest {
    @Test fun sessionAloneNeverHidesConfirmedDockAudio() {
        assertFalse(NativeMediaCardPolicy.shouldPreferNative(false, NativeMediaCardPolicy.PLAYING))
        assertFalse(NativeMediaCardPolicy.shouldPreferNative(false, NativeMediaCardPolicy.PAUSED))
        assertFalse(NativeMediaCardPolicy.shouldPreferNative(false, null))
    }

    @Test fun genuineActiveMediaNotificationAvoidsDuplicateCard() {
        assertTrue(NativeMediaCardPolicy.shouldPreferNative(true, NativeMediaCardPolicy.PLAYING))
        assertTrue(NativeMediaCardPolicy.shouldPreferNative(true, NativeMediaCardPolicy.PAUSED))
        assertTrue(NativeMediaCardPolicy.shouldPreferNative(true, NativeMediaCardPolicy.BUFFERING))
    }

    @Test fun staleStoppedMediaNotificationDoesNotHidePlayingDockApp() {
        assertFalse(NativeMediaCardPolicy.shouldPreferNative(true, 1))
        assertFalse(NativeMediaCardPolicy.shouldPreferNative(true, null))
    }
}
