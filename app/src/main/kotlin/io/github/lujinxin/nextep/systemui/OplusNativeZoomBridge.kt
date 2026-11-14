package io.github.lujinxin.nextep.systemui

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository

/**
 * OxygenOS/ColorOS native flexible-window entry point. This talks to the
 * installed OEM framework from the hooked, privileged SystemUI process.
 * Never installs hooks in third-party apps or bypasses package allowlists.
 */
internal class OplusNativeZoomBridge(private val context: Context) {
    sealed interface Result {
        data object Unavailable : Result
        data class Accepted(val code: Int) : Result
        data class Rejected(val reason: String) : Result
    }

    fun start(task: TaskRepository.TaskSnapshot): Result {
        val managerClass = runCatching {
            Class.forName("com.oplus.zoomwindow.OplusZoomWindowManager")
        }.getOrNull() ?: return Result.Unavailable
        val instance = runCatching {
            managerClass.getMethod("getInstance").invoke(null)
        }.getOrNull() ?: return Result.Unavailable
        // Only invoke the verified vendor signature. Firmware variants may
        // omit or change this API; do not guess arbitrary similar methods.
        val method = runCatching {
            managerClass.getMethod("startZoomWindow", Intent::class.java,
                Bundle::class.java, Int::class.javaPrimitiveType, String::class.java)
        }.getOrNull() ?: return Result.Unavailable
        val enabled = runCatching {
            managerClass.getMethod("isSupportZoomWindowMode").invoke(instance) as? Boolean
        }.getOrNull()
        if (enabled == false) return Result.Unavailable

        val launch = context.packageManager.getLaunchIntentForPackage(
            task.component.packageName
        ) ?: return Result.Rejected("No launcher entry for ${task.component.packageName}")
        // Use the package's canonical launcher entry, not the current top
        // Activity (which may not be exported). Prefer bringing its task forward.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val extras = ActivityOptions.makeBasic().toBundle()
        // Without the OEM's own zoom window-mode constant, calling
        // startZoomWindow may simply start the app fullscreen.
        val zoomMode = runCatching {
            managerClass.getField("WINDOWING_MODE_ZOOM").getInt(null)
        }.getOrNull()?.takeIf { it > 0 } ?: return Result.Unavailable
        extras.putInt("extra_window_mode", zoomMode)
        val userId = task.userId ?: return Result.Rejected("Task user ID unavailable")
        return runCatching {
            val code = method.invoke(instance, launch, extras, userId,
                context.packageName) as? Int
                ?: return@runCatching Result.Rejected("Vendor returned no status")
            // Activity starter result codes < 0 denote a failed start.
            if (code < 0) Result.Rejected("Oplus startZoomWindow code=$code")
            else Result.Accepted(code)
        }.getOrElse {
            NeXtepLog.warn("native_oplus", "Vendor zoom launch failed", it)
            Result.Rejected(it.cause?.message ?: it.message ?: "Vendor zoom launch failed")
        }
    }
}
