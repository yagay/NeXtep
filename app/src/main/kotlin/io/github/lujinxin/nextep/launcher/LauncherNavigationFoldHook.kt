package io.github.lujinxin.nextep.launcher

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/**
 * Device-specific navigation completion signals from com.android.launcher.
 *
 * ColorOS Quickstep owns bottom-edge Home/Recents gestures. They are not
 * KeyEvents in PhoneWindowManager and thus never reach the key observer.
 * No gesture input is consumed or modified here.
 */
internal object LauncherNavigationFoldHook {
    private const val TAG = "NeXtepLauncherNav"
    private var lastSignalAt = 0L
    private var lastSignal = ""

    fun install(module: XposedModule, loader: ClassLoader) {
        var installed = 0
        val quickstep = runCatching {
            loader.loadClass("com.android.quickstep.TouchInteractionService\$a")
        }.getOrElse { Log.w(TAG, "OVERVIEW_CLASS_MISSING", it); null }
        if (quickstep != null) {
            listOf("onOverviewShown", "onOverviewToggle").forEach { name ->
                quickstep.declaredMethods.filter { it.name == name }.forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val result = chain.proceed()
                                // Overview shown/toggled is a navigation action, not
                                // a regular touch UP or hover event.
                                val owner = chain.thisObject
                                val context = findContext(owner) ?: runCatching {\n                                    android.app.ActivityThread.currentApplication()\n                                }.getOrNull()
                                if (context != null) signal(context, "launcher-$name")
                                else Log.w(TAG, "OVERVIEW_CONTEXT_MISSING method=$name")
                                return result
                            }
                        })
                        installed++
                        Log.i(TAG, "HOOK_INSTALLED ${quickstep.name}#${method.name}")
                    }.onFailure { Log.w(TAG, "HOOK_FAILED $name", it) }
                }
            }
        }

        // Launcher may inherit onResume through a ColorOS superclass. Hook
        // Activity's implementation only in the Launcher process, and filter
        // the concrete receiver to the actual desktop Activity.
        runCatching {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
                .apply { isAccessible = true }
            module.hook(onResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null &&
                        activity.javaClass.name == "com.android.launcher3.Launcher") {
                        activity.window?.decorView?.post {
                            if (!activity.isFinishing && !activity.isDestroyed &&
                                activity.hasWindowFocus()) signal(activity, "launcher-home-resumed")
                        }
                    }
                    return result
                }
            })
            installed++
            Log.i(TAG, "HOOK_INSTALLED android.app.Activity#onResume launcher-only")
        }.onFailure { Log.w(TAG, "HOME_HOOK_FAILED", it) }
        Log.i(TAG, "LAUNCHER_NAV_SUMMARY installed=$installed")
    }

    private fun findContext(host: Any?): android.content.Context? {
        if (host is android.content.Context) return host
        if (host == null) return null
        // The overview binder implementation may be an inner class whose
        // enclosing service is stored as a synthetic field.
        return generateSequence(host.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .mapNotNull { field -> runCatching {
                field.isAccessible = true
                field.get(host) as? android.content.Context
            }.getOrNull() }.firstOrNull()
    }

    private fun signal(context: android.content.Context, source: String) {
        val now = SystemClock.elapsedRealtime()
        if (source == lastSignal && now - lastSignalAt < 400L) return
        lastSignal = source
        lastSignalAt = now
        runCatching {
            context.sendBroadcast(
                Intent(TriggerBroadcastContract.ACTION_SYSTEM_NAVIGATION_FOLD)
                    .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
                    .putExtra(TriggerBroadcastContract.EXTRA_NAVIGATION_EVENT_SOURCE, source)
                    .putExtra(TriggerBroadcastContract.EXTRA_GESTURE_EVENT_TIME, now),
            )
            Log.i(TAG, "NAV_SENT source=$source")
        }.onFailure { Log.w(TAG, "NAV_SEND_FAILED source=$source", it) }
    }
}
