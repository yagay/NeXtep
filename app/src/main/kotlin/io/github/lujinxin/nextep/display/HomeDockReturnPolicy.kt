package io.github.lujinxin.nextep.display

/**
 * Bounded handling for a pinned Task unexpectedly returned to Display 0 after
 * a Home-triggered auto-park. No hooks in the video app and no endless moves.
 */
internal object HomeDockReturnPolicy {
    const val PROTECTION_MS = 7_000L
    const val OBSERVE_MS = 450L
    const val MAX_RETURNS = 1

    enum class Decision { RELEASE, OBSERVE, WAIT, RESTORE }

    fun decide(
        pinned: Boolean,
        sameTaskAndUser: Boolean,
        fullscreen: Boolean,
        ageMs: Long,
        observedForMs: Long?,
        completedReturns: Int,
    ): Decision {
        if (!pinned || !sameTaskAndUser || !fullscreen ||
            ageMs !in 0L..PROTECTION_MS || completedReturns >= MAX_RETURNS
        ) return Decision.RELEASE
        if (observedForMs == null) return Decision.OBSERVE
        if (observedForMs < OBSERVE_MS) return Decision.WAIT
        return Decision.RESTORE
    }
}
