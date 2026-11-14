package io.github.lujinxin.nextep.systemui

import kotlin.math.roundToInt

/**
 * The dock is a physical screen overlay with layout-in-screen enabled. Its
 * visible AND touchable rectangle must remain between the actual status bar,
 * navigation controls and any display cutout in either screen rotation.
 */
internal object FloatingDockPlacement {
    data class Anchor(val x: Int, val y: Int, val size: Int)
    data class Insets(val left: Int = 0, val top: Int = 0,
                      val right: Int = 0, val bottom: Int = 0) {
        init { require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) }
    }
    enum class Side { LEFT_OF_BUBBLE, RIGHT_OF_BUBBLE, FALLBACK }
    data class Result(val x: Int, val y: Int, val width: Int, val height: Int, val side: Side)

    fun calculate(
        screenWidth: Int,
        screenHeight: Int,
        preferredWidth: Int,
        preferredHeight: Int,
        anchor: Anchor?,
        density: Float,
        safeInsets: Insets = Insets(),
    ): Result {
        require(screenWidth > 1 && screenHeight > 1)
        require(preferredWidth > 0 && preferredHeight > 0)
        require(density.isFinite() && density > 0f)
        fun dp(value: Int) = (value * density).roundToInt().coerceAtLeast(1)
        val insetLeft = safeInsets.left.coerceIn(0, screenWidth - 1)
        val insetRight = (screenWidth - safeInsets.right).coerceIn(insetLeft + 1, screenWidth)
        val insetTop = safeInsets.top.coerceIn(0, screenHeight - 1)
        val insetBottom = (screenHeight - safeInsets.bottom).coerceIn(insetTop + 1, screenHeight)

        // Honor all system-bar/cutout insets before applying a small visual gap.
        // A tiny screen should still get a nonzero, bounded window.
        val marginX = minOf(dp(8), ((insetRight - insetLeft - 1) / 2).coerceAtLeast(0))
        val marginY = minOf(dp(8), ((insetBottom - insetTop - 1) / 2).coerceAtLeast(0))
        val left = insetLeft + marginX
        val right = insetRight - marginX
        val top = insetTop + marginY
        val bottom = insetBottom - marginY
        val width = minOf(preferredWidth, (right - left).coerceAtLeast(1))
        val height = minOf(preferredHeight, (bottom - top).coerceAtLeast(1))
        val gap = dp(8)

        val valid = anchor?.takeIf { it.size > 0 && it.size < screenWidth &&
            it.x >= -it.size && it.x < screenWidth &&
            it.y >= -it.size && it.y < screenHeight
        }
        fun fallback(): Result = Result(
            right - width,
            top + (bottom - top - height) / 2,
            width, height, Side.FALLBACK,
        )
        if (valid == null) return fallback()

        val bx = valid.x.coerceIn(0, (screenWidth - valid.size).coerceAtLeast(0))
        val by = valid.y.coerceIn(0, (screenHeight - valid.size).coerceAtLeast(0))
        val leftSpace = (bx - gap - left).coerceAtLeast(0)
        val rightSpace = (right - bx - valid.size - gap).coerceAtLeast(0)
        val preferLeft = bx + valid.size / 2 >= (insetLeft + insetRight) / 2
        val placeLeft = when {
            preferLeft && leftSpace > 0 -> true
            !preferLeft && rightSpace > 0 -> false
            leftSpace > 0 -> true
            rightSpace > 0 -> false
            else -> return fallback()
        }
        val fittedWidth = minOf(width, if (placeLeft) leftSpace else rightSpace)
        val fittedX = if (placeLeft) bx - gap - fittedWidth else bx + valid.size + gap
        val fittedY = (by + valid.size / 2 - height / 2)
            .coerceIn(top, (bottom - height).coerceAtLeast(top))
        return Result(
            fittedX.coerceIn(left, (right - fittedWidth).coerceAtLeast(left)),
            fittedY,
            fittedWidth, height,
            if (placeLeft) Side.LEFT_OF_BUBBLE else Side.RIGHT_OF_BUBBLE,
        )
    }
}
