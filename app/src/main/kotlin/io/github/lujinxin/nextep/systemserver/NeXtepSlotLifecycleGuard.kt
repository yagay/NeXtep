package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional ColorOS 17 system_server guard adapted from YMiniGuard.
 *
 * Unlike the OPlus FlexibleWindow implementation, this guard identifies
 * NeXtep-owned VirtualDisplays. It does not hook applications, disable the
 * keyguard, or change the physical display power state.
 *
 * All lifecycle overrides are fail-open and restricted to NeXtep virtual
 * displays while the physical screen is non-interactive. A separate live
 * Task/UID check gates background freezer protection.
 */
internal object NeXtepSlotLifecycleGuard {
    private const val TAG = "NeXtepSlotGuard"
    private val handler = Handler(Looper.getMainLooper())
    private val observerStarted = AtomicBoolean(false)
    private val observerQueued = AtomicBoolean(false)
    private val trackedTasks = ConcurrentHashMap<Int, WeakReference<Any>>()
    private val lastEvents = ConcurrentHashMap<String, Long>()
    @Volatile private var context: Context? = null
    @Volatile private var enabled = false

    fun install(module: XposedModule, loader: ClassLoader) {
        val fragment = loader.loadClass("com.android.server.wm.TaskFragment")
        val activity = loader.loadClass("com.android.server.wm.ActivityRecord")
        var installed = 0

        // Observe moves onto/off NeXtep displays; never hold a Task strongly.
        activity.declaredMethods.filter {
            it.name == "onDisplayChanged" && it.parameterCount == 1
        }.forEach { method ->
            hook(module, method) { chain ->
                val result = chain.proceed()
                runCatching { trackActivity(chain.thisObject) }
                    .onFailure { logOnce("tracking-error", "Task tracking failed: $it") }
                result
            }
            installed++
        }

        fragment.declaredMethods.filter { method ->
            method.name == "startPausing" && method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.any { it == String::class.java }
        }.forEach { method ->
            hook(module, method) { chain ->
                val fragmentObject = chain.thisObject
                val sleepingArg = chain.args.any { it is Boolean && it } &&
                    chain.args.any { it is String && it.contains("sleep", ignoreCase = true) }
                // ColorOS also supplies a dedicated uiSleeping boolean. Never
                // suppress a normal same-app navigation or a user-driven pause.
                val booleans = chain.args.filterIsInstance<Boolean>()
                val uiSleeping = if (booleans.size >= 2) booleans[1] else sleepingArg
                if (fragmentObject != null && shouldGuard(fragmentObject, uiSleeping)) {
                    logOnce("PAUSE:${taskId(fragmentObject)}",
                        "SLOT_PAUSE_BLOCK task=${taskId(fragmentObject)}")
                    false
                } else chain.proceed()
            }
            installed++
        }

        activity.declaredMethods.filter {
            it.name == "stopIfPossible" && it.returnType == Void.TYPE
        }.forEach { method ->
            hook(module, method) { chain ->
                val record = chain.thisObject
                if (record != null && !isFinishing(record) && shouldGuard(record)) {
                    logOnce("STOP:${taskId(record)}",
                        "SLOT_STOP_BLOCK task=${taskId(record)}")
                    null
                } else chain.proceed()
            }
            installed++
        }

        fragment.declaredMethods.filter {
            it.name == "sleepIfPossible" && it.returnType == Boolean::class.javaPrimitiveType
        }.forEach { method ->
            hook(module, method) { chain ->
                val fragmentObject = chain.thisObject
                val forceSleep = chain.args.any { it == true }
                if (!forceSleep && fragmentObject != null && shouldGuard(fragmentObject)) {
                    logOnce("SLEEP:${taskId(fragmentObject)}",
                        "SLOT_SLEEP_BLOCK task=${taskId(fragmentObject)}")
                    true
                } else chain.proceed()
            }
            installed++
        }

        // Freezer and UID-stop suppression is additionally gated by a LIVE
        // ActivityRecord in the NeXtep display, not merely a stale package name.
        runCatching {
            val hans = loader.loadClass("com.android.server.hans.freeze.HansCGroup")
            hans.declaredMethods.filter {
                it.name == "hansFreezeLocked" && it.returnType == Boolean::class.javaPrimitiveType
            }.forEach { method ->
                hook(module, method) { chain ->
                    val uid = chain.args.filterIsInstance<Int>().firstOrNull { it >= 10000 }
                    if (uid != null && liveProtectedUid(uid)) {
                        logOnce("HANS:$uid", "SLOT_HANS_FREEZE_BLOCK uid=$uid")
                        false
                    } else chain.proceed()
                }
                installed++
            }
        }.onFailure { Log.i(TAG, "Hans hook unavailable: ${it.javaClass.simpleName}") }

        runCatching {
            val optimizer = loader.loadClass("com.android.server.am.CachedAppOptimizer")
            optimizer.declaredMethods.filter {
                it.name == "freezeAppAsyncInternalLSP" && it.returnType == Void.TYPE
            }.forEach { method ->
                hook(module, method) { chain ->
                    val process = chain.args.firstOrNull { arg ->
                        arg?.javaClass?.name?.endsWith(".ProcessRecord") == true
                    }
                    val uid = process?.let(::processUid)
                    if (uid != null && liveProtectedUid(uid)) {
                        logOnce("AOSP:$uid", "SLOT_AOSP_FREEZE_BLOCK uid=$uid")
                        null
                    } else chain.proceed()
                }
                installed++
            }
        }.onFailure { Log.i(TAG, "CachedAppOptimizer hook unavailable: ${it.javaClass.simpleName}") }

        runCatching {
            val ams = loader.loadClass("com.android.server.am.ActivityManagerService")
            ams.declaredMethods.filter {
                it.name == "doStopUidLocked" && it.returnType == Void.TYPE
            }.forEach { method ->
                hook(module, method) { chain ->
                    val uid = chain.args.filterIsInstance<Int>().firstOrNull { it >= 10000 }
                    if (uid != null && liveProtectedUid(uid)) {
                        logOnce("UID:$uid", "SLOT_STOP_UID_BLOCK uid=$uid")
                        null
                    } else chain.proceed()
                }
                installed++
            }
        }.onFailure { Log.i(TAG, "Stop UID hook unavailable: ${it.javaClass.simpleName}") }

        Log.i(TAG, "Installed scoped ColorOS slot lifecycle hooks=$installed; mode awaits trusted SystemUI setting")
        Log.i("NeXtep", "NeXtep scoped lifecycle hook count=$installed")
    }

