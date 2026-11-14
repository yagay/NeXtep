package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import io.github.lujinxin.nextep.display.SlotState
import io.github.lujinxin.nextep.display.VirtualDisplaySlot
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository

/**
 * New native fullscreen transaction owner. The four original VirtualDisplays
 * remain allocated. No UI-thread sleep loops or repeating 360 ms watchdog.
 *
 * A transition is driven by Shell task callbacks with a bounded set of
 * delayed checks solely for OEMs that omit a callback. Never finish or
 * destroy a virtual-display Task in order to restore it.
 */
internal class FloatingNativeTaskSession(
    context: Context,
    private val slotAt: (Int) -> VirtualDisplaySlot?,
    private val onSuspended: (Boolean) -> Unit,
    private val onEntered: () -> Unit,
    private val onReturned: (Boolean) -> Unit,
) {
    private data class Lease(
        val index: Int, val taskId: Int, val sourceDisplay: Int,
        val originalResizeMode: Int?, val startedAt: Long,
        var phase: Phase, var attempts: Int = 0, var backgroundAt: Long = 0L,
    )
    private enum class Phase { ENTERING, ACTIVE, RETURNING }
    private val tasks = TaskRepository(context)
    private val handler = Handler(Looper.getMainLooper())
    private var lease: Lease? = null
    private var generation = 0
    private val checks = mutableListOf<Runnable>()

    val active: Boolean get() = lease != null
    val transitioning: Boolean get() = lease?.phase != null && lease?.phase != Phase.ACTIVE

    fun enter(index: Int, state: SlotState.Occupied): Boolean {
        if (lease != null || slotAt(index)?.displayId() != state.displayId) return false
        val original = tasks.findTask(state.taskId)
            ?.takeIf { it.displayId == state.displayId } ?: return false
        val pin = Lease(index, state.taskId, state.displayId, original.resizeMode,
            SystemClock.uptimeMillis(), Phase.ENTERING)
        lease = pin
        generation++
        clearChecks()
        return runCatching {
            onSuspended(true)
            if (original.resizeMode != 2)
                ActivityTaskManagerCompat.setTaskResizeable(pin.taskId, 2).getOrThrow()
            TaskSurfaceCompat.markDisplayExchange(pin.taskId, Display.DEFAULT_DISPLAY)
            ActivityTaskManagerCompat.moveTaskToDisplay(pin.taskId, Display.DEFAULT_DISPLAY).getOrThrow()
            scheduleChecks(pin)
            NeXtepLog.info("floating_native_v2",
                "ENTER_REQUEST task=${pin.taskId} source=${pin.sourceDisplay}")
            true
        }.getOrElse { error ->
            NeXtepLog.warn("floating_native_v2", "ENTER_REQUEST_FAILED task=${pin.taskId}", error)
            returnToSlot("enter-failure")
            false
        }
    }

    /**
     * A launcher or Recents selection can move an already-owned task to the
     * physical display without asking NeXtep. Reserve that exact task and
     * return it to its source slot in STABLE mode; NATIVE may keep it on 0.
     */
    fun adoptExternal(index: Int, state: SlotState.Occupied, allowNative: Boolean): Boolean {
        if (lease != null || slotAt(index)?.displayId() != state.displayId) return false
        val current = tasks.findTask(state.taskId)
            ?.takeIf { it.displayId == Display.DEFAULT_DISPLAY } ?: return false
        val pin = Lease(index, state.taskId, state.displayId, current.resizeMode,
            SystemClock.uptimeMillis(), Phase.ACTIVE)
        lease = pin
        generation++
        clearChecks()
        onSuspended(true)
        NeXtepLog.info("floating_native_v2",
            "EXTERNAL_ADOPT task=${pin.taskId} from=${pin.sourceDisplay}" +
                " nativeAllowed=$allowNative")
        if (allowNative) {
            onEntered()
        } else {
            returnToSlot("external-stable")
        }
        return true
    }

    fun returnToSlot(reason: String): Boolean {
        val pin = lease ?: return true
        if (pin.phase == Phase.RETURNING) return true
        pin.phase = Phase.RETURNING
        pin.attempts = 0
        clearChecks()
        NeXtepLog.info("floating_native_v2", "RETURN_REQUEST task=${pin.taskId} reason=$reason")
        issueReturn(pin)
        return true // asynchronous: success is confirmed only in finishReturn()
    }

    fun onTasksChanged() {
        val pin = lease ?: return
        if (pin.phase == Phase.ENTERING) {
            val task = tasks.findTask(pin.taskId)
            if (task?.displayId == Display.DEFAULT_DISPLAY) {
                clearChecks()
                runCatching {
                    task.token?.let { token ->
                        WindowContainerTransactionCompat.applyTaskLayout(
                            token, Rect(), contextDensity(), 1).getOrThrow()
                    }
                    TaskSurfaceCompat.resetPromotedSlotToFullscreen(pin.taskId)
                    tasks.bringTaskToFront(pin.taskId).getOrThrow()
                    pin.phase = Phase.ACTIVE
                    NeXtepLog.info("floating_native_v2",
                        "ENTER_CONFIRMED task=${pin.taskId} physicalDisplay=0")
                    onEntered()
                }.onFailure {
                    NeXtepLog.warn("floating_native_v2", "ENTER_FINALIZE_FAILED", it)
                    returnToSlot("enter-finalize-failure")
                }
            }
            return
        }
        if (pin.phase == Phase.RETURNING) {
            if (tasks.findTask(pin.taskId)?.displayId == pin.sourceDisplay) {
                finishReturn(pin)
            }
            return
        }
        val foreground = tasks.foregroundTask()
        if (foreground?.taskId == pin.taskId) {
            pin.backgroundAt = 0
        } else if (foreground != null && foreground.displayId == Display.DEFAULT_DISPLAY) {
            if (pin.backgroundAt == 0L) {
                pin.backgroundAt = SystemClock.uptimeMillis()
                schedule(pin, 500L)
            } else if (SystemClock.uptimeMillis() - pin.backgroundAt >= 350L) {
                returnToSlot("physical-task-background")
            }
        }
    }

    private fun contextDensity(): Int =
        slotAt(lease?.index ?: 0)?.geometry()?.densityDpi ?: 440

    private fun issueReturn(pin: Lease) {
        if (lease !== pin) return
        val current = tasks.findTask(pin.taskId)
        if (current?.displayId == pin.sourceDisplay) {
            finishReturn(pin)
            return
        }
        if (current?.displayId != Display.DEFAULT_DISPLAY && current != null) {
            NeXtepLog.warn("floating_native_v2",
                "RETURN_FOREIGN_DISPLAY task=${pin.taskId} display=${current.displayId}")
            // Another display now owns this task; do not steal or release it.
            return
        }
        pin.attempts++
        runCatching {
            TaskSurfaceCompat.markDisplayExchange(pin.taskId, pin.sourceDisplay)
            ActivityTaskManagerCompat.moveTaskToDisplay(pin.taskId, pin.sourceDisplay).getOrThrow()
        }.onFailure {
            NeXtepLog.warn("floating_native_v2",
                "RETURN_MOVE_FAILED task=${pin.taskId} attempt=${pin.attempts}", it)
        }
        scheduleChecks(pin)
    }

    private fun finishReturn(pin: Lease) {
        if (lease !== pin || pin.phase != Phase.RETURNING) return
        val task = tasks.findTask(pin.taskId) ?: return
        if (task.displayId != pin.sourceDisplay) return
        runCatching {
            val slot = checkNotNull(slotAt(pin.index))
            val token = checkNotNull(task.token)
            val geometry = checkNotNull(slot.geometry())
            WindowContainerTransactionCompat.applyTaskLayout(
                token, Rect(), geometry.densityDpi, 1).getOrThrow()
            val bounds = slot.sourceBounds()
            TaskSurfaceCompat.fitSlotSurface(pin.taskId, pin.sourceDisplay,
                Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
            pin.originalResizeMode?.takeIf { it != 2 }?.let {
                ActivityTaskManagerCompat.setTaskResizeable(pin.taskId, it)
            }
            TaskSurfaceCompat.cancelDisplayExchange(pin.taskId)
            clearChecks()
            lease = null
            onSuspended(false)
            onReturned(true)
            NeXtepLog.info("floating_native_v2",
                "RETURN_CONFIRMED task=${pin.taskId} display=${pin.sourceDisplay}")
        }.onFailure {
            NeXtepLog.warn("floating_native_v2",
                "RETURN_FINALIZE_PENDING task=${pin.taskId}", it)
            schedule(pin, 500L)
        }
    }

    private fun scheduleChecks(pin: Lease) {
        listOf(120L, 450L, 1100L).forEach { delay -> schedule(pin, delay) }
    }

    private fun schedule(pin: Lease, delay: Long) {
        val currentGeneration = generation
        val check = object : Runnable {
            override fun run() {
                checks.remove(this)
                if (lease !== pin || currentGeneration != generation) return
                onTasksChanged()
                if (pin.phase == Phase.ENTERING &&
                    SystemClock.uptimeMillis() - pin.startedAt > 1800L) {
                    NeXtepLog.warn("floating_native_v2", "ENTER_TIMED_OUT; returning original Task")
                    returnToSlot("enter-timeout")
                } else if (pin.phase == Phase.RETURNING && pin.attempts < 3 &&
                    tasks.findTask(pin.taskId)?.displayId != pin.sourceDisplay) {
                    // Bounded retries only. The task remains reserved after failures.
                    issueReturn(pin)
                }
            }
        }
        checks += check
        handler.postDelayed(check, delay)
    }

    private fun clearChecks() {
        checks.forEach(handler::removeCallbacks)
        checks.clear()
    }

    fun shutdown(): Boolean {
        if (lease != null) {
            returnToSlot("shutdown")
            return false
        }
        clearChecks()
        return true
    }
}
