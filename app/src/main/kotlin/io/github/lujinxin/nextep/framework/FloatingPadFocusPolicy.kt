package io.github.lujinxin.nextep.framework

/**
 * Per-virtual-display focus profiles. AOSP multi-resume is controlled by
 * actual visibility/focus; these flags ASK Android for a separate focus
 * domain and do not spoof Activity.onResume or TOP_RESUMED.
 *
 * PAD tries a trusted, independent-focussed display without the
 * STEAL_TOP_FOCUS_DISABLED flag first. On Android this permits the virtual
 * display to participate in top focus/IME, but may move focus away from the
 * phone's main display. Users can disable Pad mode to restore that behavior.
 *
 * PAD first tries an independent unlocked virtual display. This is internal
 * to NeXtep; the host preview remains hidden behind the physical keyguard.
 * ALWAYS_UNLOCKED affects keyguard treatment, not display power, and is
 * always paired with OWN_DISPLAY_GROUP. Rejected combinations fall back.
 */
object FloatingPadFocusPolicy {
    private val base = listOf("OWN_CONTENT_ONLY", "PRESENTATION")

    fun candidates(available: Set<String>, padMode: Boolean): List<List<String>> {
        val trusted = "TRUSTED" in available
        val focus = trusted && "OWN_FOCUS" in available
        val group = "OWN_DISPLAY_GROUP" in available
        val noSteal = focus && "STEAL_TOP_FOCUS_DISABLED" in available
        val unlocked = trusted && group && "ALWAYS_UNLOCKED" in available
        val focusFlags = buildList {
            if (trusted) add("TRUSTED")
            if (focus) add("OWN_FOCUS")
            if (group) add("OWN_DISPLAY_GROUP")
        }
        val preferred = base + focusFlags
        val antiSteal = if (noSteal) preferred + "STEAL_TOP_FOCUS_DISABLED" else preferred
        val legacy = listOf(antiSteal, preferred, base)
        if (!padMode) return legacy.distinct()

        if (!focus) return legacy.distinct()
        val noGroup = base + listOf("TRUSTED", "OWN_FOCUS")
        val unlockedPreferred =
            if (unlocked) preferred + "ALWAYS_UNLOCKED" else preferred
        return listOf(
            unlockedPreferred, // Separate keyguard domain when OEM supports it
            preferred, // Pad: allow focus / IME on the virtual display
            antiSteal, // Compatible if Android rejects non-anti-steal focus
            noGroup, // Some OEM builds disallow own display groups
            if (noSteal) noGroup + "STEAL_TOP_FOCUS_DISABLED" else noGroup,
            base, // Last resort; log missing OWN_FOCUS after creation
        ).distinct()
    }
}
