package io.github.lujinxin.nextep.systemserver

import android.os.Binder
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Read-only trace of external task-move requests inside system_server.
 * Never changes arguments/results and never hooks third-party app processes.
 * The OEM may also reparent Tasks internally without this binder method;
 * therefore absence of this record does NOT prove there was no system move.
 */
internal object SystemServerTaskMoveTrace {
    private const val TAG = "NeXtepSlotGuard"

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val service = runCatching {
            classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
        }.getOrElse {
            Log.w(TAG, "TASK_MOVE_TRACE_UNAVAILABLE class", it)
            return
        }
        val methods = service.declaredMethods.filter { method ->
            method.name in setOf("moveRootTaskToDisplay", "moveTaskToDisplay") &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }
        for (method in methods) runCatching {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val taskId = chain.args.getOrNull(0) as? Int ?: -1
                    val destination = chain.args.getOrNull(1) as? Int ?: -1
                    val callerUid = Binder.getCallingUid()
                    // Capture the calling identity BEFORE the system may clear
                    // Binder's identity inside the implementation.
                    if (taskId > 0) {
                        Log.i(TAG, "TASK_MOVE_CALL method=${method.name} " +
                            "task=$taskId destination=$destination callerUid=$callerUid")
                    }
                    return chain.proceed()
                }
            })
        }.onFailure { Log.w(TAG, "TASK_MOVE_TRACE_INSTALL_FAILED ${method.name}", it) }
        Log.i(TAG, "TASK_MOVE_TRACE_INSTALLED count=${methods.size}")
    }
}
