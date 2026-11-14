package io.github.lujinxin.nextep.systemserver

/**
 * Only user-enabled, SystemUI-owned floating dock displays may enter the
 * experimental lock guard. Original workspace slots are deliberately excluded.
 * Requiring display ownership prevents other apps from creating a similarly
 * named display to obtain system_server protection.
 */
internal object FloatingLockGuardScope {
    private val namePattern = Regex("NeXtep-floating-slot-[0-3]")

    fun isEligibleDisplay(
        displayId: Int,
        name: String,
        type: Int?,
        ownerUid: Int?,
        ownerPackage: String?,
    ): Boolean = displayId > 0 && namePattern.matches(name) &&
        type == 5 && (ownerUid == 1000 || ownerPackage == "com.android.systemui")
}
