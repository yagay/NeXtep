package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
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
    )

    private val manager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var host: FrameLayout? = null
    private var picker: FrameLayout? = null
    private var pickerList: LinearLayout? = null
    private var grid: FrameLayout? = null
    private var railScroll: HorizontalScrollView? = null
    private var coordinator: SlotTaskCoordinator? = null
    private var slots: List<VirtualDisplaySlot> = emptyList()
    private var profile: WorkspaceLayoutProfile? = null
    private var panel: Panel? = null
    private var bubbleAnchor: FloatingDockPlacement.Anchor? = null
    private var pickerGeneration = 0
    var lastError: String? = null
        private set

    fun isExpanded(): Boolean = host != null

    /** Move the already visible panel without recreating its live task displays. */
    fun updateAnchor(anchor: FloatingDockPlacement.Anchor) {
        if (bubbleAnchor == anchor) return
        bubbleAnchor = anchor
        if (isExpanded()) reconfigure()
    }

    fun expand(anchor: FloatingDockPlacement.Anchor? = null): Boolean {
        if (isExpanded()) {
            if (anchor != null) updateAnchor(anchor)
            return true
        }
        bubbleAnchor = anchor
        lastError = null
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
                clipChildren = false
                background = rounded(Color.argb(245, 21, 32, 44), dp(18))
                elevation = dp(8).toFloat()
            }
            val frames = FrameLayout(context).apply {
                clipChildren = false
            }
            grid = frames
            // Each card is the SAME TaskSwitcherView as the original sidebar:
            // one tap on '+' parks the currently fullscreen app; one tap on an
            // occupied slot exchanges it with the main app. Never intercept
            // child touches with the horizontal viewport.
            // Chevrons move the rail without shrinking the original card sizes.
            val scroller = object : HorizontalScrollView(context) {
                override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
            }.apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(frames, FrameLayout.LayoutParams(layout.railWidth, layout.railHeight))
            }
            railScroll = scroller
            root.addView(scroller, FrameLayout.LayoutParams(layout.width,
                layout.height - layout.header, Gravity.TOP).apply {
                topMargin = layout.header
            })
            val newSlots = List(WORKSPACE_SLOT_COUNT) { index ->
                VirtualDisplaySlot(context, index).apply {
                    setLayoutProfile(recorded)
                    view.setBackgroundSwipeEnabled(true)
                    view.setBackgroundSwipeSide(SidebarSide.RIGHT)
                    view.background = rounded(Color.argb(230, 8, 16, 22), dp(10))
                    frames.addView(view)
                }
            }
            slots = newSlots
            layoutCells(layout)
            val controls = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(context).apply {
                    text = str(R.string.floating_dock_title)
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(action("‹", Color.WHITE) {
                    railScroll?.smoothScrollBy(-(checkNotNull(panel).cells.first().width + checkNotNull(panel).gap), 0)
                }, LinearLayout.LayoutParams(dp(30), dp(46)))
                addView(action("›", Color.WHITE) {
                    railScroll?.smoothScrollBy(checkNotNull(panel).cells.first().width + checkNotNull(panel).gap, 0)
                }, LinearLayout.LayoutParams(dp(30), dp(46)))
                addView(action(str(R.string.floating_dock_add), 0xFF8CD5E8.toInt()) {
                    showPicker()
                }, LinearLayout.LayoutParams(-2, dp(46)))
                addView(action(str(R.string.floating_dock_close), Color.WHITE) {
                    collapse()
                }, LinearLayout.LayoutParams(dp(40), dp(46)))
            }
            root.addView(controls, FrameLayout.LayoutParams(-1, layout.header, Gravity.TOP))
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
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            checkNotNull(manager).addView(root, wnd)
            host = root
            coordinator = SlotTaskCoordinator(
                context = context,
                slots = newSlots,
                mainTaskPresenter = MainTaskPresentationCoordinator(context, preserveFullscreen = true),
                geometryProvider = { checkNotNull(profile).geometry },
                preserveFullscreenMain = true,
                returnAfterSharing = { false },
            )
            checkNotNull(coordinator).activate()
            NeXtepLog.info("floating_dock",
                "Expanded beside bubble x=${layout.x} y=${layout.y}, horizontal original-size slots")
            true
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            NeXtepLog.warn("floating_dock", "Unable to show floating dock", error)
            runCatching { coordinator?.deactivate() }
            host?.let { runCatching { manager?.removeViewImmediate(it) } }
            clear()
            false
        }
    }

    /** Keep the overlay off the lockscreen even when task parking must retry. */
    fun hideForLock() {
        host?.visibility = View.GONE
        runCatching { coordinator?.setSuspended(true) }.onFailure {
            NeXtepLog.warn("floating_dock", "Output suspension failed during lock", it)
        }
        collapse()
    }

    /** Park every slot task before detaching its TextureView; never strand a task on a dead display. */
    fun collapse(): Boolean {
        if (!isExpanded()) return true
        lastError = null
        return runCatching {
            coordinator?.deactivate()
            host?.let { manager?.removeViewImmediate(it) }
            clear()
            NeXtepLog.info("floating_dock", "Collapsed; all slot tasks parked safely")
            true
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            NeXtepLog.warn("floating_dock", "Collapse deferred to preserve task displays", error)
            false
        }
    }

    fun onTasksChanged() {
        if (isExpanded()) coordinator?.onTasksChanged()
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
            panel = dimensions
            slots.forEach { it.setLayoutProfile(recorded) }
            val wnd = root.layoutParams as WindowManager.LayoutParams
            wnd.width = dimensions.width
            wnd.height = dimensions.height
            wnd.x = dimensions.x
            wnd.y = dimensions.y
            railScroll?.layoutParams = FrameLayout.LayoutParams(dimensions.width,
                dimensions.height - dimensions.header).apply { topMargin = dimensions.header }
            grid?.layoutParams = FrameLayout.LayoutParams(dimensions.railWidth, dimensions.railHeight)
            picker?.layoutParams = FrameLayout.LayoutParams(-1,
                dimensions.height - dimensions.header).apply { topMargin = dimensions.header }
            layoutCells(dimensions)
            manager?.updateViewLayout(root, wnd)
        }.onFailure {
            NeXtepLog.warn("floating_dock", "Rotation reflow failed; closing dock", it)
            collapse()
        }
    }

    private fun clear() {
        pickerGeneration += 1
        coordinator = null
        slots = emptyList()
        grid = null
        railScroll = null
        picker = null
        pickerList = null
        profile = null
        panel = null
        host = null
    }

    private fun calculatePanel(width: Int, height: Int, recorded: WorkspaceLayoutProfile): Panel {
        // Match the original one-main/four-small preview dimensions exactly;
        // the extra floating presentation changes arrangement, not size.
        val plan = FloatingDockRailLayout.calculate(
            width, height, context.resources.displayMetrics.density,
            recorded.slots.map { it.width to it.height },
        )
        val position = FloatingDockPlacement.calculate(
            width, height, plan.panelWidth, plan.panelHeight, bubbleAnchor,
            recorded.densityDpi / 160f,
        )
        return Panel(position.width, position.height, plan.headerHeight, plan.gap,
            plan.padding, plan.railContentWidth, plan.railHeight, plan.cells,
            position.x, position.y)
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

    private fun showPicker() {
        val states = coordinator?.slotStates().orEmpty()
        val empty = states.indexOfFirst { it is SlotState.Ready }
        if (empty < 0) {
            message(R.string.floating_dock_no_slot)
            return
        }
        val pick = picker ?: return
        pick.visibility = View.VISIBLE
        val list = pickerList ?: return
        list.removeAllViews()
        list.addView(action(str(R.string.floating_dock_loading), Color.WHITE) {
            pick.visibility = View.GONE
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
                    pick.visibility = View.GONE
                })
                if (entries.isEmpty()) {
                    list.addView(action(str(R.string.floating_dock_empty), Color.WHITE) {
                        pick.visibility = View.GONE
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
                                pick.visibility = View.GONE
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
