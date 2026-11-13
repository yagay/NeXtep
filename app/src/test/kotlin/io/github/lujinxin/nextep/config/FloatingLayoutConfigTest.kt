package io.github.lujinxin.nextep.config

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingLayoutConfigTest {
    @Test fun normalizeOrientationOnlyAllowsHorizontalOrVertical() {
        assertEquals("horizontal", WorkspaceConfigContract.normalizeFloatingDockOrientation(null))
        assertEquals("horizontal", WorkspaceConfigContract.normalizeFloatingDockOrientation("diagonal"))
        assertEquals("horizontal", WorkspaceConfigContract.normalizeFloatingDockOrientation("horizontal"))
        assertEquals("vertical", WorkspaceConfigContract.normalizeFloatingDockOrientation("vertical"))
    }

    @Test fun sizePresetsAreBoundedAndMatchTheLiveDock() {
        assertEquals(60, WorkspaceConfigContract.normalizeFloatingDockSizePercent(20))
        assertEquals(140, WorkspaceConfigContract.normalizeFloatingDockSizePercent(1000))
        assertEquals(100, WorkspaceConfigContract.normalizeFloatingDockSizePercent(100))
        assertEquals(90, WorkspaceConfigContract.normalizeFloatingDockSizePercent(99))
        for (scale in 60..140 step 10) {
            assertEquals(scale, WorkspaceConfigContract.normalizeFloatingDockSizePercent(scale))
        }
    }
}
