package io.github.lujinxin.nextep.systemui

/**
 * A saved pin reserves a slot and protects its exact Task; it is NOT consent
 * to move that Task when HOME or the keyguard takes focus.
 *
 * Automatic moves require both the user's explicit trigger switch and an
 * explicit selected package. HOME is always left to the system launcher.
 */
internal object AutoSlotTransitionPolicy {
    fun permitsAutomaticMove(
        enabled: Boolean,
        selected: Boolean,
        nextIsHome: Boolean,
    ): Boolean = enabled && selected && !nextIsHome
}
