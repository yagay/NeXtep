package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.view.WindowInsets
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * Independently measure the ACTUAL OS bars. The original workspace profile
 * deliberately ignores navigation insets in gesture mode; floating overlays
 * must never reuse that decision when positioning their physical windows.
 */
internal object FloatingDockSafeArea {
    fun measure(context: Context, manager: WindowManager?): FloatingDockPlacement.Insets {
        val system = runCatching {
            manager?.maximumWindowMetrics?.windowInsets
                ?.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars() or
                        WindowInsets.Type.displayCutout(),
                )
        }.onFailure {
            NeXtepLog.warn("floating_dock", "OS safe insets unavailable", it)
        }.getOrNull()

        fun systemDimension(name: String): Int = runCatching {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            if (id != 0) context.resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
        val statusTop = systemDimension("status_bar_height")
        val navMode = runCatching {
            val id = context.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            if (id != 0) context.resources.getInteger(id) else -1
        }.getOrDefault(-1)
        val navFallback = if (navMode == 2) 0 else
            systemDimension("navigation_bar_height")
        val metrics = context.resources.displayMetrics
        val wide = metrics.widthPixels > metrics.heightPixels
        val bottomFallback = if (system == null || system.bottom == 0) {
            if (wide) 0 else navFallback
        } else 0
        val sideFallback = if (wide && (system == null ||
            (system.left == 0 && system.right == 0 && system.bottom == 0))) navFallback else 0

        return FloatingDockPlacement.Insets(
            left = maxOf(0, system?.left ?: 0),
            top = maxOf(0, system?.top ?: 0, statusTop),
            right = maxOf(0, system?.right ?: 0, sideFallback),
            bottom = maxOf(0, system?.bottom ?: 0, bottomFallback),
        )
    }
}
