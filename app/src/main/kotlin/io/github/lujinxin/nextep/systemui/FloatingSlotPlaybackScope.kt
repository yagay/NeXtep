package io.github.lujinxin.nextep.systemui

/**
 * Protect only a session that the user explicitly selected manually, or one
 * that belongs to an application currently occupying a floating slot.
 * Auto-enrollment never applies to unrelated apps or packages that were
 * removed from the floating dock.
 */
internal object FloatingSlotPlaybackScope {
    fun enabled(manualEnabled: Boolean, autoFloatingSlots: Boolean): Boolean =
        manualEnabled || autoFloatingSlots

    fun isEligible(
        packageName: String,
        manualEnabled: Boolean,
        manuallySelected: Set<String>,
        autoFloatingSlots: Boolean,
        currentlyInSlots: Set<String>,
    ): Boolean =
        (manualEnabled && packageName in manuallySelected) ||
            (autoFloatingSlots && packageName in currentlyInSlots)
}
