package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Rect
import android.view.Display
import io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/**
 * Experimental system-managed multi-freeform workspace. Unlike the VirtualDisplay
 * dock, tasks remain on Display 0 and WM Shell/OEM owns frames and input.
 *
 * Runs only inside the hooked SystemUI process. No vendor/private service names
 * are guessed: an unsupported freeform mode fails without task movement.
 */
internal class NativeFreeformController(context: Context) {
    private val taskRepository = TaskRepository(context)
    private val context = context.applicationContext
    private val tracked = LinkedHashSet<Int>()
    private val capacity = 4

    fun addForeground(): Boolean {
        refresh()
        val task = taskRepository.foregroundTask() ?: return false
        val packageName = task.component.packageName
        val home = TriggerBroadcastContract.resolveHomePackage(context)
        if (task.displayId != Display.DEFAULT_DISPLAY ||
            packageName == home ||
            packageName == TriggerBroadcastContract.SYSTEM_UI_PACKAGE ||
            packageName == TriggerBroadcastContract.MODULE_PACKAGE ||
            task.token == null) return false
        if (task.windowingMode == FREEFORM_MODE) {
            tracked.add(task.taskId)
            NeXtepLog.info("native_freeform", "TRACK_EXISTING task=${task.taskId}")
            return true
        }
        if (task.windowingMode != FULLSCREEN_MODE || tracked.size >= capacity) {
            NeXtepLog.warn("native_freeform", "REFUSED task=${task.taskId} mode=${task.windowingMode} tracked=${tracked.size}")
            return false
        }
        val display = context.resources.displayMetrics
        val width = display.widthPixels.coerceAtLeast(320)
        val height = display.heightPixels.coerceAtLeast(480)
        val targetWidth = (width * 0.72f).toInt().coerceAtLeast(240).coerceAtMost(width)
        val targetHeight = (height * 0.55f).toInt().coerceAtLeast(320).coerceAtMost(height)
        val shift = tracked.size * (width / 18)
        val left = ((width - targetWidth) / 2 + shift).coerceAtMost(width - targetWidth)
        val top = ((height - targetHeight) / 3 + shift).coerceAtMost(height - targetHeight)
        val window = Rect(left, top, left + targetWidth, top + targetHeight)
        val original = Rect(task.bounds)
        val originalMode = task.windowingMode
        return runCatching {
            // One atomic system transaction. Never first migrate to Display 0.
            WindowContainerTransactionCompat.applyTaskLayout(
                task.token, window, task.densityDpi, FREEFORM_MODE
            ).getOrThrow()
            // WindowManager applies organizer transactions asynchronously.
            // Do not read RunningTaskInfo immediately and mistakenly roll
            // back a transaction which is still being committed by WM Shell.
            tracked.add(task.taskId)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val actual = taskRepository.findTask(task.taskId)
                if (actual?.windowingMode != FREEFORM_MODE ||
                    actual.displayId != Display.DEFAULT_DISPLAY) {
                    tracked.remove(task.taskId)
                    NeXtepLog.warn("native_freeform",
                        "OS_DID_NOT_ENTER_FREEFORM task=${task.taskId} mode=${actual?.windowingMode}")
                } else {
                    NeXtepLog.info("native_freeform",
                        "CONFIRMED task=${task.taskId} bounds=${actual.bounds}")
                }
            }, 1_200L)
            NeXtepLog.info("native_freeform", "REQUEST_SENT task=${task.taskId} bounds=$window")
            true
        }.getOrElse { error ->
            NeXtepLog.warn("native_freeform", "OPEN_FAILED task=${task.taskId}", error)
            // Restore the original windowing mode and bounds if the OEM rejects the request.
            runCatching {
                val after = taskRepository.findTask(task.taskId)
                if (after?.windowingMode == FREEFORM_MODE)
                    WindowContainerTransactionCompat.applyTaskLayout(
                        task.token, original, task.densityDpi, originalMode)
                        .getOrThrow()
            }.onFailure { NeXtepLog.warn("native_freeform", "ROLLBACK_FAILED", it) }
            false
        }
    }

    /** Reap only tracked freeforms, never close or remove app tasks. */
    private fun refresh() {
        val tasks = taskRepository.runningTasks()
        tracked.removeAll { id -> tasks.none { it.taskId == id &&
            it.displayId == Display.DEFAULT_DISPLAY && it.windowingMode == FREEFORM_MODE } }
    }

    private companion object {
        const val FULLSCREEN_MODE = 1
        const val FREEFORM_MODE = 5
    }
}
