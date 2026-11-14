package io.github.lujinxin.nextep.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPanelStateControllerTest {
    @Test fun navigationCannotOpenCollapsedPanel() {
        val state = FloatingPanelStateController()
        assertEquals(FloatingPanelState.COLLAPSED, state.state)
        assertFalse(state.shouldCollapseForNavigation())
    }

    @Test fun navigationCanCollapseOnlyExpandedPanel() {
        val state = FloatingPanelStateController()
        state.onVisibilityChanged(true)
        assertEquals(FloatingPanelState.EXPANDED, state.state)
        assertTrue(state.shouldCollapseForNavigation())
        state.onVisibilityChanged(false)
        assertFalse(state.shouldCollapseForNavigation())
    }

    @Test fun repeatedCollapsedUpdatesNeverToggle() {
        val state = FloatingPanelStateController()
        repeat(4) { state.onVisibilityChanged(false) }
        assertEquals(FloatingPanelState.COLLAPSED, state.state)
        assertFalse(state.shouldCollapseForNavigation())
    }
}
