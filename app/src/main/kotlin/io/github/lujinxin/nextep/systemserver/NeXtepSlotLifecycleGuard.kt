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
import io.github.lujinxin.nextep.workspace.PinnedTaskIdentityContract
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    internal const val STATUS_KEY = "nextep_slot_guard_diagnostics_v1"
    private const val PREARM_WINDOW_MS = 20_000L
    private const val PINNED_PRELOCK_WINDOW_MS = 2_000L
    @Volatile private var lastPinnedPowerKeyAt = -1L
    @Volatile private var pinnedIdentities = emptyMap<Int, PinnedTaskIdentityContract.Identity>()

    /** Called from the policy hook without locking WM or querying tasks. */
    fun onPhysicalPowerKeyDown() {
        lastPinnedPowerKeyAt = android.os.SystemClock.elapsedRealtime()
    }
    private val prearmedTasks = ConcurrentHashMap<Int, Long>()
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private val statusQueued = AtomicBoolean(false)
    @Volatile private var lastEvent = "installing"
    @Volatile private var installedCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val observerStarted = AtomicBoolean(false)
    private val observerQueued = AtomicBoolean(false)
    private val trackedTasks = ConcurrentHashMap<Int, WeakReference<Any>>()
    /** YMiniGuard-style background session keyed by actual Task identity. */
    private data class BackgroundSession(val uid: Int, val armedAt: Long)
    private val backgroundSessions = ConcurrentHashMap<Int, BackgroundSession>()
    private val lastEvents = ConcurrentHashMap<String, Long>()
    private val failureCount = AtomicInteger(0)
    @Volatile private var disabledByErrors = false
    @Volatile private var context: Context? = null
    @Volatile private var enabled = false
    @Volatile private var freezerEnabled = false
    @Volatile private var backgroundKeepaliveEnabled = false

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
                runCatching { trackActivity(chain.thisObject, "onDisplayChanged") }
                    .onFailure { logOnce("tracking-error", "Task tracking failed: $it") }
                result
            }
            installed++
        }

        // Capture the real Activity state after OEM transitions, including
        // PAUSED/STOPPED if a ColorOS path bypasses our candidate hooks.
        activity.declaredMethods.filter {
            it.name == "setState" && it.parameterCount >= 1
        }.forEach { method ->
            hook(module, method) { chain ->
                val result = chain.proceed()
                runCatching {
                    val record = chain.thisObject ?: return@runCatching
                    if (isTrackedEligible(record)) {
                        trackActivity(record, "setState")
                        val state = chain.args.firstOrNull()?.toString().orEmpty()
                        if (state.contains("RESUMED", ignoreCase = true)) {
                            backgroundSessions.remove(taskId(record))
                        }
                        if (state.contains("PAUS", true) || state.contains("STOP", true)
                            || state.contains("RESUM", true)) {
                            note("STATE", "SLOT_ACTIVITY_STATE task=${taskId(record)} state=$state")
                        }
                    }
                }
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
                if (fragmentObject != null) {
                    probe(fragmentObject, "startPausing")
                    if (uiSleeping) armForLock(fragmentObject, "startPausing")
                }
                val lockGuarded = fragmentObject != null &&
                    safeCondition { shouldGuard(fragmentObject) }
                val backgroundGuarded = fragmentObject != null &&
                    safeCondition { shouldGuardBackgroundPause(fragmentObject, chain.args) }
                if (lockGuarded || backgroundGuarded) {
                    note(if (backgroundGuarded && !lockGuarded) "BACKGROUND_PAUSE" else "PAUSE",
                        "SLOT_PAUSE_BLOCK task=${taskId(fragmentObject!!)} " +
                            "uiSleeping=$uiSleeping background=$backgroundGuarded " +
                            "reason=${chain.args.filterIsInstance<String>().firstOrNull().orEmpty().take(60)}")
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
                if (record != null) probe(record, "stopIfPossible")
                val lockGuarded = record != null &&
                    safeCondition { !isFinishing(record) && shouldGuard(record) }
                val backgroundGuarded = record != null &&
                    safeCondition { shouldGuardBackgroundStop(record) }
                if (lockGuarded || backgroundGuarded) {
                    call(record, "resumeKeyDispatchingLocked")
                    note(if (backgroundGuarded && !lockGuarded) "BACKGROUND_STOP" else "STOP",
                        "SLOT_STOP_BLOCK task=${taskId(record!!)} background=$backgroundGuarded")
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
                if (fragmentObject != null) {
                    probe(fragmentObject, "sleepIfPossible")
                    if (!forceSleep) armForLock(fragmentObject, "sleepIfPossible")
                }
                if (!forceSleep && fragmentObject != null && safeCondition {
                    // Never bypass physical Display 0 sleep: keyguard and power
                    // transitions must remain owned by Android, not this hook.
                    isNeXtepSlot(fragmentObject) && (shouldGuard(fragmentObject) ||
                        (shouldKeepBackgroundTask(fragmentObject) &&
                            (physicalScreenOff() || displaySleeping(fragmentObject))))
                }) {
                    note("SLEEP", "SLOT_SLEEP_BLOCK task=${taskId(fragmentObject)}")
                    true
                } else chain.proceed()
            }
            installed++
        }

        // ColorOS calls this OEM transition before it hides the window and
        // sends activity pause/stop. Arm only already tracked NeXtep tasks;
        // never alter the OEM keyguard callback or any other display.
        runCatching {
            val flexible = loader.loadClass("com.android.server.wm.FlexibleTaskController")
            flexible.declaredMethods.filter { method ->
                method.name == "onScreenLockedChanged" ||
                    method.name == "notifyKeyguardStateChanged"
            }.forEach { method ->
                hook(module, method) { chain ->
                    runCatching {
                        when (method.name) {
                            "onScreenLockedChanged" -> {
                                if (chain.args.filterIsInstance<Boolean>().any { it }) {
                                    armAllTracked("oplusScreenLocked")
                                }
                            }
                            "notifyKeyguardStateChanged" -> {
                                val showing = chain.args.filterIsInstance<Boolean>().getOrNull(1)
                                if (showing == true) armAllTracked("oplusKeyguardShowing")
                                if (showing == false) clearLockArms("oplusKeyguardHidden")
                            }
                        }
                    }.onFailure {
                        logOnce("SLOT_EARLY_LOCK_ERROR",
                            "SLOT_EARLY_LOCK_ERROR source=${method.name} type=${it.javaClass.simpleName}")
                    }
                    chain.proceed()
                }
                installed++
            }
        }.onFailure {
            Log.w(TAG, "OPlus early lock observer unavailable; lifecycle fallback remains", it)
        }

        // OEM-specific remote-playback stop interception confirmed in the
        // uploaded OOS17 oplus-services.jar classes3.dex. Unlike the failed
        // FlexibleWindow-only hook, this method accepts an ActivityRecord
        // directly; verify the exact NeXtep virtual display before returning
        // the OEM's normal "skip stop" decision.
        runCatching {
            val supervisorExt = loader.loadClass(
                "com.android.server.wm.ActivityTaskSupervisorExtImpl")
            supervisorExt.declaredMethods.filter {
                it.name == "skipStopActivityWhenRemotePlaying" &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].name == "com.android.server.wm.ActivityRecord"
            }.forEach { method ->
                hook(module, method) { chain ->
                    val record = chain.args.firstOrNull()
                    if (record != null && safeCondition {
                        shouldGuardBackgroundStop(record)
                    }) {
                        note("OEM_REMOTE_STOP",
                            "SLOT_OEM_REMOTE_STOP_BLOCK task=${taskId(record)}")
                        true
                    } else chain.proceed()
                }
                installed++
            }
        }.onFailure {
            Log.w(TAG, "OOS17 remote-playing stop hook unavailable", it)
        }

        // New OOS17 diagnostic-only pause decision: do not override its
        // return value until the real device log proves the decision is
        // involved in the virtual display's unwanted pause.
        runCatching {
            val fragmentExt = loader.loadClass("com.android.server.wm.TaskFragmentExtImpl")
            fragmentExt.declaredMethods.filter {
                it.name == "shouldPauseImmediately" &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterCount == 3 &&
                    it.parameterTypes[0].name == "com.android.server.wm.TaskFragment" &&
                    it.parameterTypes[1].name == "com.android.server.wm.ActivityRecord" &&
                    it.parameterTypes[2].name == "com.android.server.wm.ActivityRecord"
            }.forEach { method ->
                hook(module, method) { chain ->
                    val fragmentObject = chain.args.firstOrNull()
                    if (fragmentObject != null && safeCondition {
                        shouldKeepBackgroundTask(fragmentObject)
                    }) {
                        note("OEM_PAUSE_DECISION",
                            "SLOT_OEM_PAUSE_DECISION task=${taskId(fragmentObject)}")
                    }
                    chain.proceed()
                }
                installed++
            }
        }.onFailure {
            Log.w(TAG, "OOS17 pause-decision diagnostics unavailable", it)
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
                        ?: chain.args.firstOrNull { it?.javaClass?.name?.endsWith(".OplusHansPackage") == true }
                            ?.let { field(it, "uid") as? Int }
                    if (uid != null && safeCondition { liveProtectedUid(uid) }) {
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
                    if (uid != null && safeCondition { liveProtectedUid(uid) && !explicitTermination() }) {
                        logOnce("UID:$uid", "SLOT_STOP_UID_BLOCK uid=$uid")
                        null
                    } else chain.proceed()
                }
                installed++
            }
        }.onFailure { Log.i(TAG, "Stop UID hook unavailable: ${it.javaClass.simpleName}") }

        installedCount = installed
        // Subscribe as soon as the system context can be resolved. Previously
        // the guard waited for a later Activity callback, which could miss
        // the first lock transition after the module was enabled.
        // Failure remains non-fatal; subsequent task callbacks retry.
        queueObserver(this)
        recordStatus("installed")
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

    /** Bad OEM reflection fails open; three errors disable the guard until restart. */
    private inline fun safeCondition(block: () -> Boolean): Boolean {
        if (disabledByErrors) return false
        return try {
            block()
        } catch (error: Throwable) {
            if (failureCount.incrementAndGet() >= 3) {
                disabledByErrors = true
                Log.e(TAG, "SLOT_GUARD_AUTO_DISABLED after repeated hook lookup errors", error)
            } else {
                Log.w(TAG, "Slot guard lookup failed, original system behavior retained", error)
            }
            false
        }
    }

    private data class SlotIdentity(
        val displayId: Int,
        val name: String,
        val type: Int?,
        val ownerUid: Int?,
        val ownerPackage: String?,
    ) {
        val matched: Boolean get() =
            FloatingLockGuardScope.isEligibleDisplay(
                displayId, name, type, ownerUid, ownerPackage)
    }

    private fun slotIdentity(owner: Any): SlotIdentity? {
        val task = call(owner, "getTask")
        val display = call(owner, "getDisplayContent")
            ?: field(owner, "mDisplayContent")
            ?: call(task, "getDisplayContent")
            ?: field(task, "mDisplayContent")
            ?: return null
        val info = call(display, "getDisplayInfo") ?: field(display, "mDisplayInfo")
            ?: return null
        val displayId = (call(display, "getDisplayId") as? Int)
            ?: (field(info, "displayId") as? Int) ?: -1
        val name = field(info, "name") as? String ?: "<unknown>"
        return SlotIdentity(
            displayId, name, field(info, "type") as? Int,
            field(info, "ownerUid") as? Int,
            field(info, "ownerPackageName") as? String,
        )
    }

    private fun probe(owner: Any, source: String) {
        if (disabledByErrors) return
        queueObserver(owner)
        val identity = slotIdentity(owner)
        val id = taskId(owner)
        // Probe every NeXtep-labelled candidate, including failed owner or
        // display-type checks, without logging unrelated foreground apps.
        if (identity?.name?.startsWith("NeXtep") == true) {
            logOnce(
                "SLOT_PROBE:$source:$id:${identity.displayId}",
                "SLOT_GUARD_MATCH source=$source task=$id " +
                    "display=${identity.displayId} name=${identity.name} " +
                    "type=${identity.type} ownerUid=${identity.ownerUid} " +
                    "ownerPackage=${identity.ownerPackage} " +
                    "matched=${identity.matched} policy=$enabled " +
                    "matchReason=${when { identity.displayId <= 0 -> "display-id"; !identity.name.matches(Regex("NeXtep-floating-slot-[0-3]")) -> "display-name"; identity.type != 5 -> "display-type"; identity.ownerUid != 1000 && identity.ownerPackage != "com.android.systemui" -> "display-owner"; else -> "ok" }}",
            )
            if (identity.matched) recordStatus("matched:$source:$id")
        }
    }

    private fun trackActivity(record: Any?, source: String) {
        if (record == null) return
        queueObserver(record)
        probe(record, source)
        val id = taskId(record)
        if (id < 0) return
        if (isTrackedEligible(record) && !isFinishing(record)) {
            trackedTasks[id] = WeakReference(record)
            if (isPinnedTask(record) && slotIdentity(record)?.displayId == 0) {
                logOnce("PIN_MAIN_TRACK:$id",
                    "PIN_MAIN_TASK_TRACKED task=$id source=$source uid=${recordUid(record)}")
            } else {
                logOnce("SLOT_TRACK:$id", "SLOT_TASK_TRACKED task=$id source=$source uid=${recordUid(record)}")
            }
            recordStatus("tracked:$id")
        } else {
            trackedTasks.remove(id)
            prearmedTasks.remove(id)
            backgroundSessions.remove(id)
        }
        if (trackedTasks.size > 32) cleanup()
    }

    private fun armForLock(owner: Any, source: String) {
        if (!enabled || !isTrackedEligible(owner) || isFinishing(owner)) return
        val id = taskId(owner)
        if (id < 0) return
        val first = prearmedTasks.putIfAbsent(id, android.os.SystemClock.elapsedRealtime())
        if (first == null) note(
            "PREARM", "SLOT_LOCK_PREARM source=$source task=$id " +
                "display=${slotIdentity(owner)?.displayId}",
        )
        val resumed = field(owner, "mResumedActivity")
        if (resumed != null) trackActivity(resumed, "lockPrearm")
    }

    private fun armAllTracked(source: String) {
        if (!enabled || disabledByErrors) return
        var armed = 0
        val iter = trackedTasks.entries.iterator()
        while (iter.hasNext()) {
            val (id, ref) = iter.next()
            val record = ref.get()
            if (record == null || !isTrackedEligible(record) || isFinishing(record)) {
                iter.remove()
                prearmedTasks.remove(id)
                continue
            }
            val original = prearmedTasks.putIfAbsent(id, android.os.SystemClock.elapsedRealtime())
            if (original == null) armed++
        }
        note("EARLY", "SLOT_EARLY_LOCK source=$source armed=$armed tracked=${trackedTasks.size}")
    }

    private fun clearLockArms(source: String) {
        if (prearmedTasks.isEmpty()) return
        prearmedTasks.clear()
        note("UNLOCK", "SLOT_LOCK_ARMS_CLEARED source=$source")
    }

    private fun isPrearmed(owner: Any): Boolean {
        val id = taskId(owner)
        if (isPinnedTask(owner)) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastPinnedPowerKeyAt
            if (elapsed in 0..PINNED_PRELOCK_WINDOW_MS) return true
        }
        val armed = prearmedTasks[id] ?: return false
        val elapsed = android.os.SystemClock.elapsedRealtime() - armed
        if (elapsed in 0..PREARM_WINDOW_MS) return true
        prearmedTasks.remove(id, armed)
        return false
    }

    /** A lock pre-arm is only a short transition bridge. Once the physical
     * display is non-interactive, an eligible live slot remains protected for
     * the whole lock session, not just PREARM_WINDOW_MS. */
    private fun physicalScreenOff(): Boolean =
        context?.getSystemService(PowerManager::class.java)?.isInteractive == false

    private fun shouldGuard(owner: Any): Boolean {
        queueObserver(owner)
        // A previously prearmed virtual slot must not hijack Home/Recents
        // after its pinned Task is promoted to the physical display.
        // Display 0 is guarded only for the real power-key lock transition.
        if (isPinnedTask(owner) && slotIdentity(owner)?.displayId == 0) {
            val sincePower = android.os.SystemClock.elapsedRealtime() - lastPinnedPowerKeyAt
            return enabled && !disabledByErrors && !isFinishing(owner) &&
                (physicalScreenOff() || sincePower in 0L..PINNED_PRELOCK_WINDOW_MS)
        }
        return SlotLockProtectionPolicy.eligible(
            enabled = enabled && !disabledByErrors,
            matchingSlot = isTrackedEligible(owner),
            finishing = isFinishing(owner),
            physicallyOff = physicalScreenOff(),
            displaySleeping = displaySleeping(owner),
            prearmed = isPrearmed(owner),
        )
    }

    /** Unpinned tasks retain the original virtual-display-only opt-in.
     * Explicit pins are protected on their exact Task even on Display 0. */
    private fun shouldKeepBackgroundTask(owner: Any): Boolean =
        !disabledByErrors && !isFinishing(owner) && taskId(owner) > 0 &&
            ((backgroundKeepaliveEnabled && isNeXtepSlot(owner)) ||
                (enabled && isPinnedTask(owner)))

    private fun resumedActivity(owner: Any): Any? =
        if (owner.javaClass.name == "com.android.server.wm.ActivityRecord") owner
        else field(owner, "mResumedActivity")
            ?: call(owner, "topRunningActivity")
            ?: call(owner, "getTopNonFinishingActivity")

    private fun activityPackage(record: Any?): String? {
        val info = field(record, "info") ?: field(record, "mInfo")
        return (field(info, "packageName") as? String)
            ?: (field(field(info, "applicationInfo"), "packageName") as? String)
    }

    /**
     * YMiniGuard's backgroundProtected policy:
     *  - different app, explicit user leave, recents transition or sleep
     *  - do not intercept navigation inside the protected package or finishing
     *  - remember the verified Task/UID for following stop/freeze callbacks
     */
    private fun shouldGuardBackgroundPause(owner: Any, args: List<Any?>): Boolean {
        if (!shouldKeepBackgroundTask(owner)) return false
        val activity = resumedActivity(owner) ?: return false
        if (isFinishing(activity)) {
            backgroundSessions.remove(taskId(owner))
            return false
        }
        val uid = recordUid(activity)?.takeIf { it >= 10000 } ?: return false
        val task = taskId(owner)
        val reason = args.filterIsInstance<String>().firstOrNull().orEmpty()
        val bools = args.filterIsInstance<Boolean>()
        val userLeaving = bools.firstOrNull() == true
        val uiSleeping = if (bools.size >= 2) bools[1] else false
        val ownPkg = activityPackage(activity)
        val other = args.firstOrNull {
            it != null && it.javaClass.name == "com.android.server.wm.ActivityRecord"
        }
        val otherPkg = activityPackage(other)
        if (otherPkg != null && ownPkg == otherPkg) {
            backgroundSessions.remove(task)
            return false
        }
        val switchingApp = otherPkg != null && ownPkg != null && ownPkg != otherPkg
        val recents = reason.contains("pauseInRecentsAnim", true) ||
            reason.contains("pauseBackTasks", true)
        val alreadyArmed = backgroundSessions[task]?.uid == uid
        if (!alreadyArmed && !switchingApp && !userLeaving &&
            !uiSleeping && !recents && !physicalScreenOff()) return false
        backgroundSessions[task] = BackgroundSession(uid, android.os.SystemClock.elapsedRealtime())
        trackActivity(activity, "background-armed")
        // Preventing normal PAUSE on the physical display can deadlock task
        // switching, recents and keyguard. Allow regular Display 0 pause;
        // stop/UID-freeze remain gated by the verified pinned Task.
        val pinnedMain = isPinnedTask(owner) && slotIdentity(owner)?.displayId == 0
        if (pinnedMain) {
            logOnce("PIN_MAIN_PAUSE:$task",
                "PIN_MAIN_BACKGROUND_ARMED task=$task uid=$uid reason=${reason.take(60)}")
        }
        return !pinnedMain
    }

    private fun shouldGuardBackgroundStop(owner: Any): Boolean {
        if (!shouldKeepBackgroundTask(owner)) return false
        // Normal physical-display Home/app switching must be free to STOP
        // the Activity. The verified pinned UID still receives freezer
        // protection so stopping the UI need not kill its playback process.
        if (isPinnedTask(owner) && slotIdentity(owner)?.displayId == 0 &&
            !shouldGuard(owner)) return false
        val record = resumedActivity(owner) ?: return false
        val id = taskId(owner)
        val uid = recordUid(record) ?: return false
        return backgroundSessions[id]?.uid == uid && !isFinishing(record)
    }

    private fun liveBackgroundUid(uid: Int): Boolean {
        if (disabledByErrors || uid < 10000 || (!backgroundKeepaliveEnabled && !enabled)) return false
        return trackedTasks.entries.any { (id, ref) ->
            val record = ref.get()
            record != null && taskId(record) == id &&
                !isFinishing(record) && shouldKeepBackgroundTask(record) &&
                recordUid(record) == uid
        }
    }

    private fun liveProtectedUid(uid: Int): Boolean {
        if (disabledByErrors || uid < 10000) return false
        if (liveBackgroundUid(uid)) return true
        if (!enabled || !freezerEnabled) return false
        val iterator = trackedTasks.entries.iterator()
        while (iterator.hasNext()) {
            val (id, ref) = iterator.next()
            val record = ref.get()
            if (record == null || isFinishing(record) || !isTrackedEligible(record)) {
                iterator.remove()
                prearmedTasks.remove(id)
                continue
            }
            if (recordUid(record) == uid &&
                (physicalScreenOff() || displaySleeping(record) || isPrearmed(record))) return true
        }
        return false
    }

    private fun cleanup() {
        trackedTasks.entries.removeIf { (id, ref) ->
            val activity = ref.get()
            val stale = activity == null || isFinishing(activity) || !isTrackedEligible(activity)
            if (stale) {
                prearmedTasks.remove(id)
                backgroundSessions.remove(id)
            }
            stale
        }
    }

    private fun displaySleeping(owner: Any): Boolean {
        val task = call(owner, "getTask")
        val display = call(owner, "getDisplayContent")
            ?: field(owner, "mDisplayContent")
            ?: call(task, "getDisplayContent")
        return call(display, "isSleeping") == true ||
            call(owner, "shouldSleepActivities") == true
    }

    private fun explicitTermination(): Boolean =
        Thread.currentThread().stackTrace.any { frame ->
            frame.methodName.contains("forceStop", ignoreCase = true) ||
                frame.methodName.contains("shuttingDown", ignoreCase = true) ||
                frame.className.contains("PackageInstaller")
        }

    private fun queueObserver(owner: Any) {
        if (observerStarted.get() || !observerQueued.compareAndSet(false, true)) return
        val ctx = systemContext(owner)
        if (ctx == null) {
            observerQueued.set(false)
            logOnce("SLOT_CONTEXT_MISSING",
                "SLOT_POLICY_CONTEXT_MISSING source=${owner.javaClass.simpleName}")
            return
        }
        handler.post {
            try {
                if (observerStarted.get()) return@post
                val resolver = ctx.contentResolver
                val key = SystemServerWorkspaceBridge.INDEPENDENT_SLOT_LOCK_GUARD_KEY
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = refresh(ctx)
                }
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
                resolver.registerContentObserver(Settings.Global.getUriFor(
                    SystemServerWorkspaceBridge.NATIVE_TASK_FREEZER_KEY), false, observer)
                resolver.registerContentObserver(Settings.Global.getUriFor(
                    SystemServerWorkspaceBridge.FLOATING_SLOT_BACKGROUND_KEEPALIVE_KEY),
                    false, observer)
                resolver.registerContentObserver(Settings.Global.getUriFor(
                    SystemServerWorkspaceBridge.PINNED_TASK_IDENTITIES_KEY),
                    false, observer)
                context = ctx
                observerStarted.set(true)
                refresh(ctx)
                Log.i(TAG, "SLOT_POLICY_OBSERVER_READY source=system_server")
                recordStatus("observer-ready")
            } catch (error: Throwable) {
                Log.w(TAG, "SLOT_POLICY_OBSERVER_FAILED; fail closed", error)
            } finally {
                observerQueued.set(false)
            }
        }
    }

    private fun systemContext(owner: Any): Context? {
        val task = call(owner, "getTask")
        val service = field(owner, "mAtmService") ?: field(owner, "mWmService")
            ?: field(task, "mAtmService") ?: field(task, "mWmService")
        val direct = field(service, "mContext") as? Context
            ?: field(service, "mUiContext") as? Context
        if (direct != null) return direct
        return runCatching {
            val klass = Class.forName("android.app.ActivityThread")
            val current = klass.getDeclaredMethod("currentActivityThread").apply {
                isAccessible = true
            }.invoke(null)
            klass.getDeclaredMethod("getSystemContext").apply {
                isAccessible = true
            }.invoke(current) as? Context
        }.getOrNull()
    }

    private fun refresh(ctx: Context) {
        val next = runCatching {
            Settings.Global.getInt(ctx.contentResolver,
                SystemServerWorkspaceBridge.INDEPENDENT_SLOT_LOCK_GUARD_KEY, 0) == 1
        }.getOrDefault(false)
        enabled = next
        // Independent user preference: freezer interception is OFF by default
        // even when both lifecycle guards are enabled.
        freezerEnabled = runCatching {
            Settings.Global.getInt(ctx.contentResolver,
                SystemServerWorkspaceBridge.NATIVE_TASK_FREEZER_KEY, 0) == 1
        }.getOrDefault(false)
        backgroundKeepaliveEnabled = runCatching {
            Settings.Global.getInt(ctx.contentResolver,
                SystemServerWorkspaceBridge.FLOATING_SLOT_BACKGROUND_KEEPALIVE_KEY, 0) == 1
        }.getOrDefault(false)
        val bootCount = runCatching {
            Settings.Global.getInt(ctx.contentResolver, "boot_count", -1)
        }.getOrDefault(-1)
        val registrations = runCatching {
            PinnedTaskIdentityContract.decode(
                Settings.Global.getString(ctx.contentResolver,
                    SystemServerWorkspaceBridge.PINNED_TASK_IDENTITIES_KEY),
                bootCount)
        }.getOrDefault(emptyMap())
        val prior = pinnedIdentities
        pinnedIdentities = registrations
        // A deleted/moved pin must revoke all old Task sessions immediately.
        prior.forEach { (id, previous) ->
            if (registrations[id] == previous) return@forEach
            backgroundSessions.remove(id)
            prearmedTasks.remove(id)
            trackedTasks.remove(id)
        }
        if (!next) prearmedTasks.clear()
        if (!backgroundKeepaliveEnabled && !enabled) backgroundSessions.clear()
        Log.i(TAG, "SLOT_LOCK_POLICY enabled=$next freezer=$freezerEnabled " +
            "background=$backgroundKeepaliveEnabled pinned=${pinnedIdentities.size} tracked=${trackedTasks.size}")
        recordStatus("policy:$next")
    }

    private fun recordStatus(event: String) {
        lastEvent = event
        if (!statusQueued.compareAndSet(false, true)) return
        handler.postDelayed({
            try {
                val ctx = context ?: return@postDelayed
                val counts = counters.entries.sortedBy { it.key }
                    .joinToString(",") { "${it.key}:${it.value.get()}" }
                val snapshot = "enabled=$enabled freezer=$freezerEnabled " +
                    "background=$backgroundKeepaliveEnabled hooks=$installedCount " +
                    "tracked=${trackedTasks.size} armed=${prearmedTasks.size} " +
                    "backgroundSessions=${backgroundSessions.size} pinned=${pinnedIdentities.size} " +
                    "errors=${failureCount.get()} disabledByErrors=$disabledByErrors " +
                    "screenOff=${physicalScreenOff()} last=$lastEvent counters=$counts"
                Settings.Global.putString(ctx.contentResolver, STATUS_KEY, snapshot)
            } catch (error: Throwable) {
                Log.w(TAG, "SLOT_STATUS_WRITE_FAILED", error)
            } finally {
                statusQueued.set(false)
            }
        }, 180L)
    }

    private fun note(event: String, message: String) {
        counters.computeIfAbsent(event) { AtomicInteger(0) }.incrementAndGet()
        logOnce("SLOT_EVENT:$event:${message.substringAfter("task=", "")}", message)
        recordStatus(event)
    }

    private fun isNeXtepSlot(owner: Any): Boolean =
        slotIdentity(owner)?.matched == true

    private fun isTrackedEligible(owner: Any): Boolean =
        isNeXtepSlot(owner) || (enabled && isPinnedTask(owner))

    private fun isPinnedTask(owner: Any): Boolean {
        val id = taskId(owner)
        val identity = pinnedIdentities[id] ?: return false
        val activity = resumedActivity(owner) ?: return false
        if (isFinishing(activity)) return false
        val uid = recordUid(activity) ?: return false
        val display = slotIdentity(owner) ?: return false
        val slotIndex = if (display.matched)
            display.name.removePrefix("NeXtep-floating-slot-").toIntOrNull()
        else null
        return PinnedTaskIdentityContract.matches(
            identity, id, uid, activityPackage(activity),
            display.displayId, slotIndex)
    }

    private fun taskId(owner: Any): Int {
        val task = call(owner, "getTask")
            ?: if (owner.javaClass.name == "com.android.server.wm.Task") owner else null
            ?: return -1
        return (call(task, "getTaskId") as? Int)
            ?: (field(task, "mTaskId") as? Int) ?: -1
    }

    private fun isFinishing(owner: Any): Boolean =
        field(owner, "finishing") == true ||
            field(field(owner, "mResumedActivity"), "finishing") == true

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
