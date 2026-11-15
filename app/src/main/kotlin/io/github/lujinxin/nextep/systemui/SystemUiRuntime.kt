package io.github.lujinxin.nextep.systemui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.SurfaceControl
import io.github.lujinxin.nextep.apps.LauncherAppCatalogContract
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.config.WorkspaceConfigClient
import io.github.lujinxin.nextep.config.WorkspaceConfigContract
import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.monitoring.WorkspaceEnergyContract
import io.github.lujinxin.nextep.monitoring.WorkspaceEnergyDiagnostics
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.trigger.WorkspaceRecoveryPolicy
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.workspace.WorkspaceController
import io.github.lujinxin.nextep.workspace.WorkspaceStateBridge
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SystemUiRuntime {
    private val initialized = AtomicBoolean(false)
    private lateinit var applicationContext: Context
    private lateinit var windowController: NeXtepWindowController
    private lateinit var floatingDock: FloatingSlotDockController
    private lateinit var dockMediaObserver: DockMediaAudioObserver
    private lateinit var nativeFreeform: NativeFreeformController
    private lateinit var workspaceController: WorkspaceController
    private lateinit var fourFingerGestures: FourFingerGestureController
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val configExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-WorkspaceConfig").apply { isDaemon = true }
    }
    @Volatile private var workspaceConfig = WorkspaceTopConfig()
    @Volatile private var workspaceConfigReady = false
    private val configRetry = Runnable { refreshWorkspaceConfig() }
    // Restore hidden floating task coordination after the keyguard is gone,
    // without requiring the user to show the window again.
    private var hiddenFloatingResumeAttempts = 0
    private val hiddenFloatingResume = object : Runnable {
        override fun run() {
            if (!::floatingDock.isInitialized || !floatingDock.hasSession()) return
            val screenReady = isScreenReady()
            val unlocked = !isKeyguardLocked(applicationContext)
            if (screenReady && unlocked && floatingDock.resumeHiddenSessionAfterUnlock()) return
            if (++hiddenFloatingResumeAttempts < 12) {
                lifecycleHandler.postDelayed(this, 500L)
            } else {
                NeXtepLog.warn("floating_dock",
                    "Retained hidden session did not resume after unlock " +
                        "screenReady=$screenReady unlocked=$unlocked")
            }
        }
    }

    private lateinit var autoSlotTaskRepository: TaskRepository
    private var observedForeground: TaskRepository.TaskSnapshot? = null
    private var lastAutoParkTaskId = -1
    private var lastAutoParkAt = 0L
    private var autoSlotPending = false

    private fun isAutoSlotAllowed(task: TaskRepository.TaskSnapshot?, onLock: Boolean): Boolean {
        if (!workspaceConfigReady || !::floatingDock.isInitialized ||
            !::workspaceController.isInitialized || workspaceController.isActive() ||
            !workspaceConfig.floatingBubbleEnabled ||
            workspaceConfig.floatingWindowMode != WorkspaceConfigContract.FLOATING_WINDOW_MODE_VIRTUAL ||
            task?.userId == null || task.displayId != Display.DEFAULT_DISPLAY ||
            task.windowingMode != 1 || task.vendorWindowed) return false
        val pkg = task.component.packageName
        val pinned = floatingDock.pinnedSlotFor(pkg, task.userId) != null
        val enabled = pinned ||
            if (onLock) workspaceConfig.autoSlotOnLock
            else workspaceConfig.autoSlotOnBackground
        if (!enabled) return false
        if ((!pinned && (workspaceConfig.autoSlotPackages.isEmpty() ||
            pkg !in workspaceConfig.autoSlotPackages)) ||
            pkg == TriggerBroadcastContract.SYSTEM_UI_PACKAGE ||
            pkg == TriggerBroadcastContract.MODULE_PACKAGE ||
            pkg == TriggerBroadcastContract.resolveHomePackage(applicationContext)) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (task.taskId == lastAutoParkTaskId && now - lastAutoParkAt < 3_000L) return false
        return true
    }

    private fun captureAutoSlotForeground(source: String, parkOnChange: Boolean) {
        if (!::autoSlotTaskRepository.isInitialized ||
            (!workspaceConfig.autoSlotOnBackground && !workspaceConfig.autoSlotOnLock &&
                (!::floatingDock.isInitialized || !floatingDock.hasPinnedBindings()))) return
        val next = autoSlotTaskRepository.foregroundTask()
        val previous = observedForeground
        observedForeground = next
        if (!parkOnChange || autoSlotPending || previous?.userId == null ||
            next == null || next.taskId == previous.taskId ||
            !isAutoSlotAllowed(previous, onLock = false)) return

        // Home runs the already-in-progress Quickstep animation. A task
        // observed on the desktop for one instant is not yet safely movable.
        // Lock parking is intentionally separate and remains immediate.
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
        val returningHome = PinnedHomeTransitionPolicy.shouldAutoParkOnHome(
            pinned = floatingDock.pinnedSlotFor(
                previous.component.packageName, previous.userId) != null,
            nextIsHome = homePackage != null && next.component.packageName == homePackage,
        )
        val parkReason = if (returningHome) "home" else "background"
        val taskId = previous.taskId
        val pkg = previous.component.packageName
        val userId = previous.userId
        autoSlotPending = true
        fun commitPark() {
            try {
                val latest = autoSlotTaskRepository.foregroundTask()
                val stillHome = homePackage != null &&
                    latest?.taskId == next.taskId &&
                    latest?.component?.packageName == homePackage
                if (returningHome && (!stillHome ||
                    floatingDock.pinnedSlotFor(pkg, userId) == null)) {
                    NeXtepLog.info("auto_slot",
                        "HOME_SETTLE_CANCEL_CHANGED_FOCUS task=$taskId")
                    return
                }
                if (isScreenReady() && !isKeyguardLocked(applicationContext) &&
                    isAutoSlotAllowed(previous, onLock = false) &&
                    latest?.taskId != taskId) {
                    val accepted = floatingDock.autoParkCaptured(
                        taskId, pkg, userId, parkReason)
                    if (accepted) {
                        lastAutoParkTaskId = taskId
                        lastAutoParkAt = android.os.SystemClock.elapsedRealtime()
                        NeXtepLog.info("auto_slot",
                            "AUTO_PARK_DISPATCH task=$taskId reason=$parkReason " +
                                "homeSettled=$returningHome")
                    }
                } else {
                    NeXtepLog.info("auto_slot",
                        "AUTO_PARK_SKIP_CHANGED_STATE task=$taskId reason=$parkReason " +
                            "latest=${latest?.taskId}")
                }
            } catch (error: Throwable) {
                NeXtepLog.warn("auto_slot",
                    "AUTO_BACKGROUND_EXCEPTION task=$taskId source=$source", error)
            } finally {
                autoSlotPending = false
            }
        }
        if (returningHome) {
            NeXtepLog.info("auto_slot",
                "HOME_SETTLE_START task=$taskId launcherTask=${next.taskId} " +
                    "firstDelayMs=${HomeAutoParkSettlePolicy.FIRST_CHECK_DELAY_MS}")
            fun sampleHome(confirmations: Int) {
                try {
                    val latest = autoSlotTaskRepository.foregroundTask()
                    val target = autoSlotTaskRepository.findTask(taskId)
                    val stillHome = homePackage != null &&
                        latest?.component?.packageName == homePackage
                    val sameHomeTask = latest?.taskId == next.taskId
                    val onMain = target?.displayId == Display.DEFAULT_DISPLAY
                    val sameTarget = target != null &&
                        target.component.packageName == pkg && target.userId == userId &&
                        target.windowingMode == 1 && !target.vendorWindowed
                    val decision = HomeAutoParkSettlePolicy.decide(
                        launcherStillForeground = stillHome,
                        sameLauncherTask = sameHomeTask,
                        targetStillOnMain = onMain,
                        matchingTargetIdentity = sameTarget,
                        confirmations = confirmations,
                    )
                    when (decision) {
                        HomeAutoParkSettlePolicy.Decision.CANCEL -> {
                            autoSlotPending = false
                            NeXtepLog.info("auto_slot",
                                "HOME_SETTLE_CANCEL task=$taskId samples=$confirmations " +
                                    "home=$stillHome sameHomeTask=$sameHomeTask " +
                                    "onMain=$onMain identity=$sameTarget")
                        }
                        HomeAutoParkSettlePolicy.Decision.WAIT -> {
                            if (confirmations >= HomeAutoParkSettlePolicy.MAX_CHECKS) {
                                autoSlotPending = false
                                NeXtepLog.warn("auto_slot",
                                    "HOME_SETTLE_TIMEOUT task=$taskId")
                            } else lifecycleHandler.postDelayed({
                                sampleHome(confirmations + 1)
                            }, HomeAutoParkSettlePolicy.CONFIRM_DELAY_MS)
                        }
                        HomeAutoParkSettlePolicy.Decision.PARK -> {
                            NeXtepLog.info("auto_slot",
                                "HOME_SETTLED task=$taskId launcherTask=${next.taskId} " +
                                    "samples=$confirmations")
                            commitPark()
                        }
                    }
                } catch (error: Throwable) {
                    autoSlotPending = false
                    NeXtepLog.warn("auto_slot",
                        "HOME_SETTLE_FAILED task=$taskId", error)
                }
            }
            lifecycleHandler.postDelayed({
                sampleHome(1)
            }, HomeAutoParkSettlePolicy.FIRST_CHECK_DELAY_MS)
        } else lifecycleHandler.postDelayed({ commitPark() }, 120L)
    }

    /**
     * A pinned card has just been promoted to the physical display. Seed the
     * observed identity from the real foreground Task. Switching to another
     * app or HOME returns it to its reserved slot; HOME restores launcher
     * focus once the migration completes.
     */
    fun onPinnedTaskPromoted(taskId: Int) {
        fun capture(source: String) {
            if (!::autoSlotTaskRepository.isInitialized) return
            val task = autoSlotTaskRepository.findTask(taskId)
            if (task?.displayId == Display.DEFAULT_DISPLAY &&
                autoSlotTaskRepository.foregroundTask()?.taskId == taskId &&
                task.userId != null && ::floatingDock.isInitialized &&
                floatingDock.pinnedSlotFor(task.component.packageName, task.userId) != null) {
                observedForeground = task
                lastAutoParkTaskId = -1
                NeXtepLog.info("auto_slot",
                    "PIN_FOREGROUND_CAPTURED task=$taskId source=$source")
            }
        }
        // Register the promoted Task immediately. With a 160 ms-only delay,
        // a rapid HOME gesture could occur before the identity was recorded,
        // leaving the pinned Task outside its reserved slot.
        capture("immediate")
        lifecycleHandler.postDelayed({ capture("settled") }, 160L)
    }

    private fun autoParkForLock(): Boolean {
        if (!::autoSlotTaskRepository.isInitialized) return false
        if (::floatingDock.isInitialized && floatingDock.isAutoParkInProgress()) {
            NeXtepLog.info("auto_slot", "LOCK_PREPARK_ALREADY_IN_FLIGHT")
            return true
        }
        val latest = autoSlotTaskRepository.foregroundTask()
        val captured = latest?.takeIf {
            it.userId != null && ::floatingDock.isInitialized &&
                floatingDock.pinnedSlotFor(it.component.packageName, it.userId) != null
        } ?: observedForeground ?: latest
        if (!isAutoSlotAllowed(captured, onLock = true) || captured?.userId == null)
            return false
        // Do not create a fresh focusable SystemUI overlay after keyguard is
        // displayed. Lock parking requires an already retained live session.
        if (!floatingDock.hasSession()) {
            NeXtepLog.info("auto_slot",
                "LOCK_SKIPPED_NO_SESSION task=${captured.taskId}")
            return false
        }
        lastAutoParkTaskId = captured.taskId
        lastAutoParkAt = android.os.SystemClock.elapsedRealtime()
        val accepted = floatingDock.autoParkCaptured(
            captured.taskId, captured.component.packageName,
            captured.userId, "lock",
        ) {
            // An early physical power-key event need not become a lock.
            // Only suspend displays once keyguard or screen-off is real.
            if (!isScreenReady() || isKeyguardLocked(applicationContext))
                floatingDock.closeFor(FloatingDockSessionPolicy.CloseReason.SCREEN_LOCK)
        }
        NeXtepLog.info("auto_slot",
            "LOCK_AUTO_PARK_REQUEST task=${captured.taskId} accepted=$accepted")
        return accepted
    }

    // Privileged physical power-key event from system_server. Captures the
    // already-fullscreen pinned Task before OOS17 keyguard sleep callbacks.
    private val physicalPowerPrelockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TriggerBroadcastContract.ACTION_POWER_KEY_PRELOCK ||
                !initialized.get() || !workspaceConfigReady ||
                !::floatingDock.isInitialized ||
                !::autoSlotTaskRepository.isInitialized) return
            val sentAt = intent.getLongExtra(
                TriggerBroadcastContract.EXTRA_POWER_KEY_ELAPSED, -1L)
            val ageMs = android.os.SystemClock.elapsedRealtime() - sentAt
            if (sentAt < 0L || ageMs !in 0L..800L) {
                NeXtepLog.warn("auto_slot", "EARLY_PRELOCK_STALE ageMs=$ageMs")
                return
            }
            if (!isScreenReady() || isKeyguardLocked(applicationContext)) return
            val current = autoSlotTaskRepository.foregroundTask()
            val userId = current?.userId
            if (current != null && userId != null &&
                floatingDock.pinnedSlotFor(current.component.packageName, userId) != null)
                observedForeground = current
            val accepted = autoParkForLock()
            NeXtepLog.info("auto_slot",
                "EARLY_POWER_PRELOCK ageMs=$ageMs accepted=$accepted " +
                    "task=${current?.taskId ?: -1} display=${current?.displayId ?: -1}")
        }
    }

    private val taskRefreshQueued = AtomicBoolean(false)
    private val taskRefresh = Runnable {
        taskRefreshQueued.set(false)
        captureAutoSlotForeground("task-stack", parkOnChange = true)
        if (::windowController.isInitialized) windowController.onTasksChanged()
        if (::floatingDock.isInitialized) floatingDock.onTasksChanged()
        if (::dockMediaObserver.isInitialized) dockMediaObserver.refreshSoon()
    }

    /** All sources converge here. Navigation never toggles or opens a folded dock. */
    fun onNavigationCompleted(source: String) {
        if (!initialized.get()) return
        lifecycleHandler.post {
            if (::floatingDock.isInitialized) floatingDock.onNavigationEvent(source)
        }
    }

    private val navigationFoldReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TriggerBroadcastContract.ACTION_SYSTEM_NAVIGATION_FOLD) return
            // This cross-process signal deliberately has no sender permission:
            // Quickstep runs as a non-system UID on some ColorOS builds.
            // Verify the actual Binder-origin UID, never an Intent extra.
            val senderUid = sentFromUid
            val fromSystem = senderUid == android.os.Process.SYSTEM_UID
            val packages = if (senderUid >= 0) context.packageManager
                .getPackagesForUid(senderUid).orEmpty() else emptyArray()
            val fromLauncher = packages.contains("com.android.launcher")
            val source = intent.getStringExtra(
                TriggerBroadcastContract.EXTRA_NAVIGATION_EVENT_SOURCE
            )?.take(80) ?: return
            // The module's accessibility service may only emit its own
            // diagnostic namespace, never impersonate system_server/launcher.
            val fromAccessibility = packages.contains(TriggerBroadcastContract.MODULE_PACKAGE) &&
                source.startsWith("accessibility:")
            if (!fromSystem && !fromLauncher && !fromAccessibility) {
                NeXtepLog.warn("floating_nav",
                    "NAV_REJECTED uid=$senderUid package=$sentFromPackage " +
                        "source=$source")
                return
            }
            NeXtepLog.info("floating_nav",
                "NAV_RECEIVED source=$source uid=$senderUid package=$sentFromPackage")
            onNavigationCompleted(source)
        }
    }

    fun onTasksChanged() {
        if (taskRefreshQueued.compareAndSet(false, true)) lifecycleHandler.post(taskRefresh)
    }
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WorkspaceConfigContract.ACTION_CHANGED) refreshWorkspaceConfig()
        }
    }

    private fun refreshWorkspaceConfig() {
        lifecycleHandler.removeCallbacks(configRetry)
        configExecutor.execute {
            // Query the module even before the workspace windows exist.
            val queriedConfig = WorkspaceConfigClient.queryOrNull(applicationContext)
            if (queriedConfig == null) {
                // Credential-protected module settings are unavailable before first unlock.
                lifecycleHandler.postDelayed(configRetry, 2_000L)
                return@execute
            }
            workspaceConfig = queriedConfig
            SystemServerWorkspaceBridge.publishBlockSystemDragShare(applicationContext,
                queriedConfig.blockSystemDragShareEnabled)
            SystemServerWorkspaceBridge.publishIndependentSlotLockGuard(applicationContext,
                queriedConfig.floatingLockGuardEnabled)
            SystemServerWorkspaceBridge.publishFloatingSlotBackgroundKeepalive(applicationContext,
                queriedConfig.floatingBackgroundKeepaliveEnabled)
            SystemServerWorkspaceBridge.publishNativeLockSettings(applicationContext,
                queriedConfig.nativeLockGuardEnabled, queriedConfig.nativeFreezeProtectionEnabled)
            AppLanguage.updateHostLanguage(queriedConfig.languageTag)
            workspaceConfigReady = true
            lifecycleHandler.post {
                if (::windowController.isInitialized) {
                    windowController.setSlotBackgroundSwipeEnabled(workspaceConfig.slotBackgroundSwipeEnabled)
                    windowController.setNavigationButtonsEnabled(workspaceConfig.navigationButtonsEnabled)
                }
                if (::fourFingerGestures.isInitialized) fourFingerGestures.updateConfig(workspaceConfig)
                if (workspaceConfig.autoSlotOnBackground || workspaceConfig.autoSlotOnLock ||
                    (::floatingDock.isInitialized && floatingDock.hasPinnedBindings()))
                    captureAutoSlotForeground("config-init", parkOnChange = false)
                // Prepare four hidden real VirtualDisplays while the screen is
                // still unlocked. Creating a focusable SystemUI overlay after
                // ACTION_SCREEN_OFF is too late and can disrupt keyguard.
                if ((workspaceConfig.autoSlotOnLock &&
                    workspaceConfig.autoSlotPackages.isNotEmpty() ||
                    (::floatingDock.isInitialized && floatingDock.hasPinnedBindings())) &&
                    workspaceConfig.floatingBubbleEnabled &&
                    workspaceConfig.floatingWindowMode ==
                        WorkspaceConfigContract.FLOATING_WINDOW_MODE_VIRTUAL &&
                    ::floatingDock.isInitialized &&
                    ::workspaceController.isInitialized &&
                    !workspaceController.isActive() && isScreenReady() &&
                    !isKeyguardLocked(applicationContext)) {
                    val ready = floatingDock.prepareHiddenAutoSession()
                    NeXtepLog.info("auto_slot", "PREPARE_HIDDEN_LOCK_DOCK ready=$ready")
                }
                if (::nativeFreeform.isInitialized) nativeFreeform.configureNativeLockGuard(
                    workspaceConfig.nativeLockGuardEnabled &&
                    workspaceConfig.floatingWindowMode == WorkspaceConfigContract.FLOATING_WINDOW_MODE_NATIVE_FREEFORM)
                if (::floatingDock.isInitialized) {
                    floatingDock.applyLayoutSettings(
                        workspaceConfig.floatingDockOrientation,
                        workspaceConfig.floatingDockSizePercent,
                    )
                    floatingDock.applyPopupInputSettings(
                        workspaceConfig.floatingPopupBackEnabled,
                        workspaceConfig.floatingSwipeCollapseEnabled,
                    )

                }
                // A hidden floating session still owns live VirtualDisplays.
                // Disabling the feature fully shuts down only that session.
                if ((!workspaceConfig.floatingBubbleEnabled ||
                    workspaceConfig.floatingWindowMode == WorkspaceConfigContract.FLOATING_WINDOW_MODE_NATIVE_FREEFORM) &&
                    ::floatingDock.isInitialized && floatingDock.hasSession()
                ) floatingDock.closeFor(FloatingDockSessionPolicy.CloseReason.FEATURE_DISABLED)
            }
            NeXtepLog.info(
                "workspace_config",
                "Applied statusBarGesture=${workspaceConfig.statusBarGestureEnabled} " +
                    "slotBackgroundSwipe=${workspaceConfig.slotBackgroundSwipeEnabled} " +
                    "autoMinimizeMain=${workspaceConfig.autoMinimizeMainOnTopAppSwitch} " +
                    "navigationButtons=${workspaceConfig.navigationButtonsEnabled} " +
                    "blockSystemDragShare=${workspaceConfig.blockSystemDragShareEnabled}",
            )
        }
    }

    fun isStatusBarGestureEnabled(): Boolean = workspaceConfigReady && workspaceConfig.statusBarGestureEnabled

    fun shouldAutoMinimizeMainOnTopAppSwitch(): Boolean = workspaceConfig.autoMinimizeMainOnTopAppSwitch

    fun swapAnimationDurationMs(): Int = workspaceConfig.swapAnimationDurationMs

    fun shouldReturnAfterSharing(): Boolean = workspaceConfig.returnAfterSharingEnabled
    private val lockStateObserver = object : Runnable {
        override fun run() {
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            val screenReady = isScreenReady()
            if (!screenReady || isKeyguardLocked(applicationContext)) {
                if (!workspaceController.isSuspended()) {
                    workspaceController.suspendForLock()
                    publishWorkspaceSurfaceState(false)
                }
                // A keyguard-going-away sync can include retained virtual-display
                // activities. Keeping their outputs OFF until keyguard is gone
                // makes that sync wait for its five-second draw timeout.
                // Panels and input remain suspended; only hidden buffers warm up.
                windowController.updateSuspendedOutputs(screenReady)
            } else if (workspaceController.isSuspended()) {
                lifecycleHandler.removeCallbacks(resumeAfterUnlock)
                resumeAfterUnlock.run()
            }
            updateLockStateObservation()
        }
    }

    private fun updateLockStateObservation() {
        // State transitions can occur inside an observation callback. Remove any
        // queued copy before scheduling, so there is only one fallback chain.
        lifecycleHandler.removeCallbacks(lockStateObserver)
        if (::workspaceController.isInitialized && workspaceController.isActive()) {
            lifecycleHandler.postDelayed(lockStateObserver, 500L)
        } else {
            lifecycleHandler.removeCallbacks(resumeAfterUnlock)
        }
    }
    private val resumeAfterUnlock = object : Runnable {
        override fun run() {
            lifecycleHandler.removeCallbacks(this)
            if (!::workspaceController.isInitialized || !workspaceController.isSuspended()) return
            val locked = isKeyguardLocked(applicationContext)
            val screenReady = isScreenReady()
            windowController.updateSuspendedOutputs(screenReady)
            if (locked || !screenReady) {
                // USER_PRESENT can precede the physical display becoming ready.
                // Keep this attempt alive instead of waiting for another broadcast.
                lifecycleHandler.postDelayed(this, if (locked) 500L else UNLOCK_DISPLAY_RETRY_MS)
                return
            }
            val resumed = workspaceController.resumeAfterLock()
            publishWorkspaceSurfaceState(resumed)
            if (!resumed) WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }
    private val keyguardStateListener = KeyguardManager.KeyguardLockedStateListener {
        // This callback can precede SCREEN_OFF on OxygenOS. Attempt pinned
        // return while the physical display is still interactive. The
        // ACTION_SCREEN_OFF path remains the bounded fallback.
        if (workspaceConfigReady && ::floatingDock.isInitialized &&
            ::workspaceController.isInitialized && !workspaceController.isActive() &&
            isScreenReady() && isKeyguardLocked(applicationContext) &&
            (floatingDock.hasPinnedBindings() || workspaceConfig.autoSlotOnLock)) {
            NeXtepLog.info("auto_slot", "KEYGUARD_PRELOCK_EVENT")
            autoParkForLock()
        }
        // Re-read current state: a queued callback can belong to an earlier wake.
        lockStateObserver.run()
    }
    private val displayStateListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                lockStateObserver.run()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun observeLockAndDisplayState() {
        // These callbacks execute in SystemUI. Keep the broadcast/polling fallback
        // when an OEM does not grant its host the keyguard subscription permission.
        runCatching {
            checkNotNull(applicationContext.getSystemService(KeyguardManager::class.java))
                .addKeyguardLockedStateListener(applicationContext.mainExecutor, keyguardStateListener)
        }.onFailure {
            NeXtepLog.warn("workspace_unlock", "Keyguard callback unavailable; retaining state polling", it)
        }
        runCatching {
            checkNotNull(applicationContext.getSystemService(DisplayManager::class.java))
                .registerDisplayListener(displayStateListener, lifecycleHandler)
        }.onFailure {
            NeXtepLog.warn("workspace_unlock", "Display callback unavailable; retaining state polling", it)
        }
    }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            applicationContext = context.applicationContext ?: context
            LauncherAppCatalogContract.register(applicationContext)
            TaskSurfaceCompat.initializeTaskAccess(applicationContext)
            WorkspaceDisplayTransitionHook.initialize(applicationContext)
            val mainTaskPresenter = MainTaskPresentationCoordinator(applicationContext)
            windowController = NeXtepWindowController(
                context = applicationContext,
                mainTaskPresenter = mainTaskPresenter,
                onSidebarSideRequested = ::setSidebarSideFromTopBar,
                onSettingsRequested = ::openSettingsFromTopBar,
                onExitRequested = ::closeWorkspaceFromGesture,
                onViewportChanged = ::reconfigureForViewport,
            )
            // Share the exact saved slot dimensions/rotation profile with the
            // optional horizontal dock, but never share task or window ownership.
            floatingDock = FloatingSlotDockController(
                applicationContext, windowController::recordedLayoutProfile,
            )
            // Media bridge is read-only and scoped to our retained four-slot
            // tasks. No third-party package needs an LSPosed scope.
            dockMediaObserver = DockMediaAudioObserver(applicationContext) {
                if (::floatingDock.isInitialized) floatingDock.occupiedMediaApps()
                else emptyList()
            }.also { it.start() }
            nativeFreeform = NativeFreeformController(applicationContext)
            autoSlotTaskRepository = TaskRepository(applicationContext)
            workspaceController = WorkspaceController(
                applicationContext = applicationContext,
                windowController = windowController,
                mainTaskPresenter = mainTaskPresenter,
                onStateChanged = ::updateLockStateObservation,
            )
            fourFingerGestures = FourFingerGestureController(
                applicationContext,
                isWorkspaceActive = ::isWorkspaceActive,
                visibleSlotTaskIds = windowController::visibleSlotTaskIds,
                canChangeWorkspace = { !workspaceController.isSuspended() },
                requestWorkspace = ::setWorkspaceFromFourFingerGesture,
            )

            val filter = IntentFilter().apply {
                addAction(TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_TOGGLE_FLOATING_PANEL)
                addAction(TriggerBroadcastContract.ACTION_QUICK_ADD_FLOATING_TASK)
                addAction(TriggerBroadcastContract.ACTION_OPEN_MEDIA_DOCK_APP)
                addAction(TriggerBroadcastContract.ACTION_UPDATE_FLOATING_ANCHOR)
                addAction(TriggerBroadcastContract.ACTION_SET_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_QUERY_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_QUERY_DIAGNOSTICS)
                addAction(TriggerBroadcastContract.ACTION_LAUNCH_SLOT_TEST)
                addAction(TriggerBroadcastContract.ACTION_FOUR_FINGER_WORKSPACE)
            }
            applicationContext.registerReceiver(
                controlReceiver,
                filter,
                TriggerBroadcastContract.CONTROL_PERMISSION,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            // Only privileged system navigation senders can request folding.
            // This broadcast cannot open a panel or intercept any original key.
            applicationContext.registerReceiver(
                navigationFoldReceiver,
                IntentFilter(TriggerBroadcastContract.ACTION_SYSTEM_NAVIGATION_FOLD),
                null,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            applicationContext.registerReceiver(
                launcherRequestReceiver,
                IntentFilter(TriggerBroadcastContract.ACTION_LAUNCHER_APP_REQUEST),
                Context.RECEIVER_EXPORTED,
            )
            applicationContext.registerReceiver(
                configReceiver,
                IntentFilter(WorkspaceConfigContract.ACTION_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            refreshWorkspaceConfig()
            val lifecycleFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
            runCatching {
                applicationContext.registerReceiver(
                    lifecycleReceiver,
                    lifecycleFilter,
                    Context.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { error ->
                NeXtepLog.warn("systemui_runtime", "Lifecycle receiver unavailable", error)
            }
            // Only system_server / holders of DEVICE_POWER may deliver
            // this event. Fail-open if the OS rejects the receiver.
            runCatching {
                applicationContext.registerReceiver(
                    physicalPowerPrelockReceiver,
                    IntentFilter(TriggerBroadcastContract.ACTION_POWER_KEY_PRELOCK),
                    "android.permission.DEVICE_POWER",
                    lifecycleHandler,
                    Context.RECEIVER_EXPORTED,
                )
            }.onFailure {
                NeXtepLog.warn("auto_slot", "EARLY_PRELOCK_RECEIVER_UNAVAILABLE", it)
            }
            observeLockAndDisplayState()
            runCatching {
                WorkspaceEnergyContract.register(applicationContext, lifecycleHandler) {
                    windowController.energySnapshot().apply {
                        putInt("version", 1)
                        putInt("pid", android.os.Process.myPid())
                        putLong("elapsed_ms", android.os.SystemClock.elapsedRealtime())
                        putLong("cpu_ms", android.os.Process.getElapsedCpuTime())
                        putBoolean("active", workspaceController.isActive())
                        putBoolean("suspended", workspaceController.isSuspended())
                        putLong("task_queries", WorkspaceEnergyDiagnostics.taskQueries.get())
                        putLong("preview_captures", WorkspaceEnergyDiagnostics.previewCaptures.get())
                    }
                }
            }.onFailure { NeXtepLog.warn("energy_monitor", "Runtime snapshot unavailable", it) }
            Handler(Looper.getMainLooper()).post {
                SystemServerWorkspaceBridge.publish(applicationContext, false)
                WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
                // OWN_DISPLAY_GROUP virtual screens can keep the device globally interactive
                // after the physical screen locks, so SCREEN_OFF alone is insufficient.
                updateLockStateObservation()
            }
            NeXtepLog.info("systemui_runtime", "Initialized control receiver")
        } catch (error: Throwable) {
            initialized.set(false)
            NeXtepLog.error("systemui_runtime", "Initialization failed and remains retryable", error)
        }
    }

    fun isWorkspaceActive(): Boolean =
        ::workspaceController.isInitialized && workspaceController.isActive()

    fun prepareDisplayRotation(rotation: Int, generation: Long) {
        // WM can deliver a resize before SystemUI's configuration callback is
        // dequeued. Its first drawing pass must already know the target angle.
        if (::windowController.isInitialized) windowController.stagePanelRotation(rotation)
        lifecycleHandler.post {
            if (!::workspaceController.isInitialized || !::windowController.isInitialized ||
                !workspaceController.isActive() || workspaceController.isSuspended() ||
                !TaskSurfaceCompat.isDisplayRotationPending(generation)) return@post
            windowController.projectDisplayRotation(rotation)
            workspaceController.reconfigure(refreshViewport = false, deferMainPresentation = true)
            windowController.whenRotationFrameReady { host ->
                if (!workspaceController.isActive() || workspaceController.isSuspended()) {
                    TaskSurfaceCompat.completeDisplayRotation(generation)
                    return@whenRotationFrameReady
                }
                val transaction = SurfaceControl.Transaction()
                runCatching {
                    if (!TaskSurfaceCompat.finishDisplayRotation(generation, transaction)) {
                        TaskSurfaceCompat.completeDisplayRotation(generation)
                        transaction.close()
                        return@whenRotationFrameReady
                    }
                    transaction.addTransactionCommittedListener({ command -> lifecycleHandler.post(command) }) {
                        TaskSurfaceCompat.completeDisplayRotation(generation)
                        transaction.close()
                        NeXtepLog.info("workspace_display_transition", "Fitted main task with ready panel frame rotation=$rotation")
                    }
                    if (host.rootSurfaceControl?.applyTransactionOnDraw(transaction) != true) transaction.apply()
                    host.invalidate()
                }.onFailure {
                    TaskSurfaceCompat.completeDisplayRotation(generation)
                    transaction.close()
                    NeXtepLog.warn("workspace_display_transition", "Panel rotation handoff failed; presentation will retry", it)
                }
            }
        }
    }

    fun toggleWorkspaceFromGesture() {
        if (!isStatusBarGestureEnabled() || !::workspaceController.isInitialized) return
        val target = !workspaceController.isActive()
        if (target && isKeyguardLocked(applicationContext)) return
        if (target && ::floatingDock.isInitialized && floatingDock.hasSession() &&
            !floatingDock.closeFor(FloatingDockSessionPolicy.CloseReason.OPEN_ORIGINAL_WORKSPACE)) return
        if (workspaceController.setActive(target)) {
            publishWorkspaceSurfaceState(target)
            if (!target) WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    fun closeWorkspaceFromGesture() {
        if (::workspaceController.isInitialized) {
            workspaceController.setActive(false)
        }
        if (::applicationContext.isInitialized) {
            TriggerBroadcastContract.systemUiSetIntent(applicationContext, false)?.let { intent ->
                applicationContext.sendBroadcast(intent)
            }
            applicationContext.sendBroadcast(
                TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, false),
            )
            WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    private fun setWorkspaceFromFourFingerGesture(active: Boolean) {
        if (!workspaceConfigReady || !workspaceConfig.fourFingerGestureEnabled ||
            !::workspaceController.isInitialized || !isScreenReady() || isKeyguardLocked(applicationContext) ||
            workspaceController.isSuspended() || workspaceController.isActive() == active
        ) return
        if (active && ::floatingDock.isInitialized && floatingDock.hasSession() &&
            !floatingDock.closeFor(FloatingDockSessionPolicy.CloseReason.OPEN_ORIGINAL_WORKSPACE)) return
        if (workspaceController.setActive(active)) {
            publishWorkspaceSurfaceState(active)
            if (!active) WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    private fun publishWorkspaceSurfaceState(active: Boolean) {
        TriggerBroadcastContract.systemUiSetIntent(applicationContext, active)?.let {
            applicationContext.sendBroadcast(it)
        }
        applicationContext.sendBroadcast(
            TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, active),
        )
    }

    private fun setSidebarSideFromTopBar(side: SidebarSide) {
        if (::workspaceController.isInitialized && !workspaceController.setSidebarSide(side)) {
            NeXtepLog.warn("sidebar_side", "Unable to switch side=$side")
        }
    }

    private fun reconfigureForViewport() {
        if (!::workspaceController.isInitialized || !workspaceController.isActive() ||
            workspaceController.isSuspended()) return
        // A manual navigation-mode change has already updated the cached viewport.
        if (!workspaceController.reconfigure(refreshViewport = false)) {
            publishWorkspaceSurfaceState(false)
            WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    private fun openSettingsFromTopBar() {
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(TriggerBroadcastContract.MODULE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            NeXtepLog.warn("top_settings", "Module launch intent unavailable")
            return
        }
        if (::workspaceController.isInitialized && workspaceController.isActive()) {
            // Use the same task/display coordination as a launcher click, including
            // an existing NeXtep task in a slot. Opening settings must retain workspace.
            if (!workspaceController.openFromLauncher(intent)) {
                NeXtepLog.warn("top_settings", "Unable to open settings in workspace")
            }
            return
        }
        runCatching { applicationContext.startActivity(intent) }
            .onFailure { NeXtepLog.warn("top_settings", "Unable to open settings", it) }
    }

    private val lifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                refreshWorkspaceConfig()
            }
            if (intent.action == Intent.ACTION_SCREEN_ON ||
                intent.action == Intent.ACTION_USER_PRESENT
            ) {
                // Some Android configurations have no secure keyguard, hence
                // USER_PRESENT may never arrive after a normal screen wake.
                // Both events trigger bounded retries, but actual resume is
                // gated on screenReady and a fully dismissed keyguard.
                lifecycleHandler.removeCallbacks(hiddenFloatingResume)
                hiddenFloatingResumeAttempts = 0
                lifecycleHandler.postDelayed(hiddenFloatingResume, 250L)
            }
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                lifecycleHandler.removeCallbacks(hiddenFloatingResume)
                TaskSurfaceCompat.finishWorkspaceExitAnimations()
                // If early prelock started migrating a Task, suspending the
                // virtual display here would cause Activity pause mid-move.
                if (::floatingDock.isInitialized &&
                    !floatingDock.isAutoParkInProgress() && !autoParkForLock())
                    floatingDock.closeFor(FloatingDockSessionPolicy.CloseReason.SCREEN_LOCK)
            }
            if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED && ::floatingDock.isInitialized) {
                floatingDock.reconfigure()
            }
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lifecycleHandler.removeCallbacks(resumeAfterUnlock)
                    workspaceController.suspendForLock()
                    windowController.updateSuspendedOutputs(screenAwake = false)
                    publishWorkspaceSurfaceState(false)
                    return
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    lifecycleHandler.removeCallbacks(resumeAfterUnlock)
                    lifecycleHandler.post(resumeAfterUnlock)
                    return
                }
            }
            val reason = when (intent.action) {
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    if (workspaceController.isSuspended()) {
                        // Select the recorded rotation/density profile at the actual
                        // configuration event, never during a task exchange or every wake.
                        windowController.currentGeometry(refreshViewport = true)
                        return
                    }
                    if (workspaceController.reconfigure()) {
                        NeXtepLog.info(
                            "systemui_runtime",
                            "Workspace retained across configuration change",
                        )
                        return
                    }
                    "configuration reconfigure failed"
                }
                else -> return
            }
            workspaceController.setActive(false)
            WorkspaceStateBridge.requestInactiveFromSystemUi(context)
            NeXtepLog.info("systemui_runtime", "Workspace closed fail-open: $reason")
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isOrderedBroadcast || !::workspaceController.isInitialized) return
            if (intent.action == TriggerBroadcastContract.ACTION_OPEN_MEDIA_DOCK_APP) {
                val outcome = try {
                    handleMediaOpen(context, intent)
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    NeXtepLog.warn("media_bridge", "MEDIA_OPEN_EXCEPTION", error)
                    FloatingSlotDockController.MediaOpenResult.FAILED
                }
                resultCode = if (outcome ==
                    FloatingSlotDockController.MediaOpenResult.PROMOTED)
                    TriggerBroadcastContract.RESULT_HANDLED
                else TriggerBroadcastContract.RESULT_NOT_READY
                setResultExtras(Bundle().apply {
                    putBoolean(TriggerBroadcastContract.EXTRA_MEDIA_APP_NOT_IN_DOCK,
                        outcome == FloatingSlotDockController.MediaOpenResult.NOT_IN_DOCK)
                    putString(TriggerBroadcastContract.EXTRA_FLOATING_PANEL_ERROR, outcome.name)
                })
                return
            }
            val shouldCollapseFirst = intent.getStringExtra(
                TriggerBroadcastContract.EXTRA_SOURCE,
            ) == io.github.lujinxin.nextep.trigger.TriggerSource.QUICK_SETTINGS_TILE.name &&
                requestedTarget(intent) == true
            if (shouldCollapseFirst) {
                val pendingResult = goAsync()
                SystemUiPanelCollapser.collapse(context)
                Handler(Looper.getMainLooper()).postDelayed({
                    val handled = handleControl(context, intent)
                    if (handled) {
                        pendingResult.setResultCode(TriggerBroadcastContract.RESULT_HANDLED)
                        pendingResult.setResultExtras(responseBundle(pendingResult.getResultExtras(true)))
                    }
                    pendingResult.finish()
                }, PANEL_COLLAPSE_DELAY_MS)
                return
            }
            // Bubble commands run in SystemUI's main-thread receiver. An
            // unexpected OEM WindowManager/DisplayManager exception must not
            // crash SystemUI and consequently destroy its virtual displays.
            val handled = try {
                handleControl(context, intent)
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                NeXtepLog.error("floating_icon",
                    "SYSTEMUI_TOGGLE_EXCEPTION action=${intent.action}", error)
                if (intent.action == TriggerBroadcastContract.ACTION_TOGGLE_FLOATING_PANEL) {
                    setResultExtras(Bundle().apply {
                        putString(TriggerBroadcastContract.EXTRA_FLOATING_PANEL_ERROR,
                            "SystemUI floating icon handler failed: ${error.javaClass.simpleName}")
                        putString(TriggerBroadcastContract.EXTRA_FLOATING_PANEL_TRACE,
                            android.util.Log.getStackTraceString(error).take(6_000))
                    })
                }
                return
            }
            if (!handled) {
                if (intent.action == TriggerBroadcastContract.ACTION_TOGGLE_FLOATING_PANEL) {
                    setResultExtras(Bundle().apply {
                        putString(TriggerBroadcastContract.EXTRA_FLOATING_PANEL_ERROR,
                            floatingDock.lastError ?: "Floating dock is disabled, locked, or the original workspace is active")
                        floatingDock.lastErrorTrace?.let {
                            putString(TriggerBroadcastContract.EXTRA_FLOATING_PANEL_TRACE, it)
                        }
                    })
                }
                return
            }

            resultCode = TriggerBroadcastContract.RESULT_HANDLED
            setResultExtras(responseBundle(getResultExtras(true)).apply {
                if (intent.action == TriggerBroadcastContract.ACTION_QUERY_DIAGNOSTICS) {
                    putString(TriggerBroadcastContract.EXTRA_DIAGNOSTICS_LOG, NeXtepLog.snapshot(60_000))
                    putString(TriggerBroadcastContract.EXTRA_DIAGNOSTICS_PROCESS, "com.android.systemui")
                }
            })
        }
    }

    private fun requestedTarget(intent: Intent): Boolean? = when (intent.action) {
        TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE -> !workspaceController.isActive()
        TriggerBroadcastContract.ACTION_SET_WORKSPACE -> intent.getBooleanExtra(
            TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE,
            false,
        )
        else -> null
    }

    private fun bubbleAnchorFrom(intent: Intent): FloatingDockPlacement.Anchor? {
        if (!intent.hasExtra(TriggerBroadcastContract.EXTRA_FLOATING_BUBBLE_X) ||
            !intent.hasExtra(TriggerBroadcastContract.EXTRA_FLOATING_BUBBLE_Y) ||
            !intent.hasExtra(TriggerBroadcastContract.EXTRA_FLOATING_BUBBLE_SIZE)
        ) return null
        val size = intent.getIntExtra(TriggerBroadcastContract.EXTRA_FLOATING_BUBBLE_SIZE, 0)
        if (size <= 0) return null
        return FloatingDockPlacement.Anchor(
            intent.getIntExtra(TriggerBroadcastContract.EXTRA_FLOATING_BUBBLE_X, 0),
            intent.getIntExtra(TriggerBroadcastContract.EXTRA_FLOATING_BUBBLE_Y, 0),
            size,
        )
    }

    private fun handleMediaOpen(
        context: Context, intent: Intent,
    ): FloatingSlotDockController.MediaOpenResult {
        val pkg = intent.getStringExtra(TriggerBroadcastContract.EXTRA_MEDIA_APP_PACKAGE)
            .orEmpty().trim()
        val whenSent = intent.getLongExtra(
            TriggerBroadcastContract.EXTRA_FLOATING_REQUEST_ELAPSED, -1L)
        val ageMs = android.os.SystemClock.elapsedRealtime() - whenSent
        val allowed = ::floatingDock.isInitialized &&
            ::workspaceController.isInitialized &&
            workspaceConfigReady && workspaceConfig.floatingBubbleEnabled &&
            !workspaceController.isActive() &&
            isScreenReady() && !isKeyguardLocked(context) &&
            whenSent > 0L && ageMs in -1_000L..8_000L &&
            pkg.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+"))
        val outcome = if (allowed) floatingDock.promoteMediaApp(pkg)
            else FloatingSlotDockController.MediaOpenResult.NOT_READY
        NeXtepLog.info("media_bridge",
            "MEDIA_OPEN_REQUEST pkg=$pkg allowed=$allowed result=$outcome ageMs=$ageMs")
        return outcome
    }

    private fun handleControl(context: Context, intent: Intent): Boolean = when (intent.action) {
        TriggerBroadcastContract.ACTION_UPDATE_FLOATING_ANCHOR -> {
            if (!::floatingDock.isInitialized) false
            else {
                bubbleAnchorFrom(intent)?.let { floatingDock.updateAnchor(it) }
                true
            }
        }
        TriggerBroadcastContract.ACTION_QUICK_ADD_FLOATING_TASK -> {
            val eligible = ::floatingDock.isInitialized && ::workspaceController.isInitialized &&
                workspaceConfigReady && workspaceConfig.floatingBubbleEnabled &&
                isScreenReady() && !isKeyguardLocked(context) && !workspaceController.isActive()
            if (!eligible) false
            else if (workspaceConfig.floatingWindowMode ==
                WorkspaceConfigContract.FLOATING_WINDOW_MODE_NATIVE_FREEFORM)
                !floatingDock.hasSession() && ::nativeFreeform.isInitialized &&
                    nativeFreeform.addForeground()
            else floatingDock.quickAddForeground(bubbleAnchorFrom(intent))
        }
        TriggerBroadcastContract.ACTION_TOGGLE_FLOATING_PANEL -> {
            val sentAt = intent.getLongExtra(
                TriggerBroadcastContract.EXTRA_FLOATING_REQUEST_ELAPSED, -1L)
            val ageMs = if (sentAt >= 0L)
                android.os.SystemClock.elapsedRealtime() - sentAt else 0L
            val staleRequest = sentAt >= 0L && (ageMs > 4_500L || ageMs < -1_000L)
            if (staleRequest) {
                NeXtepLog.warn("floating_icon",
                    "TOGGLE_STALE_REJECTED ageMs=$ageMs; no display or task changes")
            }
            val ready = !staleRequest &&
                ::floatingDock.isInitialized && ::workspaceController.isInitialized &&
                workspaceConfigReady && workspaceConfig.floatingBubbleEnabled &&
                isScreenReady() && !isKeyguardLocked(context)
            val beforeSession = if (::floatingDock.isInitialized) floatingDock.hasSession() else false
            val beforeExpanded = if (::floatingDock.isInitialized) floatingDock.isExpanded() else false
            NeXtepLog.info("floating_icon",
                "TOGGLE_RECEIVED ready=$ready hasSession=$beforeSession " +
                    "expanded=$beforeExpanded workspaceActive=${::workspaceController.isInitialized && workspaceController.isActive()} " +
                    "anchor=${bubbleAnchorFrom(intent)}")
            val handled = when {
                !ready -> false
                workspaceConfig.floatingWindowMode ==
                    WorkspaceConfigContract.FLOATING_WINDOW_MODE_NATIVE_FREEFORM ->
                    !workspaceController.isActive() && !floatingDock.hasSession() &&
                        ::nativeFreeform.isInitialized && nativeFreeform.addForeground()
                floatingDock.isStableZoomed() -> floatingDock.shrinkStableZoom()
                floatingDock.isExpanded() ->
                    floatingDock.closeFor(FloatingDockSessionPolicy.CloseReason.USER_HIDE)
                workspaceController.isActive() -> false
                else -> floatingDock.expand(bubbleAnchorFrom(intent))
            }
            NeXtepLog.info("floating_icon",
                "TOGGLE_RESULT handled=$handled wasSession=$beforeSession " +
                    "nowSession=${floatingDock.hasSession()} wasExpanded=$beforeExpanded " +
                    "nowExpanded=${floatingDock.isExpanded()} error=${floatingDock.lastError}")
            handled
        }
        TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE,
        TriggerBroadcastContract.ACTION_SET_WORKSPACE -> {
            val target = requestedTarget(intent) ?: false
            if (!WorkspaceRecoveryPolicy.shouldApply(target,
                    intent.getBooleanExtra(TriggerBroadcastContract.EXTRA_ONLY_IF_INACTIVE, false),
                    workspaceController.isActive())) true
            else if (target && isKeyguardLocked(context)) false
            else if (target && ::floatingDock.isInitialized && floatingDock.hasSession() &&
                !floatingDock.closeFor(FloatingDockSessionPolicy.CloseReason.OPEN_ORIGINAL_WORKSPACE)) false
            else workspaceController.setActive(target).also { handled ->
                // The tile/coordinated path must also restore the separate assistant panel.
                if (handled) publishWorkspaceSurfaceState(target)
            }
        }
        TriggerBroadcastContract.ACTION_QUERY_WORKSPACE,
        TriggerBroadcastContract.ACTION_QUERY_DIAGNOSTICS -> true
        TriggerBroadcastContract.ACTION_FOUR_FINGER_WORKSPACE -> fourFingerGestures.handleRequest(intent)
        TriggerBroadcastContract.ACTION_LAUNCH_SLOT_TEST -> {
            val canSwitch = !::floatingDock.isInitialized || !floatingDock.hasSession() ||
                floatingDock.closeFor(FloatingDockSessionPolicy.CloseReason.SLOT_TEST_SWITCH)
            val active = canSwitch &&
                (workspaceController.isActive() || workspaceController.setActive(true))
            active && workspaceController.launchInSlot(
                Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings"),
            )
        }
        else -> false
    }

    private fun responseBundle(existing: Bundle?): Bundle = (existing ?: Bundle()).apply {
        putBoolean(
            TriggerBroadcastContract.EXTRA_ACTIVE,
            workspaceController.isActive(),
        )
        putBoolean(
            TriggerBroadcastContract.EXTRA_FLOATING_PANEL_ACTIVE,
            ::floatingDock.isInitialized && floatingDock.isExpanded(),
        )
        putString(
            TriggerBroadcastContract.EXTRA_HOME_PACKAGE,
            TriggerBroadcastContract.SYSTEM_UI_PACKAGE,
        )
        putInt(
            TriggerBroadcastContract.EXTRA_RUNTIME_PROTOCOL_VERSION,
            TriggerBroadcastContract.RUNTIME_PROTOCOL_VERSION,
        )
        putString(
            TriggerBroadcastContract.EXTRA_SLOT_STATES,
            workspaceController.slotStatesDescription(),
        )
    }

    private val launcherRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            val homePackage = TriggerBroadcastContract.resolveHomePackage(context) ?: return
            val launcherIdentity = intent.getParcelableExtra(
                TriggerBroadcastContract.EXTRA_LAUNCHER_IDENTITY,
                PendingIntent::class.java,
            )
            val verifiedSender = sentFromPackage ?: launcherIdentity?.creatorPackage
            if (verifiedSender != homePackage) {
                NeXtepLog.warn(
                    "launcher_redirect",
                    "Rejected sender=$sentFromPackage token=${launcherIdentity?.creatorPackage} " +
                        "uid=$sentFromUid expected=$homePackage",
                )
                return
            }
            val launchIntent = intent.getParcelableExtra(
                TriggerBroadcastContract.EXTRA_LAUNCH_INTENT,
                Intent::class.java,
            ) ?: return
            if (!workspaceController.openFromLauncher(launchIntent)) {
                NeXtepLog.warn(
                    "launcher_redirect",
                    "SystemUI could not handle component=${launchIntent.component}",
                )
            }
        }
    }

    private fun isKeyguardLocked(context: Context): Boolean = try {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    } catch (error: Throwable) {
        NeXtepLog.warn("systemui_runtime", "Keyguard query failed; activation denied", error)
        true
    }

    private fun isScreenReady(): Boolean =
        applicationContext.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            applicationContext.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    private const val UNLOCK_DISPLAY_RETRY_MS = 100L
    private const val PANEL_COLLAPSE_DELAY_MS = 380L
}
