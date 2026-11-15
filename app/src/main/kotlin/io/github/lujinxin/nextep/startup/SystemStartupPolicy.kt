package io.github.lujinxin.nextep.startup

/** Pure, fail-closed startup decisions; a boot event never enables a feature. */
internal object SystemStartupPolicy {
    data class Plan(
        val resumeFloatingIcon: Boolean,
        val rebindMediaListener: Boolean,
    )

    fun plan(
        autostartEnabled: Boolean,
        userUnlocked: Boolean,
        floatingEnabled: Boolean,
        overlayGranted: Boolean,
        mediaEnabled: Boolean,
        notificationListenerGranted: Boolean,
    ): Plan {
        if (!autostartEnabled || !userUnlocked) return Plan(false, false)
        return Plan(
            resumeFloatingIcon = floatingEnabled && overlayGranted,
            rebindMediaListener = mediaEnabled && notificationListenerGranted,
        )
    }
}
