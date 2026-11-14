package io.github.lujinxin.nextep.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationWindowClassifierTest {
    private val homes = setOf("com.android.launcher")

    @Test fun recognizesHomeWindow() {
        assertEquals("home", NavigationWindowClassifier.classify(
            "com.android.launcher", "com.android.launcher3.Launcher", homes))
    }

    @Test fun recognizesRecentTasksFromLauncherOrSystemUi() {
        assertEquals("recents", NavigationWindowClassifier.classify(
            "com.android.launcher", "com.android.quickstep.RecentsActivity", homes))
        assertEquals("recents", NavigationWindowClassifier.classify(
            "com.android.systemui", "com.android.systemui.recents.RecentsActivity", homes))
    }

    @Test fun ignoresUnrelatedOrSpoofedWindows() {
        assertNull(NavigationWindowClassifier.classify(
            "com.example.player", "com.example.player.OverviewScreen", homes))
        assertNull(NavigationWindowClassifier.classify(
            "com.example.player", "com.example.player.VideoActivity", homes))
    }
}
