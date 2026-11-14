package io.github.lujinxin.nextep.systemserver

/** Pure guard decision: the early lock arm bridges transition only; an
 * already locked physical screen protects a verified live floating slot
 * indefinitely. No app/UID qualifies without a matching display. */
internal object SlotLockProtectionPolicy {
    fun eligible(
        enabled: Boolean,
        matchingSlot: Boolean,
        finishing: Boolean,
        physicallyOff: Boolean,
        displaySleeping: Boolean,
        prearmed: Boolean,
    ): Boolean = enabled && matchingSlot && !finishing &&
        (physicallyOff || displaySleeping || prearmed)
}
