package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
    private var suspendedForLock = false
    private data class StableZoom(val index: Int, val taskId: Int, val displayId: Int)
    private var stableZoom: StableZoom? = null
    private var stableControls: FrameLayout? = null
    private val virtualInput = FloatingVirtualDisplayInput(context)
    private val floatingTaskRepository = TaskRepository(context)
    private var stableTouchActive = false
    private val removeBadges = mutableMapOf<Int, TextView>()
    private var removalPrompt: FrameLayout? = null
    private var removalPending = false
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
    private var coordinator: SlotTaskCoordinator? = null
    private val playbackSlotRefresh = Runnable { updateRemoveBadges() }
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

    /** A hidden dock still owns the original live VirtualDisplays and tasks. */
    fun hasSession(): Boolean = host != null
    fun isStableZoomed(): Boolean = stableZoom != null
    fun shrinkStableZoom(): Boolean = restoreStableCompact()
    /** One stable interaction path; no native full-screen task migration. */
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
                val cell = panel?.cells?.getOrNull(i)
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
                val cell = compact.cells[i]
                slot.view.layoutParams = FrameLayout.LayoutParams(
                    cell.width, cell.height, Gravity.TOP or Gravity.LEFT,
                ).apply {
                    leftMargin = cell.x
                    topMargin = cell.y
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
            val cell = compact.cells.getOrNull(index) ?: continue
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
            badge.visibility = if (stableZoom == null && panelVisible &&
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

    /** Existing task and VirtualDisplay ownership always survives a mode change. */
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
            val root = object : FrameLayout(context) {
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
                            if (!blankTapMoved) foldForOutsideTap(null)
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            blankTapMoved = true
                            true
                        }
                        else -> false
                    }
                }
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
            handler.removeCallbacks(playbackSlotRefresh)
            handler.postDelayed(playbackSlotRefresh, 1_000L)
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
            wnd.flags = wnd.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            manager?.updateViewLayout(root, wnd)
            // Keep the root itself VISIBLE and attached: setting GONE or
            // removeViewImmediate can destroy live TextureView surfaces.
            panelVisible = false
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
            manager?.updateViewLayout(root, wnd)
            panelVisible = true
            handler.removeCallbacks(playbackSlotRefresh)
            handler.postDelayed(playbackSlotRefresh, 1_000L)
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
        stableZoom?.let { zoom ->
            if (slots.getOrNull(zoom.index)?.displayId() != zoom.displayId ||
                floatingTaskRepository.findTask(zoom.taskId)?.displayId != zoom.displayId) {
                NeXtepLog.warn("floating_stable",
                    "Zoomed virtual app exited or moved; shrinking without disturbing tasks")
                restoreStableCompact()
            }
        }
        coordinator?.onTasksChanged()
        updateRemoveBadges()
        handler.removeCallbacks(playbackSlotRefresh)
        handler.postDelayed(playbackSlotRefresh, 900L)
    }

    /** Reflow cells on rotation; keep the original displays and their task ownership alive. */
    fun reconfigure() {
        val root = host ?: return
        if (stableZoom != null) {
            runCatching {
                val recorded = recordedLayoutProfile(true)
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
            val oldProfile = profile
            val screenWidth = recorded.geometry.screenWidth
            val screenHeight = recorded.geometry.screenHeight
            val dimensions = calculatePanel(screenWidth, screenHeight, recorded)
            val wnd = root.layoutParams as WindowManager.LayoutParams
            // A bubble tap that merely reopens the same window must not
            // relayout live TextureViews; OEMs can treat that as a surface
            // size change and recreate a video Activity on the virtual display.
            if (recorded == oldProfile && dimensions == panel &&
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
        if (stableZoom != null) return
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
