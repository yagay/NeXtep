package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import java.lang.reflect.Method

/**
 * Observes actual system policy navigation without consuming or modifying it.
 * Built for framework methods confirmed in the device's services.jar.
 *
 * Never folds a virtual display's own Back key. SystemUI checks the dock's
 * state and does nothing unless the panel is EXPANDED.
 */
internal object SystemNavigationFoldHook {
    private const val TAG = "NeXtepNavFold"
    private var lastSource = ""
    private var lastAt = 0L
    private var installed = 0

    fun install(module: XposedModule, loader: ClassLoader) {
        val policy = runCatching {
            loader.loadClass("com.android.server.policy.PhoneWindowManager")
        }.getOrElse { Log.w(TAG, "POLICY_CLASS_MISSING", it); return }

        fun hook(name: String, count: Int, source: (XposedInterface.Chain) -> String?) {
            val methods = policy.declaredMethods.filter {
                it.name == name && it.parameterTypes.size == count
            }
            if (methods.isEmpty()) {
                Log.i(TAG, "METHOD_MISSING name=$name args=$count")
                return
            }
            for (method in methods) runCatching {
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val event = runCatching { source(chain) }.getOrNull()
                        if (event != null) dispatch(chain.thisObject, event)
                        return result
                    }
                })
                installed++
                Log.i(TAG, "HOOK_INSTALLED ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
            }.onFailure { Log.w(TAG, "HOOK_FAILED ${method.name}", it) }
        }

        hook("handleShortPressOnHome", 1) { "home-short" }
        hook("launchHomeFromHotKey", 0) { "home-hotkey" }
        hook("toggleRecentApps", 0) { "recents-toggle" }
        hook("showRecentApps", 1) { "recents-show" }
        // AOSP/OPlus return the effective delay from interceptKeyBeforeDispatching.
        // Only observe a completed, non-cancelled physical Default Display key.
        hook("interceptKeyBeforeDispatching", 2) { chain ->
            keySource(chain.args.firstOrNull { it is KeyEvent } as? KeyEvent)
        }
        hook("interceptKeyBeforeQueueing", 2) { chain ->
            keySource(chain.args.firstOrNull { it is KeyEvent } as? KeyEvent)
        }
        Log.i(TAG, "NAV_FOLD_SUMMARY installed=$installed")
    }

    private fun keySource(event: KeyEvent?): String? {
        event ?: return null
        if (event.action != KeyEvent.ACTION_UP || event.isCanceled || event.repeatCount != 0)
            return null
        val display = runCatching {
            android.view.InputEvent::class.java.getDeclaredMethod("getDisplayId").apply {
                isAccessible = true
            }.invoke(event) as Int
        }.getOrDefault(-1)
        // -1 is an unspecified physical input device display; reject virtual ids.
        if (display > 0) return null
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> "back"
            KeyEvent.KEYCODE_HOME -> "home-key"
            KeyEvent.KEYCODE_APP_SWITCH -> "recents-key"
            else -> null
        }
    }

    private fun dispatch(policy: Any?, source: String) {
        val now = SystemClock.elapsedRealtime()
        // Multiple policy methods can observe the same gesture.
        if (source == lastSource && now - lastAt < 350L) return
        lastSource = source
        lastAt = now
        val ctx = generateSequence(policy?.javaClass) { it.superclass }
            .mapNotNull { cls -> runCatching {
                cls.getDeclaredField("mContext").apply { isAccessible = true }
                    .get(policy) as? Context
            }.getOrNull() }.firstOrNull()
        if (ctx == null) {
            Log.w(TAG, "NAV_CONTEXT_MISSING source=$source")
            return
        }
        runCatching {
            ctx.sendBroadcast(
                Intent(TriggerBroadcastContract.ACTION_SYSTEM_NAVIGATION_FOLD)
                    .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
                    .putExtra(TriggerBroadcastContract.EXTRA_NAVIGATION_EVENT_SOURCE, source)
                    .putExtra(TriggerBroadcastContract.EXTRA_GESTURE_EVENT_TIME, now),
            )
            Log.i(TAG, "NAV_SENT source=$source")
        }.onFailure { Log.w(TAG, "NAV_SEND_FAILED source=$source", it) }
    }
}
