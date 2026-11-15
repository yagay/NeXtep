package io.github.lujinxin.nextep.systemui

/**
 * HOME auto-parking must not race ColorOS Quickstep's original Task transition.
 * Keep the exact foreground Task identity, sample the same launcher task twice,
 * and cancel instead of moving a Task after the user switched elsewhere.
 */
internal object HomeAutoParkSettlePolicy {
    const val FIRST_CHECK_DELAY_MS = 190L
    const val CONFIRM_DELAY_MS = 170L
    const val REQUIRED_CONFIRMATIONS = 2
    const val MAX_CHECKS = 3

    enum class Decision { CANCEL, WAIT, PARK }

    fun decide(
        launcherStillForeground: Boolean,
        sameLauncherTask: Boolean,
        targetStillOnMain: Boolean,
        matchingTargetIdentity: Boolean,
        confirmations: Int,
    ): Decision {
        if (!launcherStillForeground || !sameLauncherTask ||
            !targetStillOnMain || !matchingTargetIdentity) return Decision.CANCEL
        return if (confirmations >= REQUIRED_CONFIRMATIONS) Decision.PARK else Decision.WAIT
    }
}
