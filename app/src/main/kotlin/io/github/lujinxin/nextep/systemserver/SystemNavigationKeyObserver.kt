package io.github.lujinxin.nextep.systemserver

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only system_server key diagnostics for ColorOS / AOSP.
 *
 * Observe the policy entry points instead of guessing SystemUI button classes.
 * Never consume input, modify key events, or alter the original policy result.
 * Gesture navigation does not necessarily generate KeyEvents and must be
 * traced separately in SystemUI/Launcher.
 */
internal object SystemNavigationKeyObserver {
    private const val TAG = "NeXtepNavKey"
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val keyCodes = setOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_APP_SWITCH,
    )

    fun install(module: XposedModule, loader: ClassLoader) {
        val targetNames = listOf(
            "com.android.server.policy.PhoneWindowManager",
            "com.android.server.policy.OplusPhoneWindowManager",
        )
        var installed = 0
        for (name in targetNames) {
            val clazz = runCatching { loader.loadClass(name) }.getOrNull()
            if (clazz == null) {
                Log.i(TAG, "KEY_CLASS_MISSING class=$name")
                continue
            }
            val methods = clazz.declaredMethods.filter { method ->
                method.name in setOf("interceptKeyBeforeQueueing", "interceptKeyBeforeDispatching") &&
                    method.parameterTypes.any { KeyEvent::class.java.isAssignableFrom(it) }
            }
            if (methods.isEmpty()) {
                Log.i(TAG, "KEY_METHOD_MISSING class=$name candidates=" +
                    clazz.declaredMethods.filter { it.name.contains("Key", true) }
                        .map { it.name }.distinct().take(12))
            }
            for (method in methods) {
                if (installMethod(module, method)) installed++
            }
        }
        Log.i(TAG, "KEY_HOOK_SUMMARY installed=$installed mode=PASSIVE")
    }

    private fun installMethod(module: XposedModule, method: Method): Boolean =
        runCatching {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // Preserve every original input decision, even on lookup failure.
                    val event = chain.args.firstOrNull { it is KeyEvent } as? KeyEvent
                    if (event != null && event.keyCode in keyCodes &&
                        event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                        runCatching {
                            val key = "${method.name}:${event.keyCode}:${event.displayId}"
                            val now = SystemClock.elapsedRealtime()
                            val previous = lastSeen[key]
                            if (previous == null || now - previous >= 120L) {
                                lastSeen[key] = now
                                Log.i(TAG, "KEY_EVENT source=${method.name} code=${event.keyCode}" +
                                    " action=UP display=${event.displayId}" +
                                    " device=${event.deviceId} flags=${event.flags}")
                            }
                        }
                    }
                    return chain.proceed()
                }
            })
            Log.i(TAG, "KEY_HOOK_INSTALLED method=${method.declaringClass.name}#${method.name}" +
                " params=${method.parameterTypes.joinToString { it.simpleName }}")
            true
        }.onFailure { error ->
            Log.w(TAG, "KEY_HOOK_FAILED method=${method.declaringClass.name}#${method.name}", error)
        }.getOrDefault(false)
}
