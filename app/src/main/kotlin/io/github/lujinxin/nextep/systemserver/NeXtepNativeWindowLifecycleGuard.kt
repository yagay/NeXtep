package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * OEM native zoom lock guard, separate from NeXtepSlotLifecycleGuard.
 * Targets only SystemUI-registered taskId+UID pairs, and independently
 * verifies the live ActivityRecord's task, UID and OEM window scenario.
 *
 * Does not change keyguard visibility, physical display power or freezer.
 * Fail-open: unknown task, UID, scenario or policy means original WM behavior.
 */
internal object NeXtepNativeWindowLifecycleGuard {
    internal const val STATUS_KEY = "nextep_native_lock_diagnostics_v1"
    private const val TAG = "NeXtepNativeLock"
    private const val MAX_REGISTRY_AGE_MS = 12 * 60 * 60 * 1000L
    private data class Identity(val uid: Int, val createdAt: Long)
    private data class LockSession(val uid: Int, val startedAt: Long)
    private val lockSessions = java.util.concurrent.ConcurrentHashMap<Int, LockSession>()
    private val sessionsArmed = AtomicInteger()
    private val sessionsRejected = AtomicInteger()
    @Volatile private var identities: Map<Int, Identity> = emptyMap()
    @Volatile private var enabled = false
    @Volatile private var context: Context? = null
    @Volatile private var autoDisabled = false
    @Volatile private var installedHooks = 0
    @Volatile private var lastEvent = "installing"
    @Volatile private var armUntil = 0L
    private val errorCount = AtomicInteger()
    private val prevented = AtomicInteger()
    private val invisiblePauseBlocked = AtomicInteger()
    private val invisiblePauseObserved = AtomicInteger()
    private val stateTransitions = AtomicInteger()
    private val lockCallbackEntered = AtomicInteger()
    private val demotionObserved = AtomicInteger()
    private val observerStarted = AtomicBoolean()
    private val observerQueued = AtomicBoolean()
    private val handler = Handler(Looper.getMainLooper())

