package io.github.lujinxin.nextep.floating

/**
 * The SystemUI dock is the only authority for visible state. Navigation events
 * never toggle: they can only request COLLAPSED when currently EXPANDED.
 * The app-owned bubble merely mirrors state reported by SystemUI.
 */
internal enum class FloatingPanelState { COLLAPSED, EXPANDED }

internal class FloatingPanelStateController {
    var state: FloatingPanelState = FloatingPanelState.COLLAPSED
        private set

    fun onVisibilityChanged(expanded: Boolean) {
        state = if (expanded) FloatingPanelState.EXPANDED else FloatingPanelState.COLLAPSED
    }

    fun shouldCollapseForNavigation(): Boolean = state == FloatingPanelState.EXPANDED
}
