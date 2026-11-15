package io.github.lujinxin.nextep.systemui

/**
 * HOME should never automatically reparent a pinned app to an OWN_FOCUS
 * virtual display: some OEMs refocus that Activity above the launcher.
 * Exact pinned Task/UID background protection remains active on Display 0.
 */
internal object PinnedHomeTransitionPolicy {
    fun shouldKeepOnMain(pinned: Boolean, nextIsHome: Boolean): Boolean =
        pinned && nextIsHome
}