    fun install(module: XposedModule, loader: ClassLoader) {
        val activity = loader.loadClass("com.android.server.wm.ActivityRecord")
        val fragment = loader.loadClass("com.android.server.wm.TaskFragment")
        var installed = 0
        activity.declaredMethods.filter { it.name == "setState" && it.parameterCount >= 1 }
            .forEach { method ->
                hook(module, method) { chain ->
                    // Never suppress actual app-initiated lifecycle changes.
                    val result = chain.proceed()
                    val record = chain.thisObject
                    requestObserver(record)
                    if (record != null && identities.containsKey(taskId(record)) &&
                        (screenOff() || SystemClock.elapsedRealtime() < armUntil)) {
                        stateTransitions.incrementAndGet()
                        status("NATIVE_ACTIVITY_STATE task=${taskId(record)} state=${chain.args.firstOrNull()}")
                    }
                    result
                }
                installed++
            }

        activity.declaredMethods.filter {
            it.name == "stopIfPossible" && it.returnType == Void.TYPE
        }.forEach { method ->
            hook(module, method) { chain ->
                val owner = chain.thisObject
                requestObserver(owner)
                if (safeEligible(owner) && !finishing(owner)) {
                    prevented.incrementAndGet()
                    status("NATIVE_STOP_BLOCK task=${taskId(owner)}")
                    null
                } else chain.proceed()
            }
            installed++
        }

        fragment.declaredMethods.filter {
            it.name == "sleepIfPossible" && it.returnType == Boolean::class.javaPrimitiveType
        }.forEach { method ->
            hook(module, method) { chain ->
                val owner = chain.thisObject
                requestObserver(owner)
                // Explicit forced sleep must always be honored.
                if (chain.args.any { it == true }) return@hook chain.proceed()
                if (safeEligible(owner)) {
                    prevented.incrementAndGet()
                    status("NATIVE_SLEEP_BLOCK task=${taskId(owner)}")
                    true
                } else chain.proceed()
            }
            installed++
        }

        fragment.declaredMethods.filter {
            it.name == "startPausing" &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.any { parameter -> parameter == String::class.java }
        }.forEach { method ->
            hook(module, method) { chain ->
                val owner = chain.thisObject
                requestObserver(owner)
                // Restrict to UI-sleep pauses, not normal multitasking.
                val bools = chain.args.filterIsInstance<Boolean>()
                val uiSleeping = if (bools.size >= 2) bools[1]
                    else chain.args.filterIsInstance<String>().any {
                        it.contains("sleep", ignoreCase = true)
                    }
                if (uiSleeping && safeEligible(owner)) {
                    prevented.incrementAndGet()
                    status("NATIVE_PAUSE_BLOCK task=${taskId(owner)}")
                    false
                } else chain.proceed()
            }
            installed++
        }

        // Observe only. Skipping this OEM callback would affect ALL native
        // windows and potentially the lockscreen itself.
        runCatching {
            val flexible = loader.loadClass("com.android.server.wm.FlexibleTaskController")
            flexible.declaredMethods.filter {
                it.name == "onScreenLockedChanged" ||
                    it.name == "notifyKeyguardStateChanged"
            }.forEach { method ->
                hook(module, method) { chain ->
                    requestObserver(chain.thisObject)
                    val bools = chain.args.filterIsInstance<Boolean>()
                    val locking = when (method.name) {
                        "onScreenLockedChanged" -> bools.any { it }
                        "notifyKeyguardStateChanged" -> bools.size >= 2 && bools[1]
                        else -> false
                    }
                    if (locking) {
                        armUntil = SystemClock.elapsedRealtime() + 3_000L
                        lockCallbackEntered.incrementAndGet()
                        status("OEM_LOCK_PREARM source=${method.name} registered=${identities.size}")
                    }
                    if (method.name == "notifyKeyguardStateChanged" &&
                        bools.size >= 2 && !bools[1]) {
                        armUntil = 0L
                        lockSessions.clear()
                        status("NATIVE_LOCK_SESSIONS_CLEARED reason=unlock")
                    }
                    // This firmware posts some lock work asynchronously and
                    // runs keyguard demotion through another method. A
                    // thread-local visibility filter would miss that work.
                    chain.proceed()
                }
                installed++
            }
        }.onFailure { Log.w(TAG, "OEM lock observation unavailable (lifecycle hooks remain)", it) }

        // Verified from the user's oplus-services.jar, classes3.dex:
        // ActivityRecordExtImpl.pauseSelfIfInvisibleResumedInFlexibleTask
        // calls TaskFragment.startPausing(...). This is the OEM's exact
        // self-pause path for a resumed but invisible flexible task.
        // Intercept its decision, NOT the global Activity visibility flag:
        // the secure keyguard must still be able to cover app surfaces.
        runCatching {
            val ext = loader.loadClass("com.android.server.wm.ActivityRecordExtImpl")
            ext.declaredMethods.filter { method ->
                method.name == "pauseSelfIfInvisibleResumedInFlexibleTask" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0].name == "com.android.server.wm.TaskFragment" &&
                    method.parameterTypes[1].name == "com.android.server.wm.ActivityRecord"
            }.forEach { method ->
                hook(module, method) { chain ->
                    val activity = chain.args.getOrNull(1)
                    requestObserver(activity)
                    if (activity != null && identities.containsKey(taskId(activity))) {
                        invisiblePauseObserved.incrementAndGet()
                        if (safeEligible(activity)) {
                            invisiblePauseBlocked.incrementAndGet()
                            prevented.incrementAndGet()
                            status("NATIVE_OEM_INVISIBLE_PAUSE_BLOCK task=${taskId(activity)}")
                            false
                        } else chain.proceed()
                    } else chain.proceed()
                }
                installed++
            }
        }.onFailure {
            Log.w(TAG, "OEM invisible-resumed pause hook unavailable", it)
        }

