package io.github.lujinxin.nextep.systemui

/**
 * Separates HOME's task parking from launcher-focus restoration.
 *
 * The pinned Task must go back to the reserved virtual slot on HOME;
 * moving it can briefly make the virtual Activity top-focused on OPlus.
 * Restore the launcher only for this exact HOME-initiated pinned move.
 */
internal object PinnedHomeTransitionPolicy {
    fun shouldAutoParkOnHome(pinned: Boolean, nextIsHome: Boolean): Boolean =
        pinned && nextIsHome

    fun shouldRestoreHome(pinned: Boolean, reason: String): Boolean =
        pinned && reason == "home"
}
