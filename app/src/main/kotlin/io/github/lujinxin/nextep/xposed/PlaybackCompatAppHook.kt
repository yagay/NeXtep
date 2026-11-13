package io.github.lujinxin.nextep.xposed

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional passive source diagnostics for apps manually added to LSPosed scope.
 * Does not override pause(), change AudioManager focus or fake Activity events.
 *
 * An opt-in is checked against the module's read-only ContentProvider, which
 * confirms the caller UID and selected package. Fail closed when unavailable.
 */
internal object PlaybackCompatAppHook {
    private const val POLICY_URI = "content://io.github.lujinxin.nextep.playback.policy"
    private val attemptedPackages = ConcurrentHashMap.newKeySet<String>()

    fun install(module: XposedModule, packageName: String, classLoader: ClassLoader) {
        val method = Application::class.java.getDeclaredMethod("attach", Context::class.java).apply {
            isAccessible = true
        }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val context = chain.args.firstOrNull() as? Context ?: return result
                if (context.packageName != packageName || !attemptedPackages.add(packageName)) {
                    return result
                }
                // Never block the target app's Application.attach binder thread
                // waiting for the NeXtep settings process to start.
                Thread({
                    runCatching {
                        val allowed = context.contentResolver.call(
                            Uri.parse(POLICY_URI), "is_enabled", packageName, null,
                        )?.getBoolean("enabled", false) == true
                        if (!allowed) {
                            Log.i("NeXtep", "playback_hook package=$packageName disabled_or_not_selected")
                            return@runCatching
                        }
                        var installed = 0
                        installed += hookMediaPlayer(module, packageName)
                        installed += hookExoPlayer(module, packageName, classLoader)
                        Log.i("NeXtep",
                            "playback_hook package=$packageName installed=$installed " +
                                "pid=${Process.myPid()} mode=DIAGNOSTIC_ONLY")
                    }.onFailure { error ->
                        Log.w("NeXtep",
                            "playback_hook package=$packageName unavailable=${error.javaClass.simpleName}",
                            error)
                    }
                }, "NeXtep-PlaybackHook").apply { isDaemon = true }.start()
                return result
            }
        })
    }

    private fun hookMediaPlayer(module: XposedModule, packageName: String): Int {
        val method = MediaPlayer::class.java.getDeclaredMethod("pause")
        hookObserved(module, method, packageName, "MediaPlayer.pause", observeFalse = false)
        return 1
    }

    private fun hookExoPlayer(module: XposedModule, packageName: String,
                             classLoader: ClassLoader): Int {
        var count = 0
        for (name in listOf("androidx.media3.exoplayer.ExoPlayerImpl",
                            "com.google.android.exoplayer2.ExoPlayerImpl")) {
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?: continue
            for (method in clazz.declaredMethods) {
                val isPause = method.name == "pause" && method.parameterCount == 0
                val isPlayFlag = method.name == "setPlayWhenReady" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType
                if (!isPause && !isPlayFlag) continue
                if (runCatching {
                    hookObserved(module, method, packageName, "${clazz.simpleName}.${method.name}",
                        observeFalse = isPlayFlag)
                }.isSuccess) count += 1
            }
        }
        return count
    }

    private fun hookObserved(module: XposedModule, method: Method,
                             packageName: String, kind: String, observeFalse: Boolean) {
        method.isAccessible = true
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val shouldLog = !observeFalse || chain.args.firstOrNull() == false
                if (shouldLog) {
                    // Log only the nearest app-owned class: no media URLs,
                    // account IDs, player objects or full stack traces.
                    val source = Thread.currentThread().stackTrace
                        .firstOrNull { it.className.startsWith(packageName + ".") }
                        ?.className?.take(120) ?: "unknown"
                    Log.i("NeXtep",
                        "playback_hook package=$packageName event=$kind source=$source " +
                            "advice=correlate_with_floating_slot_and_audio_focus")
                }
                return chain.proceed()
            }
        })
    }
}
