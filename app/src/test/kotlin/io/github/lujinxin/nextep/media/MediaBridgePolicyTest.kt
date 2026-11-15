package io.github.lujinxin.nextep.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaBridgePolicyTest {
    private fun sample(pkg: String, state: String, time: Long = 1000L) =
        MediaBridgeSnapshot(pkg, state, "audiotrack", null, null, -1L, -1L, false, time)

    @Test fun playingWinsAndOnlyOneCandidateIsPublished() {
        val selected = MediaBridgePolicy.choose(listOf(
            sample("com.demo.paused", "paused", 1200L),
            sample("com.demo.playing", "playing", 900L),
        ), 1300L, emptySet())
        assertEquals("com.demo.playing", selected?.packageName)
    }

    @Test fun nativeSessionsAlwaysSuppressTheirOwnProxy() {
        val selected = MediaBridgePolicy.choose(listOf(
            sample("com.demo.playing", "playing", 1000L),
            sample("com.demo.other", "paused", 1100L),
        ), 1300L, setOf("com.demo.playing"))
        assertEquals("com.demo.other", selected?.packageName)
    }

    @Test fun staleEventsNeverRemainInSystemMedia() {
        assertNull(MediaBridgePolicy.choose(listOf(
            sample("com.demo.stale", "playing", 1000L),
            sample("com.demo.stale2", "paused", 1000L),
        ), 1000L + MediaBridgePolicy.PAUSED_STALE_MS + 1L, emptySet()))
    }

    @Test fun stoppedAndFutureReportsAreIgnored() {
        assertNull(MediaBridgePolicy.choose(listOf(
            sample("com.demo.one", "stopped", 1000L),
            sample("com.demo.two", "playing", 4000L),
        ), 2000L, emptySet()))
    }
}
