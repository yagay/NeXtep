package io.github.lujinxin.nextep.systemui

import kotlin.math.roundToInt

/**
 * Position an existing original-size horizontal dock beside an app-owned
 * floating bubble. Only the rail viewport is clipped when space is tight;
 * the four original VirtualDisplay card sizes never change.
 */
internal object FloatingDockPlacement {
    data class Anchor(val x: Int, val y: Int, val size: Int)
    enum class Side { LEFT_OF_BUBBLE, RIGHT_OF_BUBBLE, FALLBACK }
    data class Result(val x: Int, val y: Int, val width: Int, val height: Int, val side: Side)

    fun calculate(
        screenWidth: Int,
        screenHeight: Int,
        preferredWidth: Int,
        preferredHeight: Int,
        anchor: Anchor?,
        density: Float,
    ): Result {
        require(screenWidth > 1 && screenHeight > 1)
        require(preferredWidth > 0 && preferredHeight > 0)
        require(density.isFinite() && density > 0f)
        fun dp(value: Int) = (value * density).roundToInt().coerceAtLeast(1)
        val margin = dp(8)
        val gap = dp(8)
        val height = minOf(preferredHeight, (screenHeight - margin * 2).coerceAtLeast(1))
        val fullWidth = minOf(preferredWidth, (screenWidth - margin * 2).coerceAtLeast(1))
        val valid = anchor?.takeIf { it.size > 0 && it.size < screenWidth &&
            it.x >= -it.size && it.x < screenWidth &&
            it.y >= -it.size && it.y < screenHeight
        }
        if (valid == null) {
            return Result(
                (screenWidth - fullWidth - margin).coerceAtLeast(0),
                ((screenHeight - height) / 2).coerceAtLeast(0),
                fullWidth, height, Side.FALLBACK,
            )
        }
        val bx = valid.x.coerceIn(0, (screenWidth - valid.size).coerceAtLeast(0))
        val by = valid.y.coerceIn(0, (screenHeight - valid.size).coerceAtLeast(0))
        val leftSpace = (bx - gap - margin).coerceAtLeast(0)
        val rightSpace = (screenWidth - bx - valid.size - gap - margin).coerceAtLeast(0)
        // The bubble normally snaps to a screen edge. If the device reports a
        // temporarily unsnapped coordinate, choose whichever side has more room.
        val putOnLeft = if (bx + valid.size / 2 >= screenWidth / 2) {
            leftSpace > 0 || rightSpace == 0
        } else {
            !(rightSpace > 0 || leftSpace == 0)
        }
        val freeWidth = if (putOnLeft) leftSpace else rightSpace
        if (freeWidth <= 0) {
            return Result(
                (screenWidth - fullWidth - margin).coerceAtLeast(0),
                ((screenHeight - height) / 2).coerceAtLeast(0),
                fullWidth, height, Side.FALLBACK,
            )
        }
        val width = minOf(fullWidth, freeWidth)
        val x = if (putOnLeft) bx - gap - width else bx + valid.size + gap
        val topLimit = (screenHeight - height - margin).coerceAtLeast(margin)
        val y = (by + valid.size / 2 - height / 2).coerceIn(margin, topLimit)
        return Result(
            x.coerceIn(0, (screenWidth - width).coerceAtLeast(0)),
            y, width, height,
            if (putOnLeft) Side.LEFT_OF_BUBBLE else Side.RIGHT_OF_BUBBLE,
        )
    }
}
