package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/**
 * Native Oplus zoom window coordinator. Runs in LSPosed-injected SystemUI,
 * where the OEM's activity-task service can be called with the real taskId.
 * Never creates VirtualDisplays, starts a launcher Intent or scales a TextureView.
 */
internal class NativeFreeformController(context: Context) {
    private val repository = TaskRepository(context)
    private val context = context.applicationContext
    private val bridge = OplusExistingTaskZoomBridge()
    private val handler = Handler(Looper.getMainLooper())
    private val nativeTasks = LinkedHashSet<Int>()
    private val pending = LinkedHashMap<Int, Long>()
    private val capacity = 4

    fun addForeground(): Boolean {
        refresh()
        val task = repository.foregroundTask() ?: return reject("No foreground task")
        val home = TriggerBroadcastContract.resolveHomePackage(context)
        val pkg = task.component.packageName
        if (task.displayId != Display.DEFAULT_DISPLAY ||
            task.userId == null ||
            pkg == home || pkg == TriggerBroadcastContract.SYSTEM_UI_PACKAGE ||
            pkg == TriggerBroadcastContract.MODULE_PACKAGE) {
            return reject("Ineligible foreground task=${task.taskId} pkg=$pkg")
        }
        if (task.vendorWindowed || task.windowingMode == OPLUS_ZOOM_MODE ||
            task.windowingMode == FREEFORM_MODE) {
            nativeTasks.add(task.taskId)
            NeXtepLog.info("native_oplus",
                "ALREADY_WINDOWED task=${task.taskId} mode=${task.windowingMode} vendor=${task.vendorWindowed}")
            return true
        }
        if (task.windowingMode != FULLSCREEN_MODE) {
            return reject("Unsupported source windowing mode=${task.windowingMode}")
        }
        if (task.taskId in pending) {
            NeXtepLog.info("native_oplus", "ZOOM_ALREADY_PENDING task=${task.taskId}")
            return true
        }
        if (nativeTasks.size + pending.size >= capacity) {
            return reject("Native window capacity reached ($capacity)")
        }
        return when (val result = bridge.start(task)) {
            is OplusExistingTaskZoomBridge.Result.Failed ->
                reject("Native zoom task=${task.taskId} failed: ${result.reason}")
            is OplusExistingTaskZoomBridge.Result.Submitted -> {
                pending[task.taskId] = SystemClock.elapsedRealtime()
                verify(task.taskId, attempt = 0)
                true
            }
        }
    }

    /** OEM task transition is async. Never assume successful Binder return is a window. */
    private fun verify(taskId: Int, attempt: Int) {
        handler.postDelayed({
            val target = repository.findTask(taskId)
            val success = target != null && target.displayId == Display.DEFAULT_DISPLAY &&
                (target.vendorWindowed || target.windowingMode == OPLUS_ZOOM_MODE ||
                    target.windowingMode == FREEFORM_MODE)
            when {
                success -> {
                    pending.remove(taskId)
                    nativeTasks.add(taskId)
                    NeXtepLog.info("native_oplus",
                        "TASK_ZOOM_CONFIRMED task=$taskId mode=${target?.windowingMode} vendor=${target?.vendorWindowed} bounds=${target?.bounds}")
                }
                attempt < 3 && target != null -> verify(taskId, attempt + 1)
                else -> {
                    pending.remove(taskId)
                    NeXtepLog.warn("native_oplus",
                        "TASK_ZOOM_NOT_CONFIRMED task=$taskId attempts=${attempt + 1} " +
                            "mode=${target?.windowingMode} display=${target?.displayId}; " +
                            "no fallback relaunch or task move")
                }
            }
        }, 450L + attempt * 250L)
    }

    private fun refresh() {
        val tasks = repository.runningTasks()
        nativeTasks.removeAll { id ->
            tasks.none { it.taskId == id && it.displayId == Display.DEFAULT_DISPLAY &&
                (it.vendorWindowed || it.windowingMode == OPLUS_ZOOM_MODE ||
                    it.windowingMode == FREEFORM_MODE) }
        }
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeAll { now - it.value > 5_000L }
    }

    private fun reject(reason: String): Boolean {
        NeXtepLog.warn("native_oplus", "TASK_ZOOM_REFUSED $reason")
        return false
    }

    private companion object {
        const val FULLSCREEN_MODE = 1
        const val FREEFORM_MODE = 5
        const val OPLUS_ZOOM_MODE = 100
    }
}
