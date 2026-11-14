package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.lujinxin.nextep.apps.LauncherAppRepository
import io.github.lujinxin.nextep.config.WorkspaceConfigContract
import io.github.lujinxin.nextep.display.SlotState
import io.github.lujinxin.nextep.display.SlotTaskCoordinator
import io.github.lujinxin.nextep.display.VirtualDisplaySlot
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.workspace.WorkspaceLayoutProfile
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * NeXtep floating window V2.
 *
 * The OS overlay (FloatingWindowHost) owns only appearance. The slot coordinator
 * owns real Android Tasks and VirtualDisplays, and survives collapse, lock,
 * orientation changes and zoom. Native full screen has its own transition owner.
 *
 * Unlike the old monolith no UI operation polls TaskRepository, detaches the
 * four TextureViews or destroys the slot coordinator during ordinary folding.
 */
internal class FloatingSlotDockControllerV2(
    private val context: Context,
    private val profileProvider: (Boolean) -> WorkspaceLayoutProfile,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val host = FloatingWindowHost(wm)
    private val player = FloatingPlaybackCompatController(context)
    private val virtualInput = FloatingVirtualDisplayInput(context)
    private val apps = LauncherAppRepository(context)
    private val wakeLock by lazy {
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeXtep:floating-v2")
    }

    private var root: FrameLayout? = null
    private var viewport: FrameLayout? = null
    private var rail: FrameLayout? = null
    private var toolbar: LinearLayout? = null
    private var picker: FrameLayout? = null
    private var confirmation: FrameLayout? = null
    private val removeBadges = mutableMapOf<Int, TextView>()
    private var slots = emptyList<VirtualDisplaySlot>()
    private var coordinator: SlotTaskCoordinator? = null
    private var profile: WorkspaceLayoutProfile? = null
    private var plan: FloatingDockRailLayout.Plan? = null
    private var anchor: FloatingDockPlacement.Anchor? = null
    private var orientation = FloatingDockRailLayout.Orientation.HORIZONTAL
    private var scale = 100
    private var selected = 0
    private var zoomIndex: Int? = null
    private var locked = false
    private var mode = WorkspaceConfigContract.DEFAULT_FLOATING_INTERACTION_MODE
    private var padRequested = false
    private var independentRequested = false
    private var independentInSession = false
    private var showAfterNativeReturn = false
    private var activePickerGeneration = 0

    var lastError: String? = null
        private set
    var lastErrorTrace: String? = null
        private set

    private val native = FloatingNativeTaskSession(context,
        slotAt = { slots.getOrNull(it) },
        onSuspended = { suspend -> coordinator?.setSuspended(suspend || locked) },
        onEntered = {
            host.hide("native-fullscreen")
            player.onVisibilityChanged(false, packages())
        },
        onReturned = {
            if (showAfterNativeReturn && !locked) {
                host.show()
                player.onVisibilityChanged(true, packages())
            }
            showAfterNativeReturn = false
            updateStates()
        },
    )

    fun hasSession(): Boolean = root != null && host.attached
    fun isExpanded(): Boolean = host.expanded
    fun isStableZoomed(): Boolean = zoomIndex != null
    fun hasTemporaryFullscreen(): Boolean = native.active
    fun shrinkStableZoom(): Boolean {
        if (zoomIndex == null) return true
        zoomIndex = null
        rebuildToolbar()
        reconfigure()
        NeXtepLog.info("floating_v2", "STABLE_ZOOM_EXIT")
        return true
    }

    fun returnTemporaryFullscreen(): Boolean {
        showAfterNativeReturn = true
        return native.returnToSlot("floating-bubble")
    }

    fun applyInteractionMode(value: String) {
        mode = WorkspaceConfigContract.normalizeFloatingInteractionMode(value)
        if (mode == WorkspaceConfigContract.FLOATING_INTERACTION_STABLE && native.active) {
            native.returnToSlot("mode-changed")
        }
        NeXtepLog.info("floating_v2", "MODE=$mode")
    }

    fun applyLayoutSettings(orientationValue: String, scalePercent: Int) {
        val next = if (orientationValue == "vertical")
            FloatingDockRailLayout.Orientation.VERTICAL
        else FloatingDockRailLayout.Orientation.HORIZONTAL
        val size = FloatingDockRailLayout.normalizedScale(scalePercent)
        if (next == orientation && size == scale) return
        orientation = next
        scale = size
        if (hasSession()) reconfigure()
    }

    fun applyPadModeSetting(enabled: Boolean) {
        padRequested = enabled
        // Display capabilities are immutable after creation: next fresh session.
    }

    fun applyIndependentDisplaySetting(enabled: Boolean) {
        independentRequested = enabled
    }

    fun applyPlaybackCompatSettings(
        enabled: Boolean, autoResume: Boolean, lockAudio: Boolean, packages: Set<String>,
        autoFloatingSlots: Boolean,
    ) {
        player.configure(enabled, autoResume, lockAudio, packages, autoFloatingSlots)
        player.onSlotPackagesChanged(packages())
    }

    fun updateAnchor(value: FloatingDockPlacement.Anchor) {
        anchor = value
        if (hasSession() && zoomIndex == null) reconfigure()
    }

    fun expand(value: FloatingDockPlacement.Anchor? = null): Boolean {
        if (value != null) anchor = value
        if (native.active) return returnTemporaryFullscreen()
        if (hasSession()) {
            if (locked) return false
            reconfigure()
            return runCatching {
                host.show()
                player.onVisibilityChanged(true, packages())
                updateStates()
                true
            }.getOrElse { failure("RESUME_FAILED", it); false }
        }
        if (locked) return false
        return runCatching { create() }.getOrElse { failure("CREATE_FAILED", it); false }
    }

    private fun create(): Boolean {
        val type = checkNotNull(SystemUiWindowTypeResolver.resolve()) {
            "No usable SystemUI overlay window type"
        }
        val recorded = profileProvider(true)
        profile = recorded
        val layout = layout(recorded)
        plan = layout.first
        val bounds = layout.second
        val screen = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // Android delivers a copy; underlying app keeps the click.
                    onOutside(event)
                    return false
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = true
            clipToPadding = true
            var startX = 0f
            var startY = 0f
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startY = event.y
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(event.x - startX) < slop && abs(event.y - startY) < slop)
                            collapse()
                        true
                    }
                    else -> true
                }
            }
        }
        root = screen
        val content = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
        }
        viewport = content
        val tiles = FrameLayout(context).apply {
            clipChildren = false
        }
        rail = tiles
        content.addView(tiles, FrameLayout.LayoutParams(
            layout.first.railContentWidth, layout.first.railHeight))
        screen.addView(content, FrameLayout.LayoutParams(bounds.width,
            (bounds.height - layout.first.headerHeight).coerceAtLeast(1), Gravity.TOP).apply {
            topMargin = layout.first.headerHeight
        })
        val displays = List(WORKSPACE_SLOT_COUNT) { index ->
            VirtualDisplaySlot(context, index,
                keepOutputWhileSuspended = true,
                padMultiTaskMode = padRequested || independentRequested,
            ).apply {
                setLayoutProfile(recorded)
                view.setBackgroundSwipeEnabled(false)
                view.background = shape(Color.argb(235, 12, 20, 28))
                tiles.addView(view)
                view.setOnClickListener { onSlotTapped(index) }
                view.setOnLongClickListener { true }
                view.setOnTouchListener { _, event ->
                    if (zoomIndex == index) {
                        val geometry = geometry()
                        val display = displayId()
                        if (geometry != null && display != null)
                            virtualInput.sendTouch(event, view.textureView, geometry, display)
                        true
                    } else false
                }
            }
        }
        slots = displays
        independentInSession = independentRequested
        for (index in displays.indices) {
            val slot = displays[index]
            val badge = TextView(context).apply {
                text = "×"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = shape(Color.argb(235, 40, 47, 57))
                visibility = View.GONE
                elevation = dp(6).toFloat()
                setOnClickListener { confirmRemove(index) }
            }
            slot.view.addView(badge, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.RIGHT))
            removeBadges[index] = badge
        }
        val bar = LinearLayout(context).apply { gravity = Gravity.CENTER }
        toolbar = bar
        screen.addView(bar, FrameLayout.LayoutParams(bounds.width,
            layout.first.headerHeight, Gravity.TOP))
        rebuildToolbar()
        coordinator = SlotTaskCoordinator(context, displays,
            MainTaskPresentationCoordinator(context, preserveFullscreen = true),
            geometryProvider = { checkNotNull(profile).geometry },
            preserveFullscreenMain = true,
            returnAfterSharing = { false },
            onExternalForegroundPromotion = { index, occupied, task ->
                // Run after reconciliation: moving the task again while the
                // coordinator iterates Shell snapshots would be reentrant.
                if (task.taskId == occupied.taskId &&
                    task.displayId == Display.DEFAULT_DISPLAY) {
                    handler.post {
                        if (hasSession() && !native.active) {
                            native.adoptExternal(index, occupied,
                                mode == WorkspaceConfigContract.FLOATING_INTERACTION_NATIVE)
                        }
                    }
                    NeXtepLog.info("floating_v2",
                        "EXTERNAL_PROMOTION_RESERVED task=${task.taskId} slot=$index")
                    true
                } else false
            },
        )
        applyLayout()
        host.attach(screen, type, bounds)
        checkNotNull(coordinator).activate()
        player.onVisibilityChanged(true, packages())
        handler.postDelayed({ updateStates() }, 600L)
        NeXtepLog.info("floating_v2", "SESSION_CREATED slots=${displays.size}")
        return true
    }

    fun collapse(lockEvent: Boolean = false): Boolean = runCatching {
        if (zoomIndex != null) shrinkStableZoom()
        confirmation?.let { dismissConfirmation() }
        picker?.let { dismissPicker() }
        host.hide(if (lockEvent) "lock" else "user")
        player.onVisibilityChanged(false, packages(), lockEvent = lockEvent)
        true
    }.getOrElse { failure("HIDE_FAILED", it); false }

    fun onSystemNavigation(source: String) {
        if (native.active) native.returnToSlot("navigation:$source")
        if (host.expanded) {
            NeXtepLog.info("floating_v2", "NAV_COLLAPSE source=$source")
            collapse()
        }
    }

    fun onPhysicalLockSignal(source: String) {
        if (native.active) native.returnToSlot("prelock:$source")
    }

    fun closeFor(reason: FloatingDockSessionPolicy.CloseReason): Boolean {
        return when (reason) {
            FloatingDockSessionPolicy.CloseReason.USER_HIDE -> collapse()
            FloatingDockSessionPolicy.CloseReason.SCREEN_LOCK -> {
                hideForLock()
                !host.expanded
            }
            else -> destroySession()
        }
    }

    private fun hideForLock() {
        if (!hasSession()) return
        locked = true
        if (native.active) native.returnToSlot("SCREEN_OFF")
        collapse(lockEvent = true)
        player.onScreenLocked(packages())
        if (independentInSession) runCatching {
            wakeLock?.acquire(120_000L)
        }.onFailure { NeXtepLog.warn("floating_v2", "WAKELOCK_FAILED", it) }
        runCatching { coordinator?.setSuspended(true) }.onFailure {
            NeXtepLog.warn("floating_v2", "LOCK_SUSPEND_FAILED", it)
        }
        NeXtepLog.info("floating_v2",
            "LOCK_RETAINED " + slots.joinToString { it.diagnosticOutputState() })
    }

    fun resumeHiddenSessionAfterUnlock(): Boolean {
        if (!hasSession()) return true
        if (!locked) return true
        if (native.active) {
            native.onTasksChanged()
            if (native.active) return false
        }
        return runCatching {
            coordinator?.setSuspended(false)
            locked = false
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            player.onScreenUnlocked()
            updateStates()
            NeXtepLog.info("floating_v2", "UNLOCK_RETAINED; host remains hidden")
            true
        }.getOrElse { failure("UNLOCK_FAILED", it); false }
    }

    fun onTasksChanged() {
        if (!hasSession()) return
        native.onTasksChanged()
        coordinator?.onTasksChanged()
        updateStates()
    }

    private fun packages(): Set<String> = coordinator?.slotStates().orEmpty()
        .mapNotNull { (it as? SlotState.Occupied)?.component?.packageName }.toSet()

    private fun updateStates() {
        if (!hasSession()) return
        player.onSlotPackagesChanged(packages())
        coordinator?.slotStates()?.forEachIndexed { index, state ->
            removeBadges[index]?.visibility =
                if (state is SlotState.Occupied && zoomIndex == null && !native.active)
                    View.VISIBLE else View.GONE
        }
    }

    private fun confirmRemove(index: Int) {
        val task = coordinator?.slotStates()?.getOrNull(index) as? SlotState.Occupied ?: return
        if (native.active || locked || confirmation != null) return
        val screen = root ?: return
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = shape(Color.argb(250, 22, 31, 44))
            elevation = dp(20).toFloat()
        }
        content.addView(item("Remove app from slot ${index + 1}?") {},
            LinearLayout.LayoutParams(-1, dp(40)))
        val buttons = LinearLayout(context)
        buttons.addView(item("Cancel") { dismissConfirmation() },
            LinearLayout.LayoutParams(0, dp(36), 1f))
        buttons.addView(item("Remove") {
            dismissConfirmation()
            val current = coordinator?.slotStates()?.getOrNull(index) as? SlotState.Occupied
            if (current?.taskId != task.taskId || current.displayId != task.displayId) {
                NeXtepLog.warn("floating_v2", "REMOVE_STALE task=${task.taskId}")
                return@item
            }
            ActivityTaskManagerCompat.removeTask(task.taskId).onFailure {
                failure("REMOVE_FAILED", it)
            }
            handler.postDelayed({ coordinator?.onTasksChanged(); updateStates() }, 400L)
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        content.addView(buttons)
        val overlay = FrameLayout(context)
        overlay.addView(content, FrameLayout.LayoutParams(-1, dp(100), Gravity.CENTER))
        screen.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        confirmation = overlay
    }

    private fun onOutside(event: MotionEvent) {
        if (!host.expanded) return
        val bubble = anchor
        if (bubble != null && event.rawX >= bubble.x && event.rawX < bubble.x + bubble.size &&
            event.rawY >= bubble.y && event.rawY < bubble.y + bubble.size) return
        handler.post { if (host.expanded) collapse() }
    }

    private fun onSlotTapped(index: Int) {
        if (!host.expanded || locked || native.transitioning) return
        selected = index
        when (val state = coordinator?.slotStates()?.getOrNull(index)) {
            is SlotState.Occupied -> {
                if (mode == WorkspaceConfigContract.FLOATING_INTERACTION_NATIVE) {
                    if (!native.enter(index, state)) {
                        NeXtepLog.warn("floating_v2", "NATIVE_ENTER_REFUSED slot=$index")
                    }
                } else {
                    zoomIndex = index
                    rebuildToolbar()
                    applyLayout()
                    updateStates()
                    NeXtepLog.info("floating_v2", "STABLE_ZOOM_ENTER slot=$index")
                }
            }
            else -> showPicker()
        }
    }

    private fun rebuildToolbar() {
        val bar = toolbar ?: return
        bar.removeAllViews()
        bar.orientation = if (orientation == FloatingDockRailLayout.Orientation.VERTICAL)
            LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        fun button(value: String, action: () -> Unit): TextView = TextView(context).apply {
            text = value
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = shape(Color.argb(220, 29, 43, 56))
            setOnClickListener { action() }
        }
        val actions = if (zoomIndex != null) listOf(
            button("↙") { shrinkStableZoom() },
            button("←") {
                val zoom = zoomIndex ?: return@button
                slots.getOrNull(zoom)?.displayId()?.let { virtualInput.sendBack(it) }
            },
            button("⤢") {
                val zoom = zoomIndex ?: return@button
                val state = coordinator?.slotStates()?.getOrNull(zoom) as? SlotState.Occupied
                if (state != null) {
                    shrinkStableZoom()
                    native.enter(zoom, state)
                }
            },
            button("×") { collapse() },
        ) else listOf(
            button("‹") { step(-1) },
            button("›") { step(1) },
            button("＋") { showPicker() },
            button("×") { collapse() },
        )
        if (bar.orientation == LinearLayout.VERTICAL) {
            for (row in 0 until 2) {
                val group = LinearLayout(context)
                for (col in 0 until 2)
                    group.addView(actions[row * 2 + col],
                        LinearLayout.LayoutParams(dp(32), dp(29)).apply { marginEnd = dp(3) })
                bar.addView(group)
            }
        } else actions.forEach { bar.addView(it,
            LinearLayout.LayoutParams(dp(32), dp(30)).apply { marginEnd = dp(3) }) }
    }

    private fun step(direction: Int) {
        if (zoomIndex != null) { shrinkStableZoom(); return }
        selected = (selected + direction).coerceIn(0, WORKSPACE_SLOT_COUNT - 1)
        applyLayout()
    }

    private fun layout(recorded: WorkspaceLayoutProfile):
        Pair<FloatingDockRailLayout.Plan, FloatingDockPlacement.Result> {
        val geometry = recorded.geometry
        val strip = FloatingDockRailLayout.calculate(
            geometry.screenWidth, geometry.screenHeight, context.resources.displayMetrics.density,
            recorded.slots.map { it.width to it.height }, orientation, scale)
        val bounds = FloatingDockPlacement.calculate(
            geometry.screenWidth, geometry.screenHeight,
            strip.panelWidth, strip.panelHeight, anchor,
            context.resources.displayMetrics.density,
            FloatingDockSafeArea.measure(context, wm))
        return strip to bounds
    }

    private fun fullBounds(): FloatingDockPlacement.Result {
        val metrics = checkNotNull(wm).maximumWindowMetrics.bounds
        val safe = FloatingDockSafeArea.measure(context, wm)
        return FloatingDockPlacement.Result(
            safe.left, safe.top,
            (metrics.width() - safe.left - safe.right).coerceAtLeast(1),
            (metrics.height() - safe.top - safe.bottom).coerceAtLeast(1),
            FloatingDockPlacement.Side.FALLBACK,
        )
    }

    private fun applyLayout() {
        val screen = root ?: return
        val current = plan ?: return
        val preview = viewport ?: return
        val tiles = rail ?: return
        val zoom = zoomIndex
        val desired = if (zoom == null) checkNotNull(profile).let { layout(it).second }
        else fullBounds()
        host.resize(desired)
        val header = if (zoom == null) current.headerHeight else dp(36)
        preview.layoutParams = FrameLayout.LayoutParams(desired.width,
            (desired.height - header).coerceAtLeast(1), Gravity.TOP).apply { topMargin = header }
        toolbar?.layoutParams = FrameLayout.LayoutParams(desired.width, header, Gravity.TOP)
        if (zoom != null) {
            tiles.translationX = 0f
            tiles.translationY = 0f
            tiles.layoutParams = FrameLayout.LayoutParams(desired.width,
                (desired.height - header).coerceAtLeast(1))
            slots.forEachIndexed { index, slot ->
                slot.view.layoutParams = if (index == zoom)
                    FrameLayout.LayoutParams(desired.width,
                        (desired.height - header).coerceAtLeast(1), Gravity.TOP)
                else FrameLayout.LayoutParams(dp(1), dp(1), Gravity.TOP).apply {
                    leftMargin = desired.width + dp(10)
                }
            }
        } else {
            tiles.layoutParams = FrameLayout.LayoutParams(
                current.railContentWidth, current.railHeight)
            slots.forEachIndexed { index, slot ->
                val cell = current.cells[index]
                slot.view.layoutParams = FrameLayout.LayoutParams(cell.width, cell.height).apply {
                    leftMargin = cell.x
                    topMargin = cell.y
                }
            }
            val item = current.cells[selected.coerceIn(0, current.cells.lastIndex)]
            tiles.translationX = if (current.railContentWidth > desired.width)
                -(item.x - dp(4)).coerceIn(0, current.railContentWidth - desired.width).toFloat()
            else 0f
            val height = (desired.height - header).coerceAtLeast(1)
            tiles.translationY = if (current.railHeight > height)
                -(item.y - dp(4)).coerceIn(0, current.railHeight - height).toFloat()
            else 0f
        }
        screen.requestLayout()
        updateStates()
    }

    fun reconfigure() {
        if (!hasSession() || native.active) return
        val recorded = profileProvider(true)
        val next = layout(recorded)
        if (recorded != profile) {
            profile = recorded
            slots.forEach { it.setLayoutProfile(recorded) }
        }
        plan = next.first
        rebuildToolbar()
        applyLayout()
    }

    private fun showPicker() {
        val screen = root ?: return
        if (picker != null || !host.expanded || native.active || locked) return
        val generation = ++activePickerGeneration
        val overlay = FrameLayout(context).apply {
            background = shape(Color.argb(250, 20, 28, 38))
            elevation = dp(16).toFloat()
        }
        picker = overlay
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(list) }
        overlay.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        screen.addView(overlay, FrameLayout.LayoutParams(-1,
            (screen.height - (plan?.headerHeight ?: dp(36))).coerceAtLeast(dp(80)),
            Gravity.BOTTOM))
        list.addView(item("Cancel") { dismissPicker() })
        Thread({
            val entries = runCatching { apps.load() }
                .onFailure { NeXtepLog.warn("floating_v2", "APP_LIST_FAILED", it) }
                .getOrDefault(emptyList())
            handler.post {
                if (picker !== overlay || generation != activePickerGeneration) return@post
                entries.take(150).forEach { entry ->
                    val row = LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(6), dp(4), dp(6), dp(4))
                    }
                    row.addView(ImageView(context).apply { setImageDrawable(entry.icon) },
                        LinearLayout.LayoutParams(dp(28), dp(28)))
                    row.addView(item(entry.label) {
                        dismissPicker()
                        val target = selected.coerceIn(0, WORKSPACE_SLOT_COUNT - 1)
                        coordinator?.launchInSlot(entry.launchIntent(), target)
                            ?.onFailure { NeXtepLog.warn("floating_v2", "APP_LAUNCH_FAILED", it) }
                        handler.postDelayed({ updateStates() }, 400L)
                    }, LinearLayout.LayoutParams(-1, dp(40)))
                    list.addView(row)
                }
            }
        }, "NeXtep-V2-AppPicker").apply { isDaemon = true }.start()
    }

    private fun dismissPicker() {
        activePickerGeneration++
        picker?.let { root?.removeView(it) }
        picker = null
    }

    private fun item(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(8), 0)
        setOnClickListener { action() }
    }

    private fun dismissConfirmation() {
        confirmation?.let { root?.removeView(it) }
        confirmation = null
    }

    fun destroySession(): Boolean {
        if (!hasSession()) return true
        if (!native.shutdown()) {
            lastError = "Native Task return in progress; retained window cannot be destroyed"
            return false
        }
        return runCatching {
            dismissPicker()
            dismissConfirmation()
            player.shutdown()
            coordinator?.deactivate()
            host.release()
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            slots = emptyList()
            removeBadges.clear()
            coordinator = null
            root = null
            viewport = null
            rail = null
            toolbar = null
            profile = null
            plan = null
            zoomIndex = null
            locked = false
            independentInSession = false
            NeXtepLog.info("floating_v2", "SESSION_RELEASED explicit-close=true")
            true
        }.getOrElse { failure("DESTROY_FAILED", it); false }
    }

    private fun failure(stage: String, error: Throwable) {
        lastError = "$stage: ${error.message ?: error.javaClass.simpleName}"
        lastErrorTrace = android.util.Log.getStackTraceString(error).take(6_000)
        NeXtepLog.warn("floating_v2", stage, error)
    }

    private fun shape(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
