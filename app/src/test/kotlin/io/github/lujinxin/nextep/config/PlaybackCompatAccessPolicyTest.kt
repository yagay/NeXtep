package io.github.lujinxin.nextep.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCompatAccessPolicyTest {
    private val selected = setOf("com.example.player")
    private val callers = setOf("com.example.player")

    @Test fun scopedAndSelectedPlayerMayReadOnlyItsOwnBoolean() {
        assertTrue(PlaybackCompatAccessPolicy.mayReadOwnOptIn(
            "com.example.player", callers, true, selected))
    }

    @Test fun arbitraryAppCannotEnumerateOrImpersonateAnotherPackage() {
        assertFalse(PlaybackCompatAccessPolicy.mayReadOwnOptIn(
            "com.example.player", setOf("com.example.malicious"), true, selected))
        assertFalse(PlaybackCompatAccessPolicy.mayReadOwnOptIn(
            "com.example.malicious", callers, true, selected))
        assertFalse(PlaybackCompatAccessPolicy.mayReadOwnOptIn(
            "com.example.player", callers, false, selected))
    }

    @Test fun invalidPackageAndUnsetPreferencesFailClosed() {
        assertFalse(PlaybackCompatAccessPolicy.mayReadOwnOptIn(null, callers, true, selected))
        assertFalse(PlaybackCompatAccessPolicy.mayReadOwnOptIn("bad/package", callers, true, selected))
        assertFalse(PlaybackCompatAccessPolicy.mayReadOwnOptIn(
            "com.example.player", callers, true, emptySet()))
    }
}