    private fun hook(
        module: XposedModule, method: Method,
        block: (XposedInterface.Chain) -> Any?,
    ) {
        method.isAccessible = true
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? =
                try { block(chain) } catch (error: Throwable) {
                    // Never retry proceed() after it has already been called;
                    // the hook's own lookups are all fail-closed below.
                    Log.e(TAG, "Hook failure ${method.name}: ${error.javaClass.simpleName}", error)
                    throw error
                }
        })
    }

    private fun trackActivity(record: Any?) {
        if (record == null) return
        queueObserver(record)
        val id = taskId(record)
        if (id < 0) return
        if (isNeXtepSlot(record) && !isFinishing(record)) {
            trackedTasks[id] = WeakReference(record)
        } else {
            trackedTasks.remove(id)
        }
        if (trackedTasks.size > 32) cleanup()
    }

    private fun shouldGuard(owner: Any, explicitSleep: Boolean = false): Boolean {
        queueObserver(owner)
        return enabled && isNeXtepSlot(owner) &&
            (explicitSleep || physicalScreenAsleep()) &&
            !isFinishing(owner)
    }

    private fun liveProtectedUid(uid: Int): Boolean {
        if (!enabled || !physicalScreenAsleep() || uid < 10000) return false
        val iterator = trackedTasks.entries.iterator()
        while (iterator.hasNext()) {
            val (_, ref) = iterator.next()
            val record = ref.get()
            if (record == null || isFinishing(record) || !isNeXtepSlot(record)) {
                iterator.remove()
                continue
            }
            if (recordUid(record) == uid) return true
        }
        return false
    }

    private fun cleanup() {
        trackedTasks.entries.removeIf { (_, ref) ->
            val activity = ref.get()
            activity == null || isFinishing(activity) || !isNeXtepSlot(activity)
        }
    }

    private fun physicalScreenAsleep(): Boolean =
        context?.getSystemService(PowerManager::class.java)?.isInteractive == false

    private fun queueObserver(owner: Any) {
        if (observerStarted.get() || !observerQueued.compareAndSet(false, true)) return
        val ctx = runCatching {
            val service = field(owner, "mAtmService") ?: field(owner, "mWmService")
            field(service, "mContext") as? Context
                ?: field(service, "mUiContext") as? Context
        }.getOrNull()
        if (ctx == null) {
            observerQueued.set(false)
            return
        }
        handler.post {
            try {
                if (observerStarted.get()) return@post
                val resolver = ctx.contentResolver
                val key = SystemServerWorkspaceBridge.INDEPENDENT_SLOT_LOCK_GUARD_KEY
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        refresh(ctx)
                    }
                }
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
                context = ctx
                observerStarted.set(true)
                refresh(ctx)
                Log.i(TAG, "Registered trusted independent-slot setting observer")
            } catch (error: Throwable) {
                Log.w(TAG, "Policy observer unavailable; fail closed", error)
            } finally {
                observerQueued.set(false)
            }
        }
    }

    private fun refresh(ctx: Context) {
        val next = runCatching {
            Settings.Global.getInt(ctx.contentResolver,
                SystemServerWorkspaceBridge.INDEPENDENT_SLOT_LOCK_GUARD_KEY, 0) == 1
        }.getOrDefault(false)
        if (enabled != next) {
            enabled = next
            Log.i(TAG, "SLOT_LOCK_POLICY enabled=$next")
        }
    }

    private fun isNeXtepSlot(owner: Any): Boolean {
        val display = call(owner, "getDisplayContent") ?: field(owner, "mDisplayContent")
            ?: return false
        val info = call(display, "getDisplayInfo") ?: return false
        val name = field(info, "name") as? String ?: return false
        if (!name.startsWith("NeXtep-slot-")) return false
        val type = field(info, "type") as? Int
        val ownerUid = field(info, "ownerUid") as? Int
        return (type == null || type == 5) && (ownerUid == null || ownerUid == 1000)
    }

    private fun taskId(owner: Any): Int {
        val task = call(owner, "getTask") ?: return -1
        return (call(task, "getTaskId") as? Int)
            ?: (field(task, "mTaskId") as? Int) ?: -1
    }

    private fun isFinishing(owner: Any): Boolean =
        field(owner, "finishing") == true

    private fun recordUid(record: Any): Int? {
        val info = field(record, "info") as? ActivityInfo
            ?: field(record, "mInfo") as? ActivityInfo
        return info?.applicationInfo?.uid ?: call(record, "getUid") as? Int
    }

    private fun processUid(process: Any): Int? =
        (field(process, "uid") as? Int) ?: (field(process, "mUid") as? Int)
            ?: (call(process, "getUid") as? Int)

    private fun field(target: Any?, name: String): Any? {
        if (target == null) return null
        return generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.let { runCatching {
                it.isAccessible = true
                it.get(target)
            }.getOrNull() }
    }

    private fun call(target: Any?, name: String): Any? {
        if (target == null) return null
        return generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.let { runCatching {
                it.isAccessible = true
                it.invoke(target)
            }.getOrNull() }
    }

    private fun logOnce(key: String, message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastEvents[key]
        if (last != null && now - last < 2500) return
        lastEvents[key] = now
        if (lastEvents.size > 80) lastEvents.clear()
        Log.i(TAG, message)
    }
}
