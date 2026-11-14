package io.github.lujinxin.nextep.systemui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Device-specific navigation observation in SystemUI itself.
 *
 * The user's SystemUI.apk contains LauncherProxyService$1, not the generic
 * NavigationBarView/OverviewProxyService hooks used by earlier builds.
 * No cross-process broadcast, synthetic click or interception of navigation.
 */
internal object SystemUiNavigationObserver {
    private const val TAG = "NeXtepNavLocal"
    private val handler = Handler(Looper.getMainLooper())
    private var lastAt = 0L
    private var lastSource = ""

    fun install(module: XposedModule, loader: ClassLoader) {
        val proxy = runCatching {
            loader.loadClass("com.android.systemui.LauncherProxyService\$1")
        }.getOrElse {
            Log.w(TAG, "LAUNCHER_PROXY_CLASS_MISSING", it)
            return
        }

        var installed = 0
        fun observe(name: String, predicate: (List<Any?>) -> Boolean = { true }) {
            val methods = proxy.declaredMethods.filter { it.name == name }
            if (methods.isEmpty()) {
                Log.i(TAG, "METHOD_MISSING $name")
                return
            }
            methods.forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            if (runCatching { predicate(chain.args) }.getOrDefault(false)) {
                                dispatch("systemui-proxy-$name")
                            }
                            return result
                        }
                    })
                    installed++
                    Log.i(TAG, "HOOK_INSTALLED ${proxy.name}#${method.name}")
                }.onFailure { Log.w(TAG, "HOOK_FAILED $name", it) }
            }
        }

        // These methods are confirmed in the exact OPlus SystemUI DEX.
        observe("onOverviewShown")
        observe("onOverviewShownDeprecated") { args ->
            (args.firstOrNull() as? Boolean) != false
        }
        observe("notifySwipeToRecentFinished")
        observe("notifyKeyEvent") { args ->
            (args.firstOrNull() as? Int) in setOf(
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH)
        }
        observe("onKeyEvent") { args ->
            (args.firstOrNull() as? Int) in setOf(
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH)
        }
        Log.i(TAG, "LOCAL_NAV_HOOK_SUMMARY installed=$installed")
    }

    private fun dispatch(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (source == lastSource && now - lastAt < 300L) return
        lastSource = source
        lastAt = now
        handler.post {
            Log.i(TAG, "LOCAL_NAV_EVENT source=$source")
            SystemUiRuntime.onNavigationCompleted(source)
        }
    }
}
