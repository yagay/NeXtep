package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SystemUI-owned, small draggable launcher for the existing live workspace.
 *
 * It deliberately does not duplicate VirtualDisplays or move slot TextureViews
 * between parents. A tap activates the existing four-slot task coordinator,
 * and the bubble disappears for as long as the workspace is active.
 *
 * Window insertion/removal is guarded: an unsupported ROM must fail open,
 * leaving the normal workspace gestures and Quick Settings entry intact.
 */
internal class FloatingWorkspaceBubble(
    private val context: Context,
    private val onOpen: () -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("nextep_floating_bubble_position", Context.MODE_PRIVATE)
    private val diameter = dp(48)
    private var params: WindowManager.LayoutParams? = null
    private var shown = false
    private var moved = false
    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0
    private val view = FrameLayout(context).apply {
        val button = TextView(context).apply {
            text = "▦"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = AppLanguage.string(context, R.string.floating_bubble_open)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(238, 30, 52, 65))
                setStroke(dp(1), Color.argb(210, 125, 222, 231))
            }
            elevation = dp(5).toFloat()
        }
        addView(button, LayoutParams(diameter, diameter, Gravity.CENTER))
        contentDescription = AppLanguage.string(context, R.string.floating_bubble_open)
        isClickable = true
        isFocusable = false
        setOnClickListener { onOpen() }
        setOnTouchListener { _, event -> onBubbleTouch(event) }
    }

    fun setVisible(visible: Boolean) {
        if (visible == shown) {
            if (visible) updateBounds()
            return
        }
        if (!visible) {
            shown = false
            runCatching { manager?.removeViewImmediate(view) }.onFailure {
                NeXtepLog.warn("floating_workspace", "Unable to remove bubble", it)
            }
            params = null
            return
        }
        val type = SystemUiWindowTypeResolver.resolve() ?: return
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(diameter)
        val height = metrics.heightPixels.coerceAtLeast(diameter)
        val side = prefs.getInt(KEY_SIDE, 1).coerceIn(0, 1)
        val fraction = prefs.getFloat(KEY_VERTICAL, 0.4f).coerceIn(0.08f, 0.85f)
        val left = if (side == 0) dp(8) else width - diameter - dp(8)
        val bounds = WindowManager.LayoutParams(
            diameter, diameter, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = left.coerceAtLeast(0)
            y = (height * fraction).roundToInt().coerceIn(dp(36), (height - diameter - dp(36)).coerceAtLeast(dp(36)))
            title = "NeXtepFloatingBubble"
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching {
            manager?.addView(view, bounds) ?: error("WindowManager unavailable")
            params = bounds
            shown = true
        }.onFailure {
            shown = false
            params = null
            NeXtepLog.warn("floating_workspace", "Bubble unsupported on this ROM", it)
        }
    }

    private fun onBubbleTouch(event: MotionEvent): Boolean {
        val p = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = event.rawX
                startRawY = event.rawY
                startX = p.x
                startY = p.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                if (!moved && abs(dx) < dp(7) && abs(dy) < dp(7)) return true
                moved = true
                val metrics = context.resources.displayMetrics
                p.x = (startX + dx.roundToInt()).coerceIn(0, (metrics.widthPixels - diameter).coerceAtLeast(0))
                p.y = (startY + dy.roundToInt()).coerceIn(
                    dp(28),
                    (metrics.heightPixels - diameter - dp(24)).coerceAtLeast(dp(28)),
                )
                updateBounds()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    view.performClick()
                } else {
                    val metrics = context.resources.displayMetrics
                    val right = p.x + diameter / 2 >= metrics.widthPixels / 2
                    p.x = if (right) (metrics.widthPixels - diameter - dp(8)).coerceAtLeast(0) else dp(8)
                    updateBounds()
                    prefs.edit()
                        .putInt(KEY_SIDE, if (right) 1 else 0)
                        .putFloat(KEY_VERTICAL, p.y.toFloat() / metrics.heightPixels.coerceAtLeast(1))
                        .apply()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun updateBounds() {
        val p = params ?: return
        runCatching { manager?.updateViewLayout(view, p) }.onFailure {
            NeXtepLog.warn("floating_workspace", "Bubble position update failed", it)
        }
    }

    private fun dp(value: Int): Int = (context.resources.displayMetrics.density * value).roundToInt()
    private companion object {
        const val KEY_SIDE = "side"
        const val KEY_VERTICAL = "vertical"
    }
}
