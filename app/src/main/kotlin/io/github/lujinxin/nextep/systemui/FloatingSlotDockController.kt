package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.apps.LauncherAppRepository
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.display.SlotState
import io.github.lujinxin.nextep.display.SlotTaskCoordinator
import io.github.lujinxin.nextep.display.VirtualDisplaySlot
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceLayoutProfile
import kotlin.math.roundToInt

/**
 * An independently owned horizontal live four-slot task dock. Never calls
 * SystemUiRootTransformController, SystemServerWorkspaceBridge or the normal
 * WorkspaceController, so the current Display 0 app remains fullscreen.
 *
 * VirtualDisplaySlot + SlotTaskCoordinator continue to own application tasks,
 * including migrations and rollback. The special presenter is intentionally a
 * no-op for the physical main surface only.
 */
internal class FloatingSlotDockController(
    private val context: Context,
    private val recordedLayoutProfile: (Boolean) -> WorkspaceLayoutProfile,
) {
    private data class Panel(
        val width: Int, val height: Int, val header: Int, val gap: Int, val pad: Int,
        val railWidth: Int, val railHeight: Int, val cells: List<FloatingDockRailLayout.Cell>,
        val x: Int, val y: Int,
        val orientation: FloatingDockRailLayout.Orientation,
    )

    private val manager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var host: FrameLayout? = null
    private var panelVisible = false
    private var suspendedForLock = false
    // Floating-only promotion. The regular workspace coordinator's swap
    // semantics remain untouched; no main-screen app ever enters this slot.
    private data class PromotedSlot(
        val index: Int,
        val taskId: Int,
        val slotDisplayId: Int,
        val previousMainTaskId: Int?,
        val originalResizeMode: Int?,
    )
    private val floatingTasks = TaskRepository(context)
    private var promotedSlot: PromotedSlot? = null
    private var promotionBusy = false
    private var restoreHint: TextView? = null
    fun hasPromotedApp(): Boolean = promotedSlot != null
    private var picker: FrameLayout? = null
    private var pickerList: LinearLayout? = null
    private var grid: FrameLayout? = null
    private var railViewport: FrameLayout? = null
    private var railOffsetPx = 0
    private var previousControl: TextView? = null
    private var nextControl: TextView? = null
    private var toolbar: LinearLayout? = null
    // The app's SettingsRepository is the source of truth. These are updated
    // from the trusted configuration IPC whenever the user changes Settings.
    private var orientation = FloatingDockRailLayout.Orientation.HORIZONTAL
    private var sizePercent = 100
    // A VirtualDisplay's focus flags cannot change after creation. New
    // settings are applied on the NEXT fresh session only, never by
    // destroying running apps or replacing a folded four-slot dock.
    private var padModeRequested = false
    private var padModeForSession = false
    private var independentModeRequested = false
    private var independentModeForSession = false
    // Bounded CPU wake lock, not a guarantee of virtual display power.
    private val independentCpuWakeLock by lazy {
        runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeXtep:independent-display")
        }.getOrNull()
    }
    private fun releaseIndependentWakeLock() {
        runCatching {
            independentCpuWakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure {
            NeXtepLog.warn("floating_dock", "Independent CPU wake lock release failed", it)
        }
    }
    private var coordinator: SlotTaskCoordinator? = null
    private val playbackCompat = FloatingPlaybackCompatController(context)
    private val playbackSlotRefresh = Runnable {
        playbackCompat.onSlotPackagesChanged(currentPlayerPackages())
    }
    private fun currentPlayerPackages(): Set<String> =
        coordinator?.slotStates().orEmpty().mapNotNull { state ->
            (state as? SlotState.Occupied)?.component?.packageName
        }.toSet()

    fun applyPlaybackCompatSettings(
        enabled: Boolean, autoResume: Boolean, lockAudio: Boolean, packages: Set<String>,
    ) {
        playbackCompat.configure(enabled, autoResume, lockAudio, packages)
        if (hasSession()) playbackCompat.onSlotPackagesChanged(currentPlayerPackages())
    }
    private var slots: List<VirtualDisplaySlot> = emptyList()
    private var profile: WorkspaceLayoutProfile? = null
    private var panel: Panel? = null
    private var bubbleAnchor: FloatingDockPlacement.Anchor? = null
    private var pickerGeneration = 0
    var lastError: String? = null
        private set
    var lastErrorTrace: String? = null
        private set

    /** Visible and interactive, as opposed to a retained hidden live session. */
    fun isExpanded(): Boolean = host != null && panelVisible

    /**
     * Floating mode: first tap promotes the SAME task to Display 0. The app
     * previously on Display 0 stays in its existing task underneath, instead
     * of being exchanged into the virtual slot. Tap the original card or the
     * always-visible floating icon again to move that task back.
     */
    private fun onFloatingSlotTapped(index: Int) {
        if (promotionBusy || !isExpanded() || suspendedForLock) return
        val promoted = promotedSlot
        if (promoted != null) {
            if (promoted.index != index) {
                NeXtepLog.info("floating_pin", "Another slot tapped; restore the promoted task first")
                return
            }
            restorePromotedTask()
            return
        }
        when (val state = coordinator?.slotStates()?.getOrNull(index)) {
            is SlotState.Occupied -> promoteFloatingTask(index, state)
            is SlotState.Ready, SlotState.Empty -> showPicker()
            else -> NeXtepLog.info("floating_pin", "Ignored unavailable slot=$index state=$state")
        }
    }

    private fun moveAndVerify(taskId: Int, displayId: Int) {
        TaskSurfaceCompat.markDisplayExchange(taskId, displayId)
        if (displayId == Display.DEFAULT_DISPLAY) {
            TaskSurfaceCompat.cancelPreparedPromotion(taskId)
        }
        try {
            ActivityTaskManagerCompat.moveTaskToDisplay(taskId, displayId).getOrThrow()
            val deadline = SystemClock.uptimeMillis() + 1_200L
            while (SystemClock.uptimeMillis() < deadline) {
                if (floatingTasks.findTask(taskId)?.displayId == displayId) return
                SystemClock.sleep(35)
            }
            check(floatingTasks.findTask(taskId)?.displayId == displayId) {
                "Task $taskId did not settle on display=$displayId"
            }
        } catch (error: Throwable) {
            TaskSurfaceCompat.cancelDisplayExchange(taskId)
            throw error
        }
    }

    private fun showRestoreHint(index: Int) {
        val view = slots.getOrNull(index)?.view ?: return
        restoreHint?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val hint = TextView(context).apply {
            text = "↙  缩小 / Restore"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), 0)
            background = rounded(Color.argb(240, 14, 63, 73), dp(7))
            contentDescription = "Restore the fullscreen app to this floating window"
        }
        view.addView(hint, FrameLayout.LayoutParams(-1, dp(30), Gravity.TOP))
        restoreHint = hint
    }

    private fun clearRestoreHint() {
        restoreHint?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        restoreHint = null
    }

    private fun promoteFloatingTask(index: Int, state: SlotState.Occupied): Boolean {
        if (promotionBusy || promotedSlot != null) return false
        val task = floatingTasks.findTask(state.taskId)
        if (task == null || task.displayId != state.displayId ||
            slots.getOrNull(index)?.displayId() != state.displayId) {
            NeXtepLog.warn("floating_pin",
                "Promotion skipped: slot=$index taskId=${state.taskId} moved or unavailable")
            coordinator?.onTasksChanged()
            return false
        }
        promotionBusy = true
        val formerMain = floatingTasks.foregroundTask()
            ?.takeIf { it.taskId != task.taskId }?.taskId
        val pending = PromotedSlot(index, task.taskId, state.displayId,
            formerMain, task.resizeMode)
        var moved = false
        try {
            // Stop only this floating coordinator's reconciliation while
            // a task is temporarily on the main display.
            coordinator?.setSuspended(true)
            if (task.resizeMode != 2) {
                ActivityTaskManagerCompat.setTaskResizeable(task.taskId, 2).getOrThrow()
            }
            moveAndVerify(task.taskId, Display.DEFAULT_DISPLAY)
            moved = true
            floatingTasks.findTask(task.taskId)?.token?.let { token ->
                WindowContainerTransactionCompat.applyTaskLayout(
                    token, Rect(), context.resources.displayMetrics.densityDpi, 1,
                ).getOrThrow()
            }
            TaskSurfaceCompat.resetPromotedSlotToFullscreen(task.taskId)
                .onFailure { NeXtepLog.warn("floating_pin",
                    "Fullscreen compositor correction pending", it) }
            floatingTasks.bringTaskToFront(task.taskId).getOrThrow()
            promotedSlot = pending
            showRestoreHint(index)
            NeXtepLog.info("floating_pin",
                "PROMOTED task=${task.taskId} from=${state.displayId} to=0 " +
                    "slot=$index previousMain=$formerMain; no app exchange")
            return true
        } catch (error: Throwable) {
            NeXtepLog.warn("floating_pin", "Fullscreen promotion failed, restoring floating slot", error)
            if (moved) {
                promotedSlot = pending
                restorePromotedTaskInternal()
            } else {
                coordinator?.setSuspended(false)
            }
            return false
        } finally {
            promotionBusy = false
        }
    }

    /** A second tap on the same card or the floating icon shrinks the live app. */
    fun restorePromotedTask(): Boolean {
        if (promotionBusy) return false
        promotionBusy = true
        return try { restorePromotedTaskInternal() } finally { promotionBusy = false }
    }

    private fun restorePromotedTaskInternal(): Boolean {
        val promoted = promotedSlot ?: return true
        val current = floatingTasks.findTask(promoted.taskId)
        if (current == null) {
            NeXtepLog.warn("floating_pin",
                "Promoted task=${promoted.taskId} no longer exists; clearing pin")
            promotedSlot = null
            clearRestoreHint()
            coordinator?.setSuspended(false)
            coordinator?.onTasksChanged()
            return true
        }
        // If another system component moved the task, do not steal it back
        // or overwrite its system-selected windowing mode.
        if (current.displayId != Display.DEFAULT_DISPLAY &&
            current.displayId != promoted.slotDisplayId) {
            NeXtepLog.warn("floating_pin",
                "Task=${promoted.taskId} moved externally to=${current.displayId}; pin cleared")
            promotedSlot = null
            clearRestoreHint()
            coordinator?.setSuspended(false)
            coordinator?.onTasksChanged()
            return true
        }
        return runCatching {
            check(slots.getOrNull(promoted.index)?.displayId() == promoted.slotDisplayId) {
                "Floating destination was released while task was fullscreen"
            }
            if (current.displayId != promoted.slotDisplayId) {
                moveAndVerify(promoted.taskId, promoted.slotDisplayId)
            }
            val restored = checkNotNull(floatingTasks.findTask(promoted.taskId))
            val geometry = checkNotNull(slots[promoted.index].geometry())
            val token = checkNotNull(restored.token)
            WindowContainerTransactionCompat.applyTaskLayout(
                token, Rect(), geometry.densityDpi, 1,
            ).getOrThrow()
            val source = slots[promoted.index].sourceBounds()
            TaskSurfaceCompat.fitSlotSurface(
                promoted.taskId, promoted.slotDisplayId,
                Rect(source.left, source.top, source.right, source.bottom),
            ).onFailure { NeXtepLog.warn("floating_pin",
                "Floating surface fitting pending", it) }
            promoted.originalResizeMode?.let { original ->
                if (original != 2) {
                    ActivityTaskManagerCompat.setTaskResizeable(promoted.taskId, original)
                        .onFailure { NeXtepLog.warn("floating_pin",
                            "Resize mode restoration pending", it) }
                }
            }
            promotedSlot = null
            clearRestoreHint()
            coordinator?.setSuspended(false)
            coordinator?.onTasksChanged()
            promoted.previousMainTaskId?.let { id ->
                floatingTasks.findTask(id)?.takeIf { it.displayId == Display.DEFAULT_DISPLAY }
                    ?.let { floatingTasks.bringTaskToFront(id).onFailure { err ->
                        NeXtepLog.warn("floating_pin", "Previous main foreground restore pending", err)
                    } }
            }
            NeXtepLog.info("floating_pin",
                "RESTORED task=${promoted.taskId} from=0 to=${promoted.slotDisplayId} " +
                    "slot=${promoted.index}; main task unchanged")
            true
        }.getOrElse { error ->
            NeXtepLog.warn("floating_pin",
                "Could not shrink fullscreen task=${promoted.taskId}; session retained", error)
            lastError = "Unable to shrink fullscreen floating app: ${error.message}"
            false
        }
    }

    /** A hidden dock still owns the original live VirtualDisplays and tasks. */
    fun hasSession(): Boolean = host != null

    /** Reflow without releasing any VirtualDisplay or moving the running tasks. */
    fun applyLayoutSettings(orientationValue: String, scalePercent: Int) {
        val nextOrientation = if (orientationValue == "vertical")
            FloatingDockRailLayout.Orientation.VERTICAL else FloatingDockRailLayout.Orientation.HORIZONTAL
        val nextSize = FloatingDockRailLayout.normalizedScale(scalePercent)
        if (nextOrientation == orientation && nextSize == sizePercent) return
        orientation = nextOrientation
        sizePercent = nextSize
        railOffsetPx = 0
        if (hasSession()) {
            buildToolbarControls()
            reconfigure()
        }
        NeXtepLog.info("floating_dock", "Settings updated orientation=$orientation scale=$sizePercent%")
    }

    /** Existing task and VirtualDisplay ownership always survives a mode change. */
    fun applyIndependentDisplaySetting(enabled: Boolean) {
        if (independentModeRequested == enabled) return
        independentModeRequested = enabled
        NeXtepLog.info("floating_dock", "Independent mode requested=$enabled appliedOn=next_new_session")
    }

    fun applyPadModeSetting(enabled: Boolean) {
        if (padModeRequested == enabled) return
        padModeRequested = enabled
        NeXtepLog.info("floating_pad",
            "Pad mode requested=$enabled activeSession=$padModeForSession " +
                "hasSession=${hasSession()} appliedOn=next_new_session")
    }

    /** Move the already visible panel without recreating its live task displays. */
    fun updateAnchor(anchor: FloatingDockPlacement.Anchor) {
        if (bubbleAnchor == anchor) return
        bubbleAnchor = anchor
        if (hasSession()) reconfigure()
    }

    fun expand(anchor: FloatingDockPlacement.Anchor? = null): Boolean {
        if (hasSession()) {
            if (anchor != null) bubbleAnchor = anchor
            return showRetainedSession()
        }
        bubbleAnchor = anchor
        lastError = null
        lastErrorTrace = null
        val type = SystemUiWindowTypeResolver.resolve() ?: run {
            lastError = "SystemUI overlay window type unsupported"
            return false
        }
        return runCatching {
            val recorded = recordedLayoutProfile(true)
            val screenWidth = recorded.geometry.screenWidth
            val screenHeight = recorded.geometry.screenHeight
            val layout = calculatePanel(screenWidth, screenHeight, recorded)
            profile = recorded
            panel = layout
            val root = FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true
                // The old opaque full-size backing rectangle is removed.
                // Only the real preview tiles and compact control buttons
                // draw; empty space is never a blue/black panel.
                setBackgroundColor(Color.TRANSPARENT)
                elevation = 0f
            }
            val frames = FrameLayout(context).apply {
                clipChildren = false
            }
            grid = frames
            // Never detach or reparent these TextureViews on an orientation
            // change: doing so would destroy their VirtualDisplays and live
            // tasks. A clipped FrameLayout and translation-based paging allow
            // horizontal and vertical arrangements with the same task owners.
            val viewport = FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true
                addView(frames, FrameLayout.LayoutParams(layout.railWidth, layout.railHeight))
            }
            railViewport = viewport
            root.addView(viewport, FrameLayout.LayoutParams(layout.width,
                layout.height - layout.header, Gravity.TOP).apply {
                topMargin = layout.header
            })
            independentModeForSession = independentModeRequested
            padModeForSession = padModeRequested || independentModeForSession
            val newSlots = List(WORKSPACE_SLOT_COUNT) { index ->
                VirtualDisplaySlot(context, index,
                    keepOutputWhileSuspended = true,
                    padMultiTaskMode = padModeForSession,
                ).apply {
                    setLayoutProfile(recorded)
                    view.setBackgroundSwipeEnabled(true)
                    view.setBackgroundSwipeSide(SidebarSide.RIGHT)
                    view.background = rounded(Color.argb(230, 8, 16, 22), dp(10))
                    frames.addView(view)
                }
            }
            slots = newSlots
            layoutCells(layout)
            toolbar = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                orientation = if (isVertical()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            }
            root.addView(toolbar, FrameLayout.LayoutParams(-1, layout.header, Gravity.TOP))
            buildToolbarControls()
            val pick = FrameLayout(context).apply {
                visibility = View.GONE
                background = rounded(Color.argb(252, 20, 33, 45), dp(14))
            }
            picker = pick
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            pickerList = list
            val scroll = ScrollView(context).apply {
                isFillViewport = true
                addView(list)
            }
            pick.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            root.addView(pick, FrameLayout.LayoutParams(-1,
                layout.height - layout.header, Gravity.TOP).apply {
                topMargin = layout.header
            })
            val wnd = WindowManager.LayoutParams(
                layout.width, layout.height, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = layout.x
                y = layout.y
                title = "NeXtepFloatingSlotDock"
                // We own a precisely clamped OS safe rectangle even though
                // the WindowManager layer can physically cover system bars.
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            checkNotNull(manager).addView(root, wnd)
            host = root
            panelVisible = true
            suspendedForLock = false
            coordinator = SlotTaskCoordinator(
                context = context,
                slots = newSlots,
                mainTaskPresenter = MainTaskPresentationCoordinator(context, preserveFullscreen = true),
                geometryProvider = { checkNotNull(profile).geometry },
                preserveFullscreenMain = true,
                returnAfterSharing = { false },
            )
            checkNotNull(coordinator).activate()
            // Replace ONLY this floating rail's click handler. The original
            // workspace and SlotTaskCoordinator listener are unchanged.
            newSlots.forEachIndexed { index, slot ->
                slot.view.setOnClickListener { onFloatingSlotTapped(index) }
            }
            playbackCompat.onVisibilityChanged(true, currentPlayerPackages())
            handler.removeCallbacks(playbackSlotRefresh)
            handler.postDelayed(playbackSlotRefresh, 1_000L)
            NeXtepLog.info("floating_dock",
                "Expanded compact orientation=$orientation size=$sizePercent% " +
                    "padFocus=$padModeForSession " +
                    "bounds=[${layout.x},${layout.y},${layout.width},${layout.height}] " +
                    "safe=${FloatingDockSafeArea.measure(context, manager)}")
            true
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            lastErrorTrace = android.util.Log.getStackTraceString(error).take(6_000)
            NeXtepLog.warn("floating_dock", "Unable to show floating dock", error)
            runCatching { coordinator?.deactivate() }
            host?.let { runCatching { manager?.removeViewImmediate(it) } }
            clear()
            false
        }
    }

    /**
     * A normal tap on the bubble or the panel's X merely makes the host
     * transparent and non-touchable. Do NOT detach TextureViews, deactivate
     * the coordinator, or move tasks between physical and virtual displays.
     */
    fun collapse(lockEvent: Boolean = false): Boolean {
        val root = host ?: return true
        // A deliberate hide must put the pinned app back first. Screen lock
        // uses a separate privacy path; do not move tasks while keyguard starts.
        if (!lockEvent && promotedSlot != null && !restorePromotedTask()) return false
        if (!panelVisible) return true
        return runCatching {
            val wnd = root.layoutParams as WindowManager.LayoutParams
            wnd.alpha = 0f
            wnd.flags = wnd.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            manager?.updateViewLayout(root, wnd)
            // Keep the root itself VISIBLE and attached: setting GONE or
            // removeViewImmediate can destroy live TextureView surfaces.
            panelVisible = false
            playbackCompat.onVisibilityChanged(false, currentPlayerPackages(), lockEvent = lockEvent)
            handler.removeCallbacks(playbackSlotRefresh)
            pickerGeneration += 1
            picker?.visibility = View.GONE
            NeXtepLog.info("floating_dock",
                "Hidden live dock without moving tasks or releasing VirtualDisplays; " +
                    slots.joinToString { it.diagnosticOutputState() })
            true
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            NeXtepLog.warn("floating_dock", "Failed to hide retained dock", error)
            false
        }
    }

    /** Lock hides task previews and disconnects their output, not their tasks. */
    fun hideForLock() {
        if (!hasSession()) return
        if (!collapse(lockEvent = true)) {
            NeXtepLog.warn("floating_dock", "Failed to hide window at lock")
        }
        // Even if already folded, SCREEN_OFF must notify the audio adapter.
        // Capture the last PLAYING MediaSessions before the system discards
        // their state, without making the hidden surface visible.
        playbackCompat.onScreenLocked(currentPlayerPackages())
        if (independentModeForSession) {
            runCatching {
                independentCpuWakeLock?.acquire(120_000L)
                NeXtepLog.info("floating_dock",
                    "Independent CPU continuation requested for up to 120 seconds; " +
                        "virtual display ON and playback are not guaranteed")
            }.onFailure {
                NeXtepLog.warn("floating_dock", "Independent CPU wake lock unavailable", it)
            }
        }
        runCatching {
            coordinator?.setSuspended(true)
            suspendedForLock = true
            // Input and preview are hidden at the keyguard; display output
            // remains connected to keep application rendering alive where
            // the platform permits background/virtual-display execution.
            NeXtepLog.info("floating_dock", "Lock retained live outputs: " +
                slots.joinToString { it.diagnosticOutputState() })
        }.onFailure { NeXtepLog.warn("floating_dock", "Output suspension failed during lock", it) }
    }

    /** Centralized exit semantics, so future callers cannot inadvertently
     * destroy parked apps simply because a dock was folded away. */
    fun closeFor(reason: FloatingDockSessionPolicy.CloseReason): Boolean = when {
        FloatingDockSessionPolicy.shouldReleaseDisplays(reason) -> destroySession()
        reason == FloatingDockSessionPolicy.CloseReason.SCREEN_LOCK -> {
            hideForLock()
            !isExpanded()
        }
        else -> collapse()
    }

    /**
     * A lock suspends input and task reconciliation for privacy. Once the
     * keyguard is gone, resume the SAME coordinator even while the overlay is
     * still folded. This matches the original workspace's unlock behavior
     * without making the hidden host touchable or visible.
     */
    fun resumeHiddenSessionAfterUnlock(): Boolean {
        if (!hasSession() || !suspendedForLock) return true
        return runCatching {
            // A promoted app remains on Display 0 until the user taps the
            // same floating card/icon to shrink it. Do not allow suspended
            // reconciliation to consume its intentionally empty slot.
            if (promotedSlot == null) coordinator?.setSuspended(false)
            suspendedForLock = false
            releaseIndependentWakeLock()
            playbackCompat.onScreenUnlocked()
            coordinator?.onTasksChanged()
            NeXtepLog.info("floating_dock", "Unlock restored retained hidden session; " +
                slots.joinToString { it.diagnosticOutputState() })
            true
        }.getOrElse { error ->
            NeXtepLog.warn("floating_dock", "Hidden session resume pending after unlock", error)
            false
        }
    }

    /** Re-expand the SAME session; do not create another VD or restart apps. */
    private fun showRetainedSession(): Boolean {
        val root = host ?: return false
        if (!root.isAttachedToWindow) {
            lastError = "Retained floating surface was detached by WindowManager"
            NeXtepLog.warn("floating_dock", lastError.orEmpty())
            // Do not attempt to create a second set of VirtualDisplays over
            // unparked tasks. A separate explicit shutdown must precede retry.
            return false
        }
        return runCatching {
            if (suspendedForLock) {
                if (promotedSlot == null) coordinator?.setSuspended(false)
                suspendedForLock = false
            }
            pickerGeneration += 1
            picker?.visibility = View.GONE
            reconfigure()
            check(host === root) { "Floating host changed during retained reflow" }
            val wnd = root.layoutParams as WindowManager.LayoutParams
            wnd.alpha = 1f
            wnd.flags = wnd.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            manager?.updateViewLayout(root, wnd)
            panelVisible = true
            playbackCompat.onVisibilityChanged(true, currentPlayerPackages())
            handler.removeCallbacks(playbackSlotRefresh)
            handler.postDelayed(playbackSlotRefresh, 1_000L)
            coordinator?.onTasksChanged()
            NeXtepLog.info("floating_dock",
                "Reshowed retained dock with existing tasks, displays and slot positions; " +
                    slots.joinToString { it.diagnosticOutputState() })
            true
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            NeXtepLog.warn("floating_dock", "Could not show retained dock", error)
            false
        }
    }

    /**
     * Explicit full shutdown ONLY for disabling the feature or switching to
     * the original workspace. Safely park tasks before releasing displays.
     */
    fun destroySession(): Boolean {
        if (!hasSession()) return true
        lastError = null
        // Never release the source VD while its pinned task lives on Display 0.
        if (promotedSlot != null && !restorePromotedTask()) return false
        return runCatching {
            releaseIndependentWakeLock()
            coordinator?.deactivate()
            playbackCompat.shutdown()
            handler.removeCallbacks(playbackSlotRefresh)
            host?.let { manager?.removeViewImmediate(it) }
            clear()
            NeXtepLog.info("floating_dock", "Closed floating session; parked tasks safely")
            true
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            NeXtepLog.warn("floating_dock", "Refused to release display with retained tasks", error)
            false
        }
    }

    fun onTasksChanged() {
        if (!hasSession()) return
        coordinator?.onTasksChanged()
        handler.removeCallbacks(playbackSlotRefresh)
        handler.postDelayed(playbackSlotRefresh, 900L)
    }

    /** Reflow cells on rotation; keep the original displays and their task ownership alive. */
    fun reconfigure() {
        val root = host ?: return
        runCatching {
            val recorded = recordedLayoutProfile(true)
            profile = recorded
            val screenWidth = recorded.geometry.screenWidth
            val screenHeight = recorded.geometry.screenHeight
            val dimensions = calculatePanel(screenWidth, screenHeight, recorded)
            val wnd = root.layoutParams as WindowManager.LayoutParams
            // A bubble tap that merely reopens the same window must not
            // relayout live TextureViews; OEMs can treat that as a surface
            // size change and recreate a video Activity on the virtual display.
            if (recorded == profile && dimensions == panel &&
                wnd.width == dimensions.width && wnd.height == dimensions.height &&
                wnd.x == dimensions.x && wnd.y == dimensions.y
            ) {
                NeXtepLog.debug("floating_dock", "Retained geometry unchanged; skipped slot reflow")
                return@runCatching
            }
            val oldPanel = panel
            profile = recorded
            panel = dimensions
            NeXtepLog.info("floating_dock",
                "Reflow retained layout old=${oldPanel?.width}x${oldPanel?.height} " +
                    "new=${dimensions.width}x${dimensions.height} " +
                    "oldCanvas=${slots.firstOrNull()?.geometry()} newCanvas=${recorded.canvas}")
            slots.forEach { it.setLayoutProfile(recorded) }
            wnd.width = dimensions.width
            wnd.height = dimensions.height
            wnd.x = dimensions.x
            wnd.y = dimensions.y
            railViewport?.layoutParams = FrameLayout.LayoutParams(dimensions.width,
                dimensions.height - dimensions.header).apply { topMargin = dimensions.header }
            toolbar?.layoutParams = FrameLayout.LayoutParams(-1, dimensions.header, Gravity.TOP)
            grid?.layoutParams = FrameLayout.LayoutParams(dimensions.railWidth, dimensions.railHeight)
            picker?.layoutParams = FrameLayout.LayoutParams(-1,
                dimensions.height - dimensions.header).apply { topMargin = dimensions.header }
            layoutCells(dimensions)
            applyRailOffset()
            manager?.updateViewLayout(root, wnd)
            if (picker?.visibility == View.VISIBLE) placeExpandedPicker()
        }.onFailure {
            NeXtepLog.warn("floating_dock", "Rotation reflow failed; closing dock", it)
            collapse()
        }
    }

    private fun clear() {
        clearRestoreHint()
        promotedSlot = null
        promotionBusy = false
        pickerGeneration += 1
        coordinator = null
        slots = emptyList()
        grid = null
        railViewport = null
        railOffsetPx = 0
        previousControl = null
        nextControl = null
        toolbar = null
        picker = null
        pickerList = null
        profile = null
        panel = null
        host = null
        panelVisible = false
        suspendedForLock = false
    }

    private fun calculatePanel(width: Int, height: Int, recorded: WorkspaceLayoutProfile): Panel {
        // Match the original one-main/four-small preview dimensions exactly;
        // the extra floating presentation changes arrangement, not size.
        val plan = FloatingDockRailLayout.calculate(
            width, height, context.resources.displayMetrics.density,
            recorded.slots.map { it.width to it.height },
            orientation, sizePercent,
        )
        val position = FloatingDockPlacement.calculate(
            width, height, plan.panelWidth, plan.panelHeight, bubbleAnchor,
            recorded.densityDpi / 160f,
            FloatingDockSafeArea.measure(context, manager),
        )
        return Panel(position.width, position.height, plan.headerHeight, plan.gap,
            plan.padding, plan.railContentWidth, plan.railHeight, plan.cells,
            position.x, position.y, orientation)
    }

    private fun layoutCells(sizes: Panel) {
        slots.forEachIndexed { index, slot ->
            val cell = sizes.cells[index]
            slot.view.layoutParams = FrameLayout.LayoutParams(
                cell.width, cell.height, Gravity.TOP or Gravity.LEFT,
            ).apply {
                leftMargin = cell.x
                topMargin = cell.y
            }
        }
    }

    /**
     * Horizontal mode uses one short toolbar row; vertical mode uses a tight
     * 2x2 control cluster so a narrow live preview never reserves 240dp.
     * Buttons have their own small background, not a full-width backdrop.
     */
    private fun buildToolbarControls() {
        val target = toolbar ?: return
        target.removeAllViews()
        target.orientation = if (isVertical()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        target.gravity = Gravity.CENTER
        val button = dp(30)
        val separation = dp(4)
        fun tiny(label: String, description: String, onClick: () -> Unit): TextView =
            action(label, Color.WHITE, onClick).apply {
                contentDescription = description
                textSize = 18f
                setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(229, 24, 39, 52))
                    setStroke(dp(1), Color.argb(120, 140, 200, 218))
                }
            }
        previousControl = tiny(if (isVertical()) "↑" else "‹", "Previous floating window") {
            stepRail(forward = false)
        }
        nextControl = tiny(if (isVertical()) "↓" else "›", "Next floating window") {
            stepRail(forward = true)
        }
        val add = tiny("＋", str(R.string.floating_dock_add)) { showPicker() }
        val close = tiny("×", "Hide floating windows") {
            closeFor(FloatingDockSessionPolicy.CloseReason.USER_HIDE)
        }
        if (isVertical()) {
            fun row(left: TextView, right: TextView): LinearLayout =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    addView(left, LinearLayout.LayoutParams(button, button).apply {
                        marginEnd = separation / 2
                    })
                    addView(right, LinearLayout.LayoutParams(button, button).apply {
                        marginStart = separation / 2
                    })
                }
            target.addView(row(checkNotNull(previousControl), checkNotNull(nextControl)),
                LinearLayout.LayoutParams(-2, button).apply { bottomMargin = separation })
            target.addView(row(add, close), LinearLayout.LayoutParams(-2, button))
        } else {
            listOf(checkNotNull(previousControl), checkNotNull(nextControl), add, close)
                .forEachIndexed { index, buttonView ->
                    target.addView(buttonView,
                        LinearLayout.LayoutParams(button, button).apply {
                            if (index > 0) marginStart = separation
                        })
                }
        }
    }

    private fun isVertical() = orientation == FloatingDockRailLayout.Orientation.VERTICAL

    /**
     * Use the arrows instead of intercepting swipe gestures. The same original
     * TaskSwitcherView still owns tap-to-add, tap-to-swap and swipe-to-dismiss.
     */
    private fun stepRail(forward: Boolean) {
        val layout = panel ?: return
        val viewport = railViewport ?: return
        val vertical = layout.orientation == FloatingDockRailLayout.Orientation.VERTICAL
        val visible = if (vertical) viewport.height else viewport.width
        val total = if (vertical) layout.railHeight else layout.railWidth
        val max = (total - visible).coerceAtLeast(0)
        val step = (if (vertical) layout.cells.firstOrNull()?.height
            else layout.cells.firstOrNull()?.width) ?: 0
        railOffsetPx = (railOffsetPx + (if (forward) 1 else -1) * (step + layout.gap))
            .coerceIn(0, max)
        applyRailOffset(animate = true)
    }

    private fun applyRailOffset(animate: Boolean = false) {
        val layout = panel ?: return
        val grid = grid ?: return
        val viewport = railViewport ?: return
        val vertical = layout.orientation == FloatingDockRailLayout.Orientation.VERTICAL
        val max = ((if (vertical) layout.railHeight - viewport.height
            else layout.railWidth - viewport.width)).coerceAtLeast(0)
        railOffsetPx = railOffsetPx.coerceIn(0, max)
        grid.animate().cancel()
        // Center narrow columns under their compact 2x2 toolbar, avoiding
        // a large unused strip beside the actual live cards.
        val centeredX = ((viewport.width - layout.railWidth).coerceAtLeast(0)) / 2f
        val x = centeredX - if (vertical) 0f else railOffsetPx.toFloat()
        val y = if (vertical) -railOffsetPx.toFloat() else 0f
        if (animate) grid.animate().translationX(x).translationY(y).setDuration(170).start()
        else {
            grid.translationX = x
            grid.translationY = y
        }
    }

    /**
     * App selection is intentionally a temporary, wider popup. It must not
     * force the live dock itself to reserve this width when the picker closes.
     */
    private fun placeExpandedPicker() {
        val root = host ?: return
        val recorded = profile ?: return
        val width = recorded.geometry.screenWidth
        val height = recorded.geometry.screenHeight
        val available = FloatingDockPlacement.calculate(
            width, height, dp(280), dp(410), bubbleAnchor,
            recorded.densityDpi / 160f,
            FloatingDockSafeArea.measure(context, manager),
        )
        val wnd = root.layoutParams as? WindowManager.LayoutParams ?: return
        wnd.width = available.width
        wnd.height = available.height
        wnd.x = available.x
        wnd.y = available.y
        picker?.layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.TOP)
        manager?.updateViewLayout(root, wnd)
    }

    private fun hidePicker() {
        pickerGeneration += 1
        picker?.visibility = View.GONE
        reconfigure()
    }

    private fun showPicker() {
        if (promotedSlot != null || promotionBusy) return
        val states = coordinator?.slotStates().orEmpty()
        val empty = states.indexOfFirst { it is SlotState.Ready }
        if (empty < 0) {
            message(R.string.floating_dock_no_slot)
            return
        }
        val pick = picker ?: return
        val list = pickerList ?: return
        pick.visibility = View.VISIBLE
        placeExpandedPicker()
        list.removeAllViews()
        list.addView(action(str(R.string.floating_dock_loading), Color.WHITE) {
            hidePicker()
        })
        val token = ++pickerGeneration
        Thread({
            val entries = runCatching { LauncherAppRepository(context).load(includeProfiles = true) }
                .getOrElse {
                    NeXtepLog.warn("floating_dock", "App chooser query failed", it)
                    emptyList()
                }.sortedBy { it.label.lowercase() }
            handler.post {
                if (token != pickerGeneration || !isExpanded()) return@post
                list.removeAllViews()
                list.addView(action(str(R.string.floating_dock_back), Color.WHITE) {
                    hidePicker()
                })
                if (entries.isEmpty()) {
                    list.addView(action(str(R.string.floating_dock_empty), Color.WHITE) {
                        hidePicker()
                    })
                }
                entries.take(200).forEach { entry ->
                    val row = LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(5), dp(8), dp(5))
                        isClickable = true
                        addView(ImageView(context).apply { setImageDrawable(entry.icon) },
                            LinearLayout.LayoutParams(dp(30), dp(30)))
                        addView(TextView(context).apply {
                            text = entry.label
                            textSize = 14f
                            setTextColor(Color.WHITE)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(dp(12), 0, 0, 0)
                        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            gravity = Gravity.CENTER_VERTICAL
                        })
                        setOnClickListener {
                            val result = coordinator?.launchInSlot(entry.launchIntent(), empty)
                            if (result?.isSuccess == true) {
                                hidePicker()
                            } else {
                                val error = result?.exceptionOrNull()
                                NeXtepLog.warn("floating_dock", "App launch failed", error
                                    ?: IllegalStateException("Slot controller unavailable"))
                                message(R.string.floating_dock_launch_failed)
                            }
                        }
                    }
                    list.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
                }
            }
        }, "NeXtep-FloatingDockApps").apply { isDaemon = true }.start()
    }

    private fun action(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(color)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun action(label: String, onClick: () -> Unit): TextView =
        action(label, Color.WHITE, onClick)

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun message(id: Int) {
        Toast.makeText(context, str(id), Toast.LENGTH_SHORT).show()
    }

    private fun str(id: Int) = AppLanguage.string(context, id)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
