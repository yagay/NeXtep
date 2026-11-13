package io.github.lujinxin.nextep.systemui

/** Explicit separation between simply hiding a live task dock and ending it. */
internal object FloatingDockSessionPolicy {
    enum class CloseReason {
        USER_HIDE,
        SCREEN_LOCK,
        FEATURE_DISABLED,
        OPEN_ORIGINAL_WORKSPACE,
        SLOT_TEST_SWITCH,
    }

    fun shouldReleaseDisplays(reason: CloseReason): Boolean = when (reason) {
        CloseReason.USER_HIDE, CloseReason.SCREEN_LOCK -> false
        CloseReason.FEATURE_DISABLED,
        CloseReason.OPEN_ORIGINAL_WORKSPACE,
        CloseReason.SLOT_TEST_SWITCH -> true
    }
}
