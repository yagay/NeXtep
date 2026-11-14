package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.apps.LauncherAppRepository
import io.github.lujinxin.nextep.floating.FloatingPanelStateController
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.display.SlotState
import io.github.lujinxin.nextep.display.SlotTaskCoordinator
import io.github.lujinxin.nextep.display.VirtualDisplaySlot
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
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
    private val panelState = FloatingPanelStateController()
    private fun setPanelExpanded(expanded: Boolean, reason: String) {
        panelState.onVisibilityChanged(expanded)
        runCatching {
            context.sendBroadcast(
                Intent(TriggerBroadcastContract.ACTION_FLOATING_PANEL_STATE_CHANGED)
                    .setPackage(TriggerBroadcastContract.MODULE_PACKAGE)
                    .putExtra(TriggerBroadcastContract.EXTRA_FLOATING_PANEL_ACTIVE, expanded),
            )
        }.onFailure {
            NeXtepLog.warn("floating_dock", "ICON_STATE_SEND_FAILED reason=$reason", it)
        }
        NeXtepLog.info("floating_dock", "PANEL_STATE=${panelState.state} reason=$reason")
    }
    private var popupBackEnabled = true
    private var swipeCollapseEnabled = true
    private var popupEverFocused = false
    private var popupBackDispatcher: OnBackInvokedDispatcher? = null
    private val popupBackCallback = OnBackInvokedCallback {
        if (popupBackEnabled && panelVisible && !suspendedForLock) {
            NeXtepLog.info("floating_dock", "POPUP_SYSTEM_BACK_COLLAPSE")
            if (stableZoom != null) restoreStableCompact() else collapse()
        }
    }

    private fun unregisterPopupBackCallback() {
        val dispatcher = popupBackDispatcher ?: return
        popupBackDispatcher = null
        runCatching { dispatcher.unregisterOnBackInvokedCallback(popupBackCallback) }
            .onFailure { NeXtepLog.warn("floating_dock", "POPUP_BACK_UNREGISTER_FAILED", it) }
    }

    private fun syncPopupBackCallback(view: View) {
        unregisterPopupBackCallback()
        if (!popupBackEnabled || !panelVisible || !view.isAttachedToWindow) return
        runCatching {
            val dispatcher = view.findOnBackInvokedDispatcher() ?: return
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, popupBackCallback)
            popupBackDispatcher = dispatcher
            NeXtepLog.info("floating_dock", "POPUP_BACK_CALLBACK_INSTALLED")
        }.onFailure { NeXtepLog.warn("floating_dock", "POPUP_BACK_CALLBACK_UNAVAILABLE", it) }
    }
    private var suspendedForLock = false
    private data class StableZoom(val index: Int, val taskId: Int, val displayId: Int)
    private var stableZoom: StableZoom? = null
    private var stableControls: FrameLayout? = null
    private val virtualInput = FloatingVirtualDisplayInput(context)
    private var stableTouchActive = false
    private val floatingTaskRepository = TaskRepository(context)
    private val removeBadges = mutableMapOf<Int, TextView>()
    private var removalPrompt: FrameLayout? = null
    private var removalPending = false
    private var autoParkPending = false
    private var outsideFoldPending = false
    private val outsideFold = Runnable {
        outsideFoldPending = false
        // Only a genuinely open floating dock may auto-fold. Never interfere
        // with an active removal confirmation or the real full-screen task.
        if (!panelVisible || suspendedForLock || removalPending ||
            host == null) return@Runnable
        NeXtepLog.info("floating_dock",
            "OUTSIDE_TAP_FOLD; retaining original virtual displays and tasks")
        collapse()
    }

    /**
     * ACTION_OUTSIDE comes from FLAG_WATCH_OUTSIDE_TOUCH. The original click
     * belongs to the app under the overlay: this callback only folds NeXtep.
     * The bubble itself has its own toggle handler; avoid double-toggling it.
     */
    private fun foldForOutsideTap(event: MotionEvent?) {
        if (!panelVisible || suspendedForLock || removalPending ||
            outsideFoldPending) return
        if (event != null && event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val bubble = bubbleAnchor
            if (bubble != null && bubble.size > 0 &&
                event.rawX >= bubble.x && event.rawX < bubble.x + bubble.size &&
                event.rawY >= bubble.y && event.rawY < bubble.y + bubble.size) {
                NeXtepLog.debug("floating_dock", "Outside tap belongs to floating bubble; defer to bubble toggle")
                return
            }
        }
        outsideFoldPending = true
        handler.post(outsideFold)
    }
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
    private var resizingDock = false
    private var coordinator: SlotTaskCoordinator? = null
    private var slots: List<VirtualDisplaySlot> = emptyList()
    // A subset of the four EXISTING live virtual displays is presented in
    // the dock. Never detach, destroy or reindex the underlying Task owners.
    private val visibleSlotIndices = linkedSetOf(0, 1, 2, 3)
    private fun cellFor(slotIndex: Int, layout: Panel): FloatingDockRailLayout.Cell? =
        visibleSlotIndices.indexOf(slotIndex).takeIf { it >= 0 }
            ?.let { layout.cells.getOrNull(it) }

    private var profile: WorkspaceLayoutProfile? = null
    private var panel: Panel? = null
    private var bubbleAnchor: FloatingDockPlacement.Anchor? = null
    private var pickerGeneration = 0
    var lastError: String? = null
        private set
    var lastErrorTrace: String? = null
        private set

    /**
     * Input policy for the Android-style expanded popup. Only an expanded popup
     * can request focus; folded/locked windows are always non-touchable and
     * non-focusable. The user can disable focus-taking for incompatible players.
     */
    fun applyPopupInputSettings(backAndFocus: Boolean, swipeCollapse: Boolean) {
        popupBackEnabled = backAndFocus
        swipeCollapseEnabled = swipeCollapse
        val root = host ?: return
        if (!root.isAttachedToWindow) return
        runCatching {
            val lp = root.layoutParams as WindowManager.LayoutParams
            lp.flags = if (panelVisible && backAndFocus) {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            manager?.updateViewLayout(root, lp)
            if (panelVisible && backAndFocus) root.requestFocus()
            syncPopupBackCallback(root)
        }.onFailure { NeXtepLog.warn("floating_dock", "POPUP_INPUT_POLICY_FAILED", it) }
    }

    /** Visible and interactive, as opposed to a retained hidden live session. */
    fun isExpanded(): Boolean = host != null && panelVisible

    /** No toggle or click: a completed navigation event only collapses EXPANDED. */
    fun onNavigationEvent(source: String) {
        if (!panelState.shouldCollapseForNavigation() || !isExpanded()) return
        NeXtepLog.info("floating_dock", "NAV_STATE_COLLAPSE source=$source")
        collapse()
    }

    /**
     * Called by the bubble's long press. Keep the dock collapsed on success;
     * only a previously absent session is created. Never overwrite occupied slots.
     */
    fun quickAddForeground(anchor: FloatingDockPlacement.Anchor?): Boolean {
        if (suspendedForLock || removalPending || stableZoom != null) return false
        val task = floatingTaskRepository.foregroundTask() ?: return false
        if (task.displayId != android.view.Display.DEFAULT_DISPLAY ||
            task.component.packageName == TriggerBroadcastContract.SYSTEM_UI_PACKAGE ||
            task.component.packageName == context.packageName ||
            task.component.packageName == TriggerBroadcastContract.resolveHomePackage(context) ||
            task.vendorWindowed) return false
        val existing = coordinator?.slotStates().orEmpty()
        if (existing.any { it is SlotState.Occupied && it.taskId == task.taskId }) return false
        if (hasSession() && existing.none { it is SlotState.Ready }) return false
        val wasExpanded = isExpanded()
        if (!hasSession() && !expand(anchor)) return false
        // VirtualDisplay creation reports readiness asynchronously; retry only
        // while this exact Task remains physically foreground.
        fun attempt(remaining: Int) {
            if (!hasSession() || suspendedForLock) return
            if (floatingTaskRepository.foregroundTask()?.taskId != task.taskId) {
                NeXtepLog.warn("floating_quick_add", "FOREGROUND_CHANGED task=${task.taskId}")
                if (!wasExpanded) collapse()
                return
            }
            val states = coordinator?.slotStates().orEmpty()
            if (states.none { it is SlotState.Ready } && remaining > 0 &&
                states.any { it is SlotState.Empty }) {
                handler.postDelayed({ attempt(remaining - 1) }, 150L)
                return
            }
            coordinator?.quickParkForeground(task.taskId)
                ?.onSuccess { index ->
                    // Coordinator retains four physical slots and may choose
                    // an otherwise hidden Ready slot. Make the occupied task
                    // visible rather than leaving an inaccessible app.
                    revealOccupiedSlot(index, "quick-add")
                    NeXtepLog.info("floating_quick_add",
                        "PARKED task=${task.taskId} slot=$index")
                }?.onFailure { error ->
                    NeXtepLog.warn("floating_quick_add", "PARK_FAILED task=${task.taskId}", error)
                }
            if (!wasExpanded) collapse()
        }
        handler.post { attempt(20) }
        return true
    }

    /**
     * Move a specifically captured pre-switch Task, never a newly focused
     * task. Permission, package allowlist and foreground identity are checked
     * by the caller; coordinator independently re-reads the actual task.
     *
     * The original four VirtualDisplays remain owned by one coordinator.
     * This method is asynchronous because new VDs become Ready after their
     * TextureViews attach. It never uses OEM Task Zoom or relaunches an app.
     */
    fun autoParkCaptured(
        taskId: Int,
        packageName: String,
        userId: Int,
        reason: String,
        after: (() -> Unit)? = null,
    ): Boolean {
        if (taskId <= 0 || userId < 0 || suspendedForLock || removalPending ||
            stableZoom != null || autoParkPending) {
            NeXtepLog.info("auto_slot", "REJECT_BUSY reason=$reason task=$taskId")
            after?.invoke()
            return false
        }
        val existed = hasSession()
        val expanded = isExpanded()
        if (!existed && !prepareHiddenAutoSession()) {
            NeXtepLog.warn("auto_slot",
                "NO_SESSION task=$taskId reason=$reason error=$lastError")
            after?.invoke()
            return false
        }
        autoParkPending = true
        fun finish() {
            autoParkPending = false
            if (!expanded && hasSession()) collapse()
            after?.invoke()
        }
        fun attempt(left: Int) {
            if (!hasSession() || suspendedForLock) {
                NeXtepLog.info("auto_slot", "CANCELLED task=$taskId reason=$reason")
                finish()
                return
            }
            val task = floatingTaskRepository.findTask(taskId)
            if (task == null || task.component.packageName != packageName ||
                task.userId != userId ||
                task.displayId != android.view.Display.DEFAULT_DISPLAY ||
                task.vendorWindowed || task.windowingMode != 1) {
                NeXtepLog.info("auto_slot", "STALE_TASK task=$taskId reason=$reason")
                finish()
                return
            }
            val states = coordinator?.slotStates().orEmpty()
            val ready = states.any { it is SlotState.Ready }
            val waiting = states.any { it is SlotState.Empty }
            if (!ready && waiting && left > 0) {
                handler.postDelayed({ attempt(left - 1) }, 100L)
                return
            }
            if (!ready) {
                NeXtepLog.info("auto_slot", "NO_FREE_SLOT task=$taskId reason=$reason")
                finish()
                return
            }
            coordinator?.autoParkTask(taskId, packageName, userId)
                ?.onSuccess { index ->
                    revealOccupiedSlot(index, "auto-$reason")
                    NeXtepLog.info("auto_slot",
                        "PARKED reason=$reason task=$taskId user=$userId slot=$index")
                }?.onFailure { error ->
                    NeXtepLog.warn("auto_slot",
                        "PARK_FAILED reason=$reason task=$taskId", error)
                }
            finish()
        }
        handler.post { attempt(if (reason == "lock") 3 else 12) }
        return true
    }

    /** A hidden dock still owns the original live VirtualDisplays and tasks. */
    fun hasSession(): Boolean = host != null
    fun isStableZoomed(): Boolean = stableZoom != null
    fun shrinkStableZoom(): Boolean = restoreStableCompact()
    /**
     * Match main branch: keep the occupied Activity in its original
     * VirtualDisplay and enlarge only its live presentation. Do not migrate
     * the task to Display 0 just for tapping a floating card: OEM task
     * windowing mode APIs can fail after the move and cause a visible flash.
     */
    private fun onFloatingCardTapped(index: Int) {
        if (!panelVisible || suspendedForLock || removalPending) return
        if (stableZoom != null) {
            restoreStableCompact()
            return
        }
        when (val state = coordinator?.slotStates()?.getOrNull(index)) {
            is SlotState.Occupied -> zoomStableCard(index, state)
            is SlotState.Ready, is SlotState.Failed -> coordinator?.onSlotClicked(index)
            SlotState.Empty -> coordinator?.onTasksChanged()
            else -> Unit
        }
    }

    /**
     * Stable mode deliberately keeps the Activity inside its original
     * VirtualDisplay. Only the existing live TextureView's on-screen
     * presentation is enlarged, and real input is injected back into that
     * SAME display. No task reparent, app relaunch or display recreation.
     */
    private fun zoomStableCard(index: Int, state: SlotState.Occupied): Boolean {
        if (stableZoom != null) return false
        if (slots.getOrNull(index)?.displayId() != state.displayId ||
            floatingTaskRepository.findTask(state.taskId)?.displayId != state.displayId) {
            NeXtepLog.warn("floating_stable",
                "ZOOM_REFUSED task=${state.taskId} display=${state.displayId} no longer in slot")
            coordinator?.onTasksChanged()
            return false
        }
        stableZoom = StableZoom(index, state.taskId, state.displayId)
        return runCatching {
            applyStableZoomLayout()
            NeXtepLog.info("floating_stable",
                "STABLE_ZOOM_ENTER task=${state.taskId} slot=$index display=${state.displayId}; no task move")
            true
        }.getOrElse {
            NeXtepLog.warn("floating_stable", "Unable to zoom stable app", it)
            restoreStableCompact()
            false
        }
    }

    private fun stableTouch(index: Int, event: MotionEvent) {
        val zoom = stableZoom ?: return
        if (zoom.index != index || suspendedForLock || removalPending || !panelVisible) return
        val slot = slots.getOrNull(index) ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stableTouchActive = slot.displayId() == zoom.displayId &&
                    floatingTaskRepository.findTask(zoom.taskId)?.displayId == zoom.displayId
                if (!stableTouchActive) {
                    NeXtepLog.warn("floating_input",
                        "STABLE_TOUCH_REFUSED task=${zoom.taskId} display=${zoom.displayId}")
                    return
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (!stableTouchActive) return
            else -> return
        }
        val geometry = slot.geometry() ?: return
        val accepted = virtualInput.sendTouch(
            event, slot.view.textureView, geometry, zoom.displayId)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !accepted) {
            stableTouchActive = false
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) stableTouchActive = false
    }

    private fun applyStableZoomLayout() {
        val zoom = stableZoom ?: return
        val root = host ?: return
        check(slots.getOrNull(zoom.index)?.displayId() == zoom.displayId) {
            "Stable zoom virtual display no longer exists"
        }
        val bounds = manager?.maximumWindowMetrics?.bounds
            ?: Rect(0, 0, context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels)
        val insets = FloatingDockSafeArea.measure(context, manager)
        val width = (bounds.width() - insets.left - insets.right).coerceAtLeast(dp(140))
        val height = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(dp(180))
        val window = root.layoutParams as WindowManager.LayoutParams
        window.width = width
        window.height = height
        window.x = insets.left
        window.y = insets.top

        picker?.visibility = View.GONE
        toolbar?.visibility = View.GONE
        railViewport?.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP)
        grid?.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP)
        grid?.translationX = 0f
        grid?.translationY = 0f
        slots.forEachIndexed { i, slot ->
            slot.view.layoutParams = if (i == zoom.index) {
                FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT)
            } else {
                // Keep sibling TextureViews attached and their surfaces live.
                val cell = panel?.let { cellFor(i, it) }
                FrameLayout.LayoutParams(cell?.width ?: dp(32), cell?.height ?: dp(32),
                    Gravity.TOP or Gravity.LEFT).apply { leftMargin = width + dp(12) }
            }
        }
        removeBadges.values.forEach { it.visibility = View.GONE }
        if (stableControls == null) {
            val controls = FrameLayout(context).apply {
                isClickable = false
                clipChildren = false
            }
            fun smallControl(label: String, description: String, where: Int, click: () -> Unit) {
                val button = TextView(context).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    contentDescription = description
                    isClickable = true
                    background = rounded(Color.argb(218, 23, 39, 51), dp(11))
                    elevation = dp(6).toFloat()
                    setOnClickListener { click() }
                }
                val isBottomRight = where == (Gravity.BOTTOM or Gravity.RIGHT)
                val lp = FrameLayout.LayoutParams(dp(28), dp(28),
                    if (isBottomRight) where else Gravity.TOP or where).apply {
                    if (isBottomRight) {
                        bottomMargin = dp(10)
                        rightMargin = dp(9)
                    } else {
                        topMargin = dp(6)
                        if (where == Gravity.RIGHT) rightMargin = dp(6)
                        if (where == Gravity.LEFT) leftMargin = dp(6)
                    }
                }
                controls.addView(button, lp)
            }
            smallControl("↙", str(R.string.floating_dock_zoom_restore),
                Gravity.LEFT) { restoreStableCompact() }
            smallControl("←", "Back in virtual app",
                Gravity.CENTER_HORIZONTAL) {
                if (!virtualInput.sendBack(zoom.displayId)) NeXtepLog.warn(
                    "floating_input", "STABLE_BACK_FAILED display=${zoom.displayId}")
            }
            smallControl("×", str(R.string.floating_dock_remove_app),
                Gravity.RIGHT) { requestRemoveApp(zoom.index) }
            root.addView(controls, FrameLayout.LayoutParams(-1, -1))
            stableControls = controls
        }
        manager?.updateViewLayout(root, window)
        NeXtepLog.info("floating_stable",
            "STABLE_DISPLAY_ONLY task=${zoom.taskId} display=${zoom.displayId} " +
                "size=${width}x$height originalVDretained=true")
    }

    /** Shrink the live display presentation without moving its app task. */
    private fun restoreStableCompact(): Boolean {
        val previous = stableZoom ?: return true
        val root = host ?: return false
        val compact = panel ?: return false
        return runCatching {
            stableControls?.let { root.removeView(it) }
            stableControls = null
            stableTouchActive = false
            toolbar?.visibility = View.VISIBLE
            val window = root.layoutParams as WindowManager.LayoutParams
            window.width = compact.width
            window.height = compact.height
            window.x = compact.x
            window.y = compact.y
            railViewport?.layoutParams = FrameLayout.LayoutParams(
                compact.width, compact.height - compact.header, Gravity.TOP,
            ).apply { topMargin = compact.header }
            grid?.layoutParams = FrameLayout.LayoutParams(compact.railWidth, compact.railHeight)
            slots.forEachIndexed { i, slot ->
                val cell = cellFor(i, compact)
                val previous = slot.view.layoutParams
                slot.view.layoutParams = FrameLayout.LayoutParams(
                    cell?.width ?: previous?.width?.coerceAtLeast(1) ?: dp(100),
                    cell?.height ?: previous?.height?.coerceAtLeast(1) ?: dp(160),
                    Gravity.TOP or Gravity.LEFT,
                ).apply {
                    leftMargin = cell?.x ?: maxOf(compact.railWidth, compact.width) + dp(24)
                    topMargin = cell?.y ?: dp(4)
                }
            }
            applyRailOffset()
            positionRemoveBadges(compact)
            manager?.updateViewLayout(root, window)
            stableZoom = null
            updateRemoveBadges()
            NeXtepLog.info("floating_stable",
                "STABLE_ZOOM_EXIT task=${previous.taskId} display=${previous.displayId}")
            true
        }.getOrElse {
            NeXtepLog.warn("floating_stable", "Could not restore compact stable mode", it)
            false
        }
    }

    private fun installRemoveBadges() {
        val frame = grid ?: return
        removeBadges.clear()
        for (index in slots.indices) {
            val badge = TextView(context).apply {
                text = "×"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = str(R.string.floating_dock_remove_app)
                background = rounded(Color.argb(226, 35, 45, 54), dp(8))
                elevation = dp(7).toFloat()
                isClickable = true
                visibility = View.GONE
                setOnClickListener { requestRemoveApp(index) }
            }
            removeBadges[index] = badge
            frame.addView(badge)
        }
        panel?.let(::positionRemoveBadges)
        updateRemoveBadges()
    }

    private fun positionRemoveBadges(compact: Panel) {
        for ((index, badge) in removeBadges) {
            val cell = cellFor(index, compact)
            if (cell == null) {
                badge.visibility = View.GONE
                continue
            }
            badge.layoutParams = FrameLayout.LayoutParams(
                dp(19), dp(19), Gravity.TOP or Gravity.LEFT,
            ).apply {
                leftMargin = cell.x + cell.width - dp(21)
                topMargin = cell.y + dp(2)
            }
        }
    }

    private fun updateRemoveBadges() {
        val states = coordinator?.slotStates().orEmpty()
        removeBadges.forEach { (index, badge) ->
            badge.visibility = if (index in visibleSlotIndices &&
                stableZoom == null && panelVisible &&
                states.getOrNull(index) is SlotState.Occupied) View.VISIBLE else View.GONE
        }
    }

    private fun dismissRemovalPrompt() {
        val view = removalPrompt ?: return
        removalPrompt = null
        removalPending = false
        runCatching { manager?.removeViewImmediate(view) }.onFailure {
            NeXtepLog.warn("floating_remove", "Unable to dismiss confirmation", it)
        }
    }

    private fun requestRemoveApp(index: Int) {
        if (!panelVisible || suspendedForLock || removalPending) return
        val state = coordinator?.slotStates()?.getOrNull(index) as? SlotState.Occupied ?: return
        if (floatingTaskRepository.findTask(state.taskId)?.displayId != state.displayId) return
        val type = SystemUiWindowTypeResolver.resolve() ?: return
        val bounds = manager?.maximumWindowMetrics?.bounds
            ?: Rect(0, 0, context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels)
        val insets = FloatingDockSafeArea.measure(context, manager)
        val width = minOf(dp(284), bounds.width() - insets.left - insets.right - dp(16))
            .coerceAtLeast(dp(180))
        val height = dp(148)
        val x = ((bounds.width() - width) / 2).coerceIn(
            insets.left, (bounds.width() - insets.right - width).coerceAtLeast(insets.left))
        val y = ((bounds.height() - height) / 2).coerceIn(
            insets.top, (bounds.height() - insets.bottom - height).coerceAtLeast(insets.top))
        val frame = FrameLayout(context).apply {
            background = rounded(Color.argb(251, 21, 33, 45), dp(14))
            elevation = dp(10).toFloat()
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        content.addView(TextView(context).apply {
            text = str(R.string.floating_dock_remove_question)
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(context).apply { gravity = Gravity.END }
        val cancel = action(str(R.string.floating_dock_remove_cancel)) { dismissRemovalPrompt() }
        val confirm = action(str(R.string.floating_dock_remove_confirm)) {
            dismissRemovalPrompt()
            // The user confirmed removing THIS task, never uninstalling its package.
            val current = coordinator?.slotStates()?.getOrNull(index) as? SlotState.Occupied
            if (current?.taskId != state.taskId || current.displayId != state.displayId ||
                floatingTaskRepository.findTask(state.taskId)?.displayId != state.displayId) {
                NeXtepLog.warn("floating_remove", "Ignored stale remove request task=${state.taskId}")
                return@action
            }
            if (stableZoom?.taskId == state.taskId && !restoreStableCompact()) return@action
            ActivityTaskManagerCompat.removeTask(state.taskId).onSuccess {
                NeXtepLog.info("floating_remove",
                    "REMOVED_CONFIRMED task=${state.taskId} slot=$index display=${state.displayId}")
                coordinator?.onTasksChanged()
                handler.postDelayed({
                    coordinator?.onTasksChanged()
                    updateRemoveBadges()
                }, 400L)
            }.onFailure { error ->
                NeXtepLog.warn("floating_remove", "Unable to remove slot task", error)
                message(R.string.floating_dock_remove_failed)
            }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(38), 1f))
        buttons.addView(confirm, LinearLayout.LayoutParams(0, dp(38), 1f))
        content.addView(buttons, LinearLayout.LayoutParams(-1, dp(38)))
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        val params = WindowManager.LayoutParams(
            width, height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x
            this.y = y
            title = "NeXtepFloatingRemoveConfirmation"
            setFitInsetsTypes(0)
        }
        runCatching {
            checkNotNull(manager).addView(frame, params)
            removalPrompt = frame
            removalPending = true
            NeXtepLog.info("floating_remove",
                "REMOVE_CONFIRM_SHOWN task=${state.taskId} slot=$index")
        }.onFailure { error ->
            NeXtepLog.warn("floating_remove", "Removal confirmation unavailable", error)
            message(R.string.floating_dock_remove_failed)
        }
    }

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

    /** Move the already visible panel without recreating its live task displays. */
    fun updateAnchor(anchor: FloatingDockPlacement.Anchor) {
        if (bubbleAnchor == anchor) return
        bubbleAnchor = anchor
        if (hasSession()) reconfigure()
    }

    fun prepareHiddenAutoSession(): Boolean {
        if (hasSession()) return true
        if (suspendedForLock) return false
        return expand(startHidden = true)
    }

    fun expand(
        anchor: FloatingDockPlacement.Anchor? = null,
        startHidden: Boolean = false,
    ): Boolean {
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
            val root = object : FrameLayout(context) {
                override fun onAttachedToWindow() {
                    super.onAttachedToWindow()
                    handler.post { if (host === this && panelVisible) syncPopupBackCallback(this) }
                }

                override fun onDetachedFromWindow() {
                    unregisterPopupBackCallback()
                    super.onDetachedFromWindow()
                }

                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (popupBackEnabled && panelVisible &&
                        event.keyCode in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
                        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                            NeXtepLog.info("floating_dock",
                                "POPUP_KEY_COLLAPSE code=${event.keyCode}")
                            if (stableZoom != null) restoreStableCompact() else collapse()
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                    super.onWindowFocusChanged(hasWindowFocus)
                    if (hasWindowFocus) {
                        popupEverFocused = true
                    } else if (popupBackEnabled && popupEverFocused && panelVisible &&
                        !removalPending && !suspendedForLock) {
                        handler.post {
                            if (popupBackEnabled && panelVisible && !removalPending &&
                                !hasWindowFocus()) {
                                NeXtepLog.info("floating_dock", "POPUP_FOCUS_LOST_COLLAPSE")
                                collapse()
                            }
                        }
                    }
                }

                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                        foldForOutsideTap(event)
                        // This is just our copy of the outside notification,
                        // not the original tap in the underlying application.
                        return false
                    }
                    return super.dispatchTouchEvent(event)
                }
            }.apply {
                // Within the rectangular WindowManager host, blank gaps are
                // not meaningful controls; a completed blank-area tap folds
                // the dock without stealing touches from slot/buttons.
                val tapSlop = ViewConfiguration.get(context).scaledTouchSlop
                var blankDownX = 0f
                var blankDownY = 0f
                var blankTapMoved = false
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            blankDownX = event.x
                            blankDownY = event.y
                            blankTapMoved = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (kotlin.math.abs(event.x - blankDownX) > tapSlop ||
                                kotlin.math.abs(event.y - blankDownY) > tapSlop
                            ) blankTapMoved = true
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val deltaY = event.y - blankDownY
                            val deltaX = event.x - blankDownX
                            if (!blankTapMoved) foldForOutsideTap(null)
                            else if (swipeCollapseEnabled && deltaY > dp(48) &&
                                kotlin.math.abs(deltaX) < deltaY) {
                                NeXtepLog.info("floating_dock", "POPUP_SWIPE_COLLAPSE")
                                collapse()
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            blankTapMoved = true
                            true
                        }
                        else -> false
                    }
                }
                isFocusable = true
                isFocusableInTouchMode = true
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
            val newSlots = List(WORKSPACE_SLOT_COUNT) { index ->
                VirtualDisplaySlot(context, index,
                    keepOutputWhileSuspended = true,
                    floatingSlot = true,
                ).apply {
                    setLayoutProfile(recorded)
                    // In floating mode a card is fixed in its original slot.
                    // Dismissal uses the tiny confirmed remove icon instead.
                    view.setBackgroundSwipeEnabled(false)
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
                (if (popupBackEnabled && !startHidden) 0
                    else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                    (if (startHidden) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0) or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = layout.x
                y = layout.y
                title = "NeXtepFloatingSlotDock"
                if (startHidden) alpha = 0f
                // We own a precisely clamped OS safe rectangle even though
                // the WindowManager layer can physically cover system bars.
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            checkNotNull(manager).addView(root, wnd)
            host = root
            panelVisible = !startHidden
            setPanelExpanded(!startHidden, if (startHidden) "auto-prepared" else "create")
            popupEverFocused = root.hasWindowFocus()
            if (popupBackEnabled && !startHidden) root.requestFocus()
            syncPopupBackCallback(root)
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
            newSlots.forEachIndexed { index, slot ->
                slot.view.setOnClickListener { onFloatingCardTapped(index) }
                slot.view.setOnLongClickListener { true } // No ordinary task exchange in floating mode.
                slot.view.setOnTouchListener { _, event ->
                    if (stableZoom?.index == index) {
                        stableTouch(index, event)
                        true // Never collapse/trigger a second click during a touch gesture.
                    } else false
                }
            }
            installRemoveBadges()
            NeXtepLog.info("floating_dock",
                "Expanded compact orientation=$orientation size=$sizePercent% " +
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
        dismissRemovalPrompt()
        // An already queued outside tap must not fold a later reopened dock.
        handler.removeCallbacks(outsideFold)
        outsideFoldPending = false
        // Return to the compact presentation before hiding the overlay,
        // keeping every task on exactly the same virtual display.
        if (stableZoom != null && !restoreStableCompact()) {
            if (!lockEvent) return false
            NeXtepLog.warn("floating_stable", "Lock hides preview despite deferred zoom shrink")
        }
        if (!panelVisible) return true
        return runCatching {
            val wnd = root.layoutParams as WindowManager.LayoutParams
            wnd.alpha = 0f
            wnd.flags = wnd.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            manager?.updateViewLayout(root, wnd)
            popupEverFocused = false
            unregisterPopupBackCallback()
            // Keep the root itself VISIBLE and attached: setting GONE or
            // removeViewImmediate can destroy live TextureView surfaces.
            panelVisible = false
            setPanelExpanded(false, "collapse")
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
            coordinator?.setSuspended(false)
            suspendedForLock = false
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
                coordinator?.setSuspended(false)
                suspendedForLock = false
            }
            pickerGeneration += 1
            picker?.visibility = View.GONE
            reconfigure()
            check(host === root) { "Floating host changed during retained reflow" }
            val wnd = root.layoutParams as WindowManager.LayoutParams
            wnd.alpha = 1f
            wnd.flags = wnd.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            wnd.flags = if (popupBackEnabled)
                wnd.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else wnd.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            manager?.updateViewLayout(root, wnd)
            panelVisible = true
            setPanelExpanded(true, "resume")
            popupEverFocused = root.hasWindowFocus()
            if (popupBackEnabled) root.requestFocus()
            syncPopupBackCallback(root)
            coordinator?.onTasksChanged()
            updateRemoveBadges()
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
        dismissRemovalPrompt()
        if (stableZoom != null && !restoreStableCompact()) return false
        lastError = null
        return runCatching {
            coordinator?.deactivate()
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
        stableZoom?.let { zoom ->
            if (slots.getOrNull(zoom.index)?.displayId() != zoom.displayId ||
                floatingTaskRepository.findTask(zoom.taskId)?.displayId != zoom.displayId) {
                NeXtepLog.warn("floating_stable",
                    "Zoomed virtual app exited or moved; shrinking without disturbing tasks")
                restoreStableCompact()
            }
        }
        coordinator?.onTasksChanged()
        // A task might be moved into a retained hidden VirtualDisplay by
        // another trusted path. Never conceal an active application.
        coordinator?.slotStates()?.forEachIndexed { index, state ->
            if (state is SlotState.Occupied) revealOccupiedSlot(index, "reconciliation")
        }
        updateRemoveBadges()
    }

    /** Reflow cells on rotation; keep the original displays and their task ownership alive. */
    fun reconfigure() {
        val root = host ?: return
        if (stableZoom != null) {
            runCatching {
                val recorded = recordedLayoutProfile(true)
                slots.forEach { it.setLayoutProfile(recorded) }
                profile = recorded
                panel = calculatePanel(
                    recorded.geometry.screenWidth, recorded.geometry.screenHeight, recorded)
                applyStableZoomLayout()
            }.onFailure { error ->
                NeXtepLog.warn("floating_stable", "Stable zoom rotation reflow failed", error)
            }
            return
        }
        runCatching {
            val recorded = recordedLayoutProfile(true)
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
        setPanelExpanded(false, "release")
        handler.removeCallbacks(outsideFold)
        outsideFoldPending = false
        dismissRemovalPrompt()
        stableControls = null
        stableZoom = null
        stableTouchActive = false
        removeBadges.clear()
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
        resizingDock = false
        visibleSlotIndices.clear()
        visibleSlotIndices.addAll(0 until WORKSPACE_SLOT_COUNT)
    }

    private fun calculatePanel(width: Int, height: Int, recorded: WorkspaceLayoutProfile): Panel {
        // Match the original one-main/four-small preview dimensions exactly;
        // the extra floating presentation changes arrangement, not size.
        val plan = FloatingDockRailLayout.calculate(
            width, height, context.resources.displayMetrics.density,
            visibleSlotIndices.map { index ->
                recorded.slots[index].let { it.width to it.height }
            },
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
            val cell = cellFor(index, sizes)
            val old = slot.view.layoutParams
            slot.view.layoutParams = FrameLayout.LayoutParams(
                cell?.width ?: old?.width?.coerceAtLeast(1) ?: dp(100),
                cell?.height ?: old?.height?.coerceAtLeast(1) ?: dp(160),
                Gravity.TOP or Gravity.LEFT,
            ).apply {
                // Hidden slots are clipped outside the rail viewport while
                // TextureViews remain attached and their VD surfaces alive.
                leftMargin = cell?.x ?: maxOf(sizes.railWidth, sizes.width) + dp(24)
                topMargin = cell?.y ?: dp(4)
            }
        }
        positionRemoveBadges(sizes)
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
        val less = tiny("−", "减少槽位 / Select slot to hide") { showSlotRemovalPicker() }
        val more = tiny("+", "增加槽位 / Restore a hidden slot") { restoreHiddenSlot() }
        // Drag this handle in the expanded dock; the live VirtualDisplay
        // canvases and Task IDs stay unchanged. Short tap explains the gesture.
        val resize = tiny("⤢", "按住拖动调整小窗大小 / Drag to resize") {}
        val close = tiny("×", "Hide floating windows") {
            closeFor(FloatingDockSessionPolicy.CloseReason.USER_HIDE)
        }
        var downX = 0f
        var downY = 0f
        var startedAt = sizePercent
        var dragging = false
        resize.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!panelVisible || suspendedForLock || stableZoom != null || removalPending)
                        return@setOnTouchListener true
                    downX = event.rawX
                    downY = event.rawY
                    startedAt = sizePercent
                    dragging = false
                    resizingDock = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!resizingDock || !panelVisible) return@setOnTouchListener true
                    val dx = event.rawX - downX
                    val dy = downY - event.rawY
                    val movement = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) dx else dy
                    val desired = FloatingDockRailLayout.normalizedScale(
                        startedAt + (movement / dp(4).toFloat()).roundToInt())
                    if (desired != sizePercent) {
                        dragging = true
                        sizePercent = desired
                        resize.text = "$desired"
                        resize.textSize = 11f
                        railOffsetPx = 0
                        // Only re-layout existing views; never re-create a
                        // VirtualDisplay or re-parent the app Task.
                        reconfigure()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (resizingDock) {
                        resizingDock = false
                        if (!dragging && event.actionMasked == MotionEvent.ACTION_UP)
                            Toast.makeText(context, "按住 ⤢ 向右或向上拖动放大，反向缩小",
                                Toast.LENGTH_SHORT).show()
                        if (dragging) NeXtepLog.info("floating_dock",
                            "USER_DRAG_RESIZE scale=$sizePercent% slots=${visibleSlotIndices.size}")
                    }
                    resize.text = "⤢"
                    resize.textSize = 18f
                    true
                }
                else -> true
            }
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
            target.addView(row(add, close), LinearLayout.LayoutParams(-2, button).apply {
                bottomMargin = separation
            })
            target.addView(row(less, more), LinearLayout.LayoutParams(-2, button).apply {
                bottomMargin = separation
            })
            val scaleLabel = tiny("${sizePercent}%", "当前小窗缩放比例") {
                sizePercent = 100
                railOffsetPx = 0
                reconfigure()
                buildToolbarControls()
            }.apply { textSize = 11f }
            target.addView(row(resize, scaleLabel), LinearLayout.LayoutParams(-2, button))
        } else {
            listOf(checkNotNull(previousControl), checkNotNull(nextControl),
                add, less, more, resize, close)
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

    /**
     * Reduce by choosing ANY visible slot rather than always the last one.
     * Occupied slots cannot be hidden: a hidden app must never become an
     * inaccessible task in one of the retained virtual displays.
     */
    private fun showSlotRemovalPicker() {
        if (!panelVisible || suspendedForLock || stableZoom != null) return
        if (visibleSlotIndices.size <= 1) {
            Toast.makeText(context, "至少保留一个槽位", Toast.LENGTH_SHORT).show()
            return
        }
        val pick = picker ?: return
        val list = pickerList ?: return
        pickerGeneration += 1
        pick.visibility = View.VISIBLE
        placeExpandedPicker()
        list.removeAllViews()
        list.addView(action("← 返回小窗", Color.WHITE) { hidePicker() })
        for (index in visibleSlotIndices.toList()) {
            val state = coordinator?.slotStates()?.getOrNull(index)
            val occupied = state is SlotState.Occupied
            val label = "槽位 ${index + 1}" +
                if (occupied) " · 正在运行应用（不可减少）" else " · 点击减少"
            list.addView(action(label, if (occupied) Color.LTGRAY else Color.WHITE) {
                if (occupied) {
                    Toast.makeText(context, "先移走该槽位中的应用，再减少槽位", Toast.LENGTH_SHORT).show()
                } else if (hideSelectedSlot(index)) hidePicker()
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun hideSelectedSlot(index: Int): Boolean {
        if (!panelVisible || removalPending || stableZoom != null ||
            index !in visibleSlotIndices || visibleSlotIndices.size <= 1) return false
        val occupied = coordinator?.slotStates()?.getOrNull(index) is SlotState.Occupied
        val displayId = slots.getOrNull(index)?.displayId()
        val stillRunning = displayId != null && floatingTaskRepository.runningTasks()
            .any { it.displayId == displayId }
        if (occupied || stillRunning) {
            Toast.makeText(context, "该槽位仍有应用，不能隐藏", Toast.LENGTH_SHORT).show()
            return false
        }
        visibleSlotIndices.remove(index)
        railOffsetPx = 0
        buildToolbarControls()
        reconfigure()
        NeXtepLog.info("floating_dock",
            "VISIBLE_SLOT_REMOVED index=$index visible=${visibleSlotIndices.joinToString()}")
        return true
    }

    private fun revealOccupiedSlot(index: Int, reason: String) {
        if (index in visibleSlotIndices || index !in slots.indices) return
        val updated = (visibleSlotIndices + index).sorted()
        visibleSlotIndices.clear()
        visibleSlotIndices.addAll(updated)
        railOffsetPx = 0
        buildToolbarControls()
        reconfigure()
        NeXtepLog.warn("floating_dock",
            "SLOT_AUTO_REVEALED occupied=$index reason=$reason")
    }

    private fun restoreHiddenSlot() {
        if (!panelVisible || suspendedForLock || stableZoom != null) return
        val index = (0 until WORKSPACE_SLOT_COUNT).firstOrNull {
            it !in visibleSlotIndices
        } ?: run {
            Toast.makeText(context, "已显示全部四个槽位", Toast.LENGTH_SHORT).show()
            return
        }
        // Keep displayed order aligned with stable physical slot indices.
        val all = (visibleSlotIndices + index).sorted()
        visibleSlotIndices.clear()
        visibleSlotIndices.addAll(all)
        railOffsetPx = 0
        buildToolbarControls()
        reconfigure()
        NeXtepLog.info("floating_dock",
            "VISIBLE_SLOT_RESTORED index=$index visible=${visibleSlotIndices.joinToString()}")
    }

    private fun hidePicker() {
        pickerGeneration += 1
        picker?.visibility = View.GONE
        reconfigure()
    }

    private fun showPicker() {
        if (stableZoom != null) return
        val states = coordinator?.slotStates().orEmpty()
        val empty = states.indices.firstOrNull {
            it in visibleSlotIndices && states[it] is SlotState.Ready
        } ?: -1
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
