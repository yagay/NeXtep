package io.github.lujinxin.nextep.systemui

import android.app.ActivityOptions
import android.os.Bundle
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository

/**
 * Uses the actual OxygenOS SystemUI floating-window launch contract observed
 * in the user's SystemUI.apk (FloatHandleController):
 *   startActivityFromRecents(taskId, bundle)
 *   bundle["android:activity.mZoomLaunchFlags"] = 4
 *   bundle["android.activity.windowingMode"] = 100
 *
 * This code runs in LSPosed-injected SystemUI, not in the module APK. It
 * operates on the existing task, never resolves a launcher Intent or starts a
 * second app instance. No shell/root subprocess is necessary in SystemUI.
 */
internal class OplusExistingTaskZoomBridge {
    sealed interface Result {
        data class Submitted(val startCode: Int) : Result
        data class Failed(val reason: String) : Result
    }

    fun start(task: TaskRepository.TaskSnapshot): Result {
        if (task.displayId != 0) return Result.Failed("Task is not on Display 0")
        if (task.userId == null) return Result.Failed("Task user missing")
        val options: Bundle = ActivityOptions.makeBasic().toBundle().apply {
            putInt(KEY_ZOOM_LAUNCH_FLAGS, ZOOM_LAUNCH_FLAGS)
            putInt(KEY_WINDOWING_MODE, OPLUS_ZOOM_WINDOWING_MODE)
        }
        return runCatching {
            val managerClass = Class.forName("android.app.ActivityManager")
            val service = managerClass.getDeclaredMethod("getService").apply {
                isAccessible = true
            }.invoke(null) ?: error("ActivityManager service unavailable")
            val start = service.javaClass.methods.firstOrNull {
                it.name == "startActivityFromRecents" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Bundle::class.java
            } ?: error("startActivityFromRecents(int, Bundle) unavailable")
            val result = start.invoke(service, task.taskId, options)
            val code = (result as? Number)?.toInt()
                ?: error("Unexpected start result: ${result?.javaClass?.name}")
            if (code < 0) Result.Failed("System rejected task=${task.taskId}, code=$code")
            else {
                NeXtepLog.info("native_oplus",
                    "SYSTEMUI_TASK_ZOOM_SUBMITTED task=${task.taskId} flags=$ZOOM_LAUNCH_FLAGS mode=$OPLUS_ZOOM_WINDOWING_MODE result=$code")
                Result.Submitted(code)
            }
        }.getOrElse { error ->
            NeXtepLog.warn("native_oplus",
                "SYSTEMUI_TASK_ZOOM_FAILED task=${task.taskId}", error)
            Result.Failed(error.cause?.message ?: error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        const val KEY_ZOOM_LAUNCH_FLAGS = "android:activity.mZoomLaunchFlags"
        const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
        const val ZOOM_LAUNCH_FLAGS = 4
        const val OPLUS_ZOOM_WINDOWING_MODE = 100
    }
}
