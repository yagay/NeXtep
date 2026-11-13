package io.github.lujinxin.nextep.systemui

import kotlin.math.roundToInt

/**
 * Arrange the four live previews in a single horizontal rail using the *exact*
 * slot width and height from the original WorkspaceLayoutProfile.
 *
 * Never shrink the cards to fit the viewport. If the rail exceeds the
 * physical screen, keep every original-size card and use page controls.
 */
internal object FloatingDockRailLayout {
    data class Cell(val x: Int, val y: Int, val width: Int, val height: Int)
    data class Plan(
        val panelWidth: Int,
        val panelHeight: Int,
        val headerHeight: Int,
        val railContentWidth: Int,
        val railHeight: Int,
        val padding: Int,
        val gap: Int,
        val cells: List<Cell>,
    )

    fun calculate(
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        slotSizes: List<Pair<Int, Int>>,
    ): Plan {
        require(screenWidth > 1 && screenHeight > 1)
        require(density.isFinite() && density > 0f)
        require(slotSizes.isNotEmpty() && slotSizes.all { it.first > 0 && it.second > 0 })
        fun dp(value: Int) = (value * density).roundToInt()
        val pad = dp(10)
        val gap = dp(8)
        val header = dp(48)
        val contentHeight = slotSizes.maxOf { it.second }
        val railWidth = pad * 2 + slotSizes.sumOf { it.first } + gap * (slotSizes.size - 1)
        val railHeight = contentHeight + pad * 2
        val availableWidth = (screenWidth - dp(16)).coerceAtLeast(1)
        val availableHeight = (screenHeight - dp(16)).coerceAtLeast(1)
        var x = pad
        val cells = slotSizes.map { (w,h) ->
            Cell(x, pad + (contentHeight - h) / 2, w, h).also { x += w + gap }
        }
        return Plan(
            panelWidth = minOf(railWidth, availableWidth),
            panelHeight = minOf(header + railHeight, availableHeight),
            headerHeight = header,
            railContentWidth = railWidth,
            railHeight = railHeight,
            padding = pad,
            gap = gap,
            cells = cells,
        )
    }
}
