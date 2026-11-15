package io.github.lujinxin.nextep.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockMediaMatchPolicyTest {
    @Test fun onlyAppsInsideDockAreEligible() {
        val dock = listOf("com.example.video" to 10234, "com.example.other" to 10235)
        assertEquals(setOf("com.example.video"),
            DockMediaMatchPolicy.audiblePackages(dock, setOf(10234, 10666)))
    }

    @Test fun noAudibleUidMeansNoSyntheticMediaCard() {
        assertTrue(DockMediaMatchPolicy.audiblePackages(
            listOf("com.example.video" to 10234), emptySet()).isEmpty())
    }

    @Test fun sameAppInAnotherAndroidUserIsNotConfused() {
        val dock = listOf("com.example.clone" to 1010234)
        assertTrue(DockMediaMatchPolicy.audiblePackages(dock, setOf(10234)).isEmpty())
        assertEquals(setOf("com.example.clone"),
            DockMediaMatchPolicy.audiblePackages(dock, setOf(1010234)))
    }

    @Test fun systemAudioUidCannotBecomeAnApp() {
        assertTrue(DockMediaMatchPolicy.audiblePackages(
            listOf("android" to 1000), setOf(1000)).isEmpty())
    }
}
