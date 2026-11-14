package io.github.lujinxin.nextep.display

/**
 * Original one-main/four-slot workspace disconnects presentation on lock.
 * Optional floating workspace keeps its existing output connected so the VD
 * is not suspended just because the user folds the presentation or locks.
 *
 * This only preserves the display surface. Android remains authoritative for
 * activity lifecycle, focus, background execution and process priority.
 */
internal object VirtualDisplayOutputPolicy {
    fun connectedOnSuspend(retainLiveOutput: Boolean): Boolean = retainLiveOutput

    fun connectedDuringSuspend(retainLiveOutput: Boolean, screenAwake: Boolean): Boolean =
        retainLiveOutput || screenAwake
}
