package io.github.lujinxin.nextep.xposed

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.content.Context
import android.media.MediaPlayer
import android.media.AudioManager
import android.net.Uri
import android.os.Process
import android.os.PowerManager
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.WeakHashMap

/**
 * Source diagnostics for explicitly selected player apps. An experimental,
 * short-lived screen-off pause guard is restricted to official Hongguo and
 * only previously observed active playback. Other apps remain passive.
 *
 * An opt-in is checked against the module's read-only ContentProvider, which
 * confirms the caller UID and selected package. Fail closed when unavailable.
 */
internal object PlaybackCompatAppHook {
    private const val POLICY_URI = "content://io.github.lujinxin.nextep.playback.policy"
    private val attemptedPackages = ConcurrentHashMap.newKeySet<String>()
    private const val HONGGUO = "com.phoenix.read"
    private const val LOCK_GUARD_MS = 3_500L
    private val playingObjects = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    @Volatile private var screenOffAt = -1L

    private fun watchScreenTransitions(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.elapsedRealtime()
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> screenOffAt = -1L
                }
                Log.i("NeXtep",
                    "hongguo_screen_transition action=${intent.action} " +
                        "at=${SystemClock.elapsedRealtime()}")
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun mayGuardLockPause(packageName: String, context: Context, player: Any?): Boolean {
        if (packageName != HONGGUO || player == null || playingObjects[player] != true) return false
        val now = SystemClock.elapsedRealtime()
        val age = now - screenOffAt
        return screenOffAt >= 0L && age in 0L..LOCK_GUARD_MS &&
            context.getSystemService(PowerManager::class.java)?.isInteractive == false
    }

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
                        if (packageName == HONGGUO) {
                            watchScreenTransitions(context)
                        }
                        var installed = 0
                        installed += hookActivityLifecycle(module, packageName, context)
                        installed += hookMediaPlayer(module, packageName, context)
                        installed += hookExoPlayer(module, packageName, classLoader, context)
                        Log.i("NeXtep",
                            "playback_hook package=$packageName installed=$installed " +
                                "pid=${Process.myPid()} mode=${if (packageName == HONGGUO) "HONGGUO_LOCK_GUARD" else "DIAGNOSTIC_ONLY"}")
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

    /**
     * Diagnose whether the player is stopped by display/Activity lifecycle
     * during lock. Do not modify lifecycle results or suppress app callbacks.
     */
    private fun hookActivityLifecycle(
        module: XposedModule, packageName: String, context: Context,
    ): Int {
        var installed = 0
        for (event in listOf("onPause", "onStop", "onResume")) {
            val method = Activity::class.java.getDeclaredMethod(event).apply {
                isAccessible = true
            }
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val activity = chain.thisObject as? Activity
                    val interactive = context.getSystemService(PowerManager::class.java)
                        ?.isInteractive
                    val keyguard = context.getSystemService(KeyguardManager::class.java)
                        ?.isKeyguardLocked
                    if (activity?.packageName == packageName &&
                        (interactive == false || keyguard == true)) {
                        Log.i("NeXtep",
                            "playback_lifecycle package=$packageName event=$event " +
                                "activity=${activity.javaClass.name.take(150)} " +
                                "displayId=${activity.display?.displayId} " +
                                "interactive=$interactive keyguard=$keyguard")
                    }
                    return chain.proceed()
                }
            })
            installed++
        }
        return installed
    }

    private fun hookMediaPlayer(
        module: XposedModule, packageName: String, context: Context,
    ): Int {
        var count = 0
        val clazz = MediaPlayer::class.java
        for (name in listOf("start", "pause", "stop", "reset")) {
            val method = clazz.getDeclaredMethod(name)
            hookObserved(module, method, packageName, "MediaPlayer.$name",
                context = context, observeFalse = false,
                starts = name == "start", pauses = name != "start")
            count++
        }
        return count
    }

    private fun hookExoPlayer(module: XposedModule, packageName: String,
                             classLoader: ClassLoader, context: Context): Int {
        var count = 0
        for (name in listOf("androidx.media3.exoplayer.ExoPlayerImpl",
                            "com.google.android.exoplayer2.ExoPlayerImpl")) {
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?: continue
            for (method in clazz.declaredMethods) {
                val isPause = method.name == "pause" && method.parameterCount == 0
                val isPlay = method.name == "play" && method.parameterCount == 0
                val isPlayFlag = method.name == "setPlayWhenReady" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType
                val isStop = method.name == "stop" && method.parameterCount == 0
                if (!isPause && !isPlay && !isPlayFlag && !isStop) continue
                if (runCatching {
                    hookObserved(module, method, packageName, "${clazz.simpleName}.${method.name}",
                        context = context, observeFalse = isPlayFlag,
                        starts = isPlay || (isPlayFlag),
                        pauses = isPause || isStop || isPlayFlag)
                }.isSuccess) count += 1
            }
        }
        return count
    }

    private fun hookObserved(module: XposedModule, method: Method,
                             packageName: String, kind: String,
                             context: Context, observeFalse: Boolean,
                             starts: Boolean, pauses: Boolean) {
        method.isAccessible = true
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val player = chain.thisObject
                val flag = if (observeFalse) chain.args.firstOrNull() as? Boolean else null
                val isStarting = starts && (!observeFalse || flag == true)
                val isPausing = pauses && (!observeFalse || flag == false)
                val guarding = isPausing && mayGuardLockPause(packageName, context, player)
                if (isPausing || (packageName == HONGGUO && isStarting)) {
                    val source = Thread.currentThread().stackTrace
                        .firstOrNull { it.className.startsWith(packageName + ".") }
                        ?.className?.take(120) ?: "unknown"
                    val interactive = context.getSystemService(PowerManager::class.java)
                        ?.isInteractive
                    val locked = context.getSystemService(KeyguardManager::class.java)
                        ?.isKeyguardLocked
                    val audioMode = context.getSystemService(AudioManager::class.java)?.mode
                    Log.i("NeXtep",
                        "playback_hook package=$packageName event=$kind source=$source " +
                            "interactive=$interactive keyguard=$locked audioMode=$audioMode " +
                            "observedActive=${playingObjects[player] == true} " +
                            "lockGuard=$guarding screenOffAgeMs=${SystemClock.elapsedRealtime()-screenOffAt}")
                }
                // Only guard an already-playing official Hongguo instance from
                // a brief lock-induced pause. Never change Activity callbacks,
                // stop/reset semantics, audio focus or unrelated players.
                if (guarding && (method.name == "pause" ||
                    (method.name == "setPlayWhenReady" && flag == false))) {
                    Log.i("NeXtep", "hongguo_lock_pause_deferred method=${method.name}")
                    return null
                }
                val result = chain.proceed()
                if (packageName == HONGGUO && player != null) {
                    if (isStarting) playingObjects[player] = true
                    else if (isPausing) playingObjects[player] = false
                }
                return result
            }
        })
    }
}
