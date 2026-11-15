package io.github.lujinxin.nextep.systemui

/**
 * Legacy compatibility guard. HOME is always a system-owned transition.
 * A pinned Task may be protected without being reparented to a virtual slot.
 */
internal object PinnedHomeTransitionPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun shouldAutoParkOnHome(pinned: Boolean, nextIsHome: Boolean): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun isPinnedHomePark(pinned: Boolean, reason: String): Boolean = false
}