        // The OEM keyguard method confirmed in oplus-services.jar directly
        // demotes Task always-on-top state and rearranges task layers. Observe
        // only: skipping it could put app content over the secure lockscreen.
        runCatching {
            val flexible = loader.loadClass("com.android.server.wm.FlexibleTaskController")
            flexible.declaredMethods.filter {
                it.name == "applyKeyguardHideDemotionIfNeed" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].name == "com.android.server.wm.Task"
            }.forEach { method ->
                hook(module, method) { chain ->
                    val task = chain.args.firstOrNull()
                    val id = task?.let(::taskIdFromTask) ?: -1
                    if (identities.containsKey(id) && task != null) {
                        demotionObserved.incrementAndGet()
                        // This is the firmware's actual OEM demotion entry.
                        // Snapshot native identity BEFORE the system changes
                        // always-on-top/scenario flags; YMiniGuard keeps an
                        // analogous OEM session during lock.
                        latchBeforeDemotion(task)
                        status("NATIVE_OEM_KEYGUARD_DEMOTION task=$id " +
                            "sessionLatched=${lockSessions.containsKey(id)} preservedSecurity=true")
                    }
                    chain.proceed()
                }
                installed++
            }
        }.onFailure {
            Log.w(TAG, "OEM keyguard task demotion tracing unavailable", it)
        }

        installedHooks = installed
        requestObserver(null)
        status("NATIVE_GUARD_INSTALLED hooks=$installed (freeze hooks disabled)")
    }

    private fun hook(module: XposedModule, method: Method,
                     code: (XposedInterface.Chain) -> Any?) {
        method.isAccessible = true
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? = code(chain)
        })
    }

    /**
     * Only the OEM's keyguard demotion callback may create a temporary lock
     * session. Never trust a stale package name: resolve a live ActivityRecord
     * from this exact Task and compare its actual UID with the SystemUI
     * registered pair BEFORE the original keyguard transaction proceeds.
     */
    private fun latchBeforeDemotion(task: Any) {
        if (!enabled || autoDisabled) return
        val id = taskIdFromTask(task)
        val registration = identities[id] ?: return
        runCatching {
            val now = SystemClock.elapsedRealtime()
            // Never latch a task in a routine foreground/layout demotion.
            if (!screenOff() && now >= armUntil) return
            if (registration.createdAt > now ||
                now - registration.createdAt > MAX_REGISTRY_AGE_MS) return
            val activity = resolveActivity(task) ?: run {
                sessionsRejected.incrementAndGet()
                status("NATIVE_SESSION_NO_ACTIVITY task=$id")
                return
            }
            val info = (field(activity, "info") ?: field(activity, "mInfo")) as? ActivityInfo
                ?: return
            val uid = info.applicationInfo?.uid ?: return
            val display = call(task, "getDisplayContent") ?: return
            if (call(display, "getDisplayId") != 0 ||
                uid != registration.uid || uid < 10000 ||
                finishing(activity)) {
                sessionsRejected.incrementAndGet()
                status("NATIVE_SESSION_IDENTITY_REJECTED task=$id")
                return
            }
            val mode = call(task, "getWindowingMode") as? Int
            val cfg = call(activity, "getConfiguration")
                ?: field(activity, "mConfiguration")
            val extra = field(cfg, "mOplusExtraConfiguration")
            val scenario = (call(extra, "getScenario") ?: field(extra, "mScenario")) as? Int
            // On this OxygenOS build FlexibleWindow often reports mode=1.
            // YMiniGuard also recognizes a floating Task by bounded geometry;
            // check the current Task's smaller bounds instead of trusting
            // windowingMode=5, but never omit exact task/UID verification.
            val bounds = call(task, "getBounds") as? android.graphics.Rect
            val wc = field(call(task, "getConfiguration"), "windowConfiguration")
            val maxBounds = call(wc, "getMaxBounds") as? android.graphics.Rect
            val reducedBounds = bounds != null && maxBounds != null &&
                !bounds.isEmpty && !maxBounds.isEmpty && bounds != maxBounds
            val inOemList = inOplusFloatingList(task, id)
            if (mode != 100 && scenario != 1 && scenario != 2 &&
                !reducedBounds && !inOemList) {
                sessionsRejected.incrementAndGet()
                status("NATIVE_SESSION_NOT_FLEXIBLE task=$id mode=$mode " +
                    "scenario=$scenario geometry=$reducedBounds floatingList=$inOemList")
                return
            }
            lockSessions[id] = LockSession(uid, now)
            sessionsArmed.incrementAndGet()
            status("NATIVE_SESSION_LATCHED task=$id uid=$uid scenario=$scenario")
        }.onFailure { error ->
            val failures = errorCount.incrementAndGet()
            if (failures >= 3) autoDisabled = true
            status("NATIVE_SESSION_LATCH_ERROR task=$id errors=$failures")
            Log.w(TAG, "OEM lock snapshot failed open", error)
        }
    }

    /**
     * Source: actual Oplus Services classes3.dex and YMiniGuard's
     * OplusFlexibleWindowController. The OEM's own floating-list is a
     * secondary indicator when OxygenOS reports windowingMode=1.
     */
    private fun inOplusFloatingList(task: Any, id: Int): Boolean =
        runCatching {
            val clazz = Class.forName(
                "com.android.server.wm.FloatHandleController",
                false, task.javaClass.classLoader)
            val instance = clazz.getDeclaredMethod("getInstance").apply {
                isAccessible = true
            }.invoke(null) ?: return@runCatching false
            val method = clazz.getDeclaredMethod(
                "isInFloatingList", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
            method.invoke(instance, id) == true
        }.getOrDefault(false)

    private fun resolveActivity(owner: Any): Any? {
        if (owner.javaClass.name == "com.android.server.wm.ActivityRecord") return owner
        return field(owner, "mResumedActivity")
            ?: call(owner, "topRunningActivity")
            ?: call(owner, "getTopNonFinishingActivity")
            ?: call(owner, "getTopMostActivity")
    }

    private fun safeEligible(owner: Any?): Boolean {
        if (!enabled || autoDisabled || identities.isEmpty() || owner == null) return false
        val now = SystemClock.elapsedRealtime()
        if (!screenOff() && now >= armUntil) return false
        return try {
            val activity = resolveActivity(owner) ?: return false
            val task = call(activity, "getTask") ?: return false
            val id = taskId(activity)
            val registered = identities[id] ?: return false
            if (registered.createdAt > now ||
                now - registered.createdAt > MAX_REGISTRY_AGE_MS) return false
            val info = (field(activity, "info") ?: field(activity, "mInfo")) as? ActivityInfo
                ?: error("registered task=$id has no ActivityInfo")
            val uid = info.applicationInfo?.uid ?: return false
            if (uid < 10000 || uid != registered.uid) return false
            val display = call(task, "getDisplayContent")
                ?: error("registered task=$id has no DisplayContent")
            val displayId = call(display, "getDisplayId") as? Int
                ?: error("registered task=$id has no display ID")
            if (displayId != 0) return false
            if (finishing(activity)) return false
            val mode = call(task, "getWindowingMode") as? Int
            val configuration = call(activity, "getConfiguration")
                ?: field(activity, "mConfiguration")
            val extra = field(configuration, "mOplusExtraConfiguration")
            val scenario = (call(extra, "getScenario") ?: field(extra, "mScenario")) as? Int
            if (mode == null && scenario == null)
                error("registered task=$id lacks OEM window mode and scenario")
            val latched = lockSessions[id]?.let { session ->
                session.uid == uid && session.startedAt <= now &&
                    now - session.startedAt <= MAX_REGISTRY_AGE_MS
            } == true
            NativeWindowLockPolicy.eligible(
                enabled = enabled,
                autoDisabled = autoDisabled,
                expectedTaskId = id,
                actualTaskId = id,
                expectedUid = registered.uid,
                actualUid = uid,
                displayId = 0,
                windowMode = mode,
                vendorScenario = scenario,
                physicallyOff = screenOff(),
                lockPrearmed = now < armUntil,
                finishing = finishing(activity),
                verifiedLockSession = latched,
            )
        } catch (error: Throwable) {
            val errors = errorCount.incrementAndGet()
            if (errors >= 3) {
                autoDisabled = true
                status("NATIVE_AUTO_DISABLED errors=$errors cause=${error.javaClass.simpleName}")
            } else Log.w(TAG, "Fail-open native task guard lookup $errors/3", error)
            false
        }
    }

    private fun screenOff(): Boolean =
        context?.getSystemService(PowerManager::class.java)?.isInteractive == false

    private fun finishing(owner: Any?): Boolean =
        field(owner, "finishing") == true ||
            field(field(owner, "mResumedActivity"), "finishing") == true

    private fun taskIdFromTask(task: Any): Int =
        (call(task, "getTaskId") as? Int)
            ?: (field(task, "mTaskId") as? Int) ?: -1

    private fun taskId(owner: Any): Int {
        val task = call(owner, "getTask") ?: return -1
        return (call(task, "getTaskId") as? Int)
            ?: (field(task, "mTaskId") as? Int) ?: -1
    }

    private fun requestObserver(owner: Any?) {
        if (observerStarted.get() || !observerQueued.compareAndSet(false, true)) return
        val ctx = systemContext(owner)
        if (ctx == null) {
            observerQueued.set(false)
            return
        }
        handler.post {
            try {
                if (observerStarted.get()) return@post
                context = ctx
                val resolver = ctx.contentResolver
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = refresh()
                }
                for (key in listOf(SystemServerWorkspaceBridge.NATIVE_TASK_LOCK_GUARD_KEY,
                    SystemServerWorkspaceBridge.NATIVE_TASK_IDENTITIES_KEY)) {
                    resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
                }
                observerStarted.set(true)
                refresh()
                status("NATIVE_POLICY_OBSERVER_READY")
            } catch (error: Throwable) {
                status("NATIVE_POLICY_OBSERVER_FAILED ${error.javaClass.simpleName}")
            } finally {
                observerQueued.set(false)
            }
        }
    }

    private fun refresh() {
        val ctx = context ?: return
        runCatching {
            val resolver = ctx.contentResolver
            val allow = Settings.Global.getInt(resolver,
                SystemServerWorkspaceBridge.NATIVE_TASK_LOCK_GUARD_KEY, 0) == 1
            val now = SystemClock.elapsedRealtime()
            val raw = Settings.Global.getString(resolver,
                SystemServerWorkspaceBridge.NATIVE_TASK_IDENTITIES_KEY).orEmpty()
            val records = raw.split(';').mapNotNull { entry ->
                val parts = entry.split(':')
                if (parts.size != 3) return@mapNotNull null
                val id = parts[0].toIntOrNull() ?: return@mapNotNull null
                val uid = parts[1].toIntOrNull() ?: return@mapNotNull null
                val timestamp = parts[2].toLongOrNull() ?: return@mapNotNull null
                if (id <= 0 || uid < 10000 || timestamp <= 0 ||
                    timestamp > now || now - timestamp > MAX_REGISTRY_AGE_MS)
                    null else id to Identity(uid, timestamp)
            }.take(4).toMap()
            identities = if (allow) records else emptyMap()
            enabled = allow
            lockSessions.entries.removeIf { (id, session) ->
                !allow || records[id]?.uid != session.uid
            }
            status("NATIVE_POLICY enabled=$allow registered=${identities.size}")
        }.onFailure {
            identities = emptyMap()
            enabled = false
            status("NATIVE_POLICY_READ_FAILED ${it.javaClass.simpleName}")
        }
    }

    private fun systemContext(owner: Any?): Context? {
        val service = field(owner, "mAtmService") ?: field(owner, "mWmService")
        val direct = field(service, "mContext") as? Context
        if (direct != null) return direct
        return runCatching {
            val klass = Class.forName("android.app.ActivityThread")
            val thread = klass.getDeclaredMethod("currentActivityThread").apply {
                isAccessible = true
            }.invoke(null)
            klass.getDeclaredMethod("getSystemContext").apply {
                isAccessible = true
            }.invoke(thread) as? Context
        }.getOrNull()
    }

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

    private fun status(message: String) {
        lastEvent = message
        Log.i(TAG, message)
        val ctx = context ?: return
        handler.post {
            runCatching {
                Settings.Global.putString(ctx.contentResolver, STATUS_KEY,
                    "enabled=$enabled hooks=$installedHooks registered=${identities.size} " +
                        "blocked=${prevented.get()} invisiblePauseObserved=${invisiblePauseObserved.get()} " +
                        "invisiblePauseBlocked=${invisiblePauseBlocked.get()} " +
                        "keyguardDemotions=${demotionObserved.get()} " +
                        "activityTransitions=${stateTransitions.get()} lockCallbacks=${lockCallbackEntered.get()} " +
                        "lockSessions=${lockSessions.size} sessionsArmed=${sessionsArmed.get()} " +
                        "sessionsRejected=${sessionsRejected.get()} " +
                        "errors=${errorCount.get()} " +
                        "autoDisabled=$autoDisabled freezer=false last=$lastEvent")
            }
        }
    }
}
