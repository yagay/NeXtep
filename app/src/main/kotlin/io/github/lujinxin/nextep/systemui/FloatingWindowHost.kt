package io.github.lujinxin.nextep.systemui

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * Pure presentation shell. This class never owns a Task, Display or media session.
 * Collapsing and locking leave the exact same View hierarchy/TextureViews attached.
 */
internal class FloatingWindowHost(private val wm: WindowManager?) {
    private var root: FrameLayout? = null
    private var visible = false

    val attached: Boolean get() = root?.isAttachedToWindow == true
    val expanded: Boolean get() = attached && visible

    fun attach(view: FrameLayout, type: Int, rect: FloatingDockPlacement.Result): Boolean {
        check(root == null) { "Floating host already exists" }
        val lp = WindowManager.LayoutParams(
            rect.width, rect.height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = rect.x
            y = rect.y
            title = "NeXtepFloatingWindowV2"
            setFitInsetsTypes(0)
        }
        checkNotNull(wm).addView(view, lp)
        root = view
        visible = true
        NeXtepLog.info("floating_v2", "HOST_ATTACH bounds=${lp.x},${lp.y},${lp.width},${lp.height}")
        return true
    }

    fun hide(reason: String): Boolean {
        val view = root ?: return true
        if (!visible) return true
        val lp = view.layoutParams as WindowManager.LayoutParams
        lp.alpha = 0f
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        checkNotNull(wm).updateViewLayout(view, lp)
        visible = false
        NeXtepLog.info("floating_v2", "HOST_HIDE reason=$reason display-and-tasks-retained")
        return true
    }

    fun show(): Boolean {
        val view = root ?: return false
        if (!view.isAttachedToWindow) return false
        val lp = view.layoutParams as WindowManager.LayoutParams
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        lp.alpha = 1f
        checkNotNull(wm).updateViewLayout(view, lp)
        visible = true
        NeXtepLog.info("floating_v2", "HOST_SHOW retained-view=true")
        return true
    }

    fun resize(rect: FloatingDockPlacement.Result) {
        val view = root ?: return
        if (!view.isAttachedToWindow) return
        val lp = view.layoutParams as WindowManager.LayoutParams
        if (lp.width == rect.width && lp.height == rect.height &&
            lp.x == rect.x && lp.y == rect.y) return
        lp.width = rect.width
        lp.height = rect.height
        lp.x = rect.x
        lp.y = rect.y
        checkNotNull(wm).updateViewLayout(view, lp)
        NeXtepLog.info("floating_v2", "HOST_REFLOW bounds=${lp.x},${lp.y},${lp.width},${lp.height}")
    }

    fun release() {
        root?.let { view -> runCatching { wm?.removeViewImmediate(view) }
            .onFailure { NeXtepLog.warn("floating_v2", "HOST_RELEASE_FAILED", it) } }
        root = null
        visible = false
    }
}
