package io.github.lujinxin.nextep.systemui

import kotlin.math.roundToInt

/**
 * The optional floating dock reuses the original sidebar's slot dimensions,
 * then applies ONE uniform scale factor to each width and height. The actual
 * VirtualDisplay canvas is unchanged; only the TextureView viewport is resized.
 * Neither orientation nor size ever stretches a card to fill its rail.
 */
internal object FloatingDockRailLayout {
    enum class Orientation { HORIZONTAL, VERTICAL }

    const val MIN_SCALE_PERCENT = 60
    const val MAX_SCALE_PERCENT = 140
    const val SCALE_STEP_PERCENT = 10

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
        val orientation: Orientation,
        val scalePercent: Int,
    )

    fun normalizedScale(percent: Int): Int =
        (percent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT) / SCALE_STEP_PERCENT) *
            SCALE_STEP_PERCENT

    fun calculate(
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        slotSizes: List<Pair<Int, Int>>,
        orientation: Orientation = Orientation.HORIZONTAL,
        scalePercent: Int = 100,
    ): Plan {
        require(screenWidth > 1 && screenHeight > 1)
        require(density.isFinite() && density > 0f)
        require(slotSizes.isNotEmpty() && slotSizes.all { it.first > 0 && it.second > 0 })
        fun dp(value: Int) = (value * density).roundToInt().coerceAtLeast(1)
        // No decorative full-size backing panel. Reserve only the live
        // card strip and a two-row (vertical) or one-row (horizontal) toolbar.
        val pad = dp(4)
        val gap = dp(6)
        val button = dp(30)
        val controlGap = dp(4)
        val header = if (orientation == Orientation.VERTICAL)
            button * 2 + dp(12) else button + dp(8)
        val toolbarWidth = if (orientation == Orientation.VERTICAL)
            button * 2 + controlGap + pad * 2
        else button * 4 + controlGap * 3 + pad * 2
        val percent = normalizedScale(scalePercent)
        // A single proportional factor locks the source aspect ratio. Integer
        // pixels can vary by at most a rounding pixel, never a freeform resize.
        val scaled = slotSizes.map { (w, h) ->
            ((w.toLong() * percent + 50) / 100).toInt().coerceAtLeast(1) to
                ((h.toLong() * percent + 50) / 100).toInt().coerceAtLeast(1)
        }
        val horizontal = orientation == Orientation.HORIZONTAL
        val railWidth = if (horizontal) {
            pad * 2 + scaled.sumOf { it.first } + gap * (scaled.size - 1)
        } else pad * 2 + scaled.maxOf { it.first }
        val railHeight = if (horizontal) {
            pad * 2 + scaled.maxOf { it.second }
        } else pad * 2 + scaled.sumOf { it.second } + gap * (scaled.size - 1)
        val availableWidth = (screenWidth - dp(16)).coerceAtLeast(1)
        val availableHeight = (screenHeight - dp(16)).coerceAtLeast(1)
        // Never force the previous 240dp / 230dp backing rectangle:
        // in portrait mode this left hundreds of empty pixels beside a
        // single narrow live preview. Scroll the content when space is tight.
        val panelWidth = minOf(maxOf(railWidth, toolbarWidth), availableWidth)
        val panelHeight = minOf(header + railHeight, availableHeight)
        var x = pad
        var y = pad
        val cells = scaled.map { (w, h) ->
            if (horizontal) {
                Cell(x, pad + (railHeight - 2 * pad - h) / 2, w, h)
                    .also { x += w + gap }
            } else {
                Cell(pad + (railWidth - 2 * pad - w) / 2, y, w, h)
                    .also { y += h + gap }
            }
        }
        return Plan(
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            headerHeight = header,
            railContentWidth = railWidth,
            railHeight = railHeight,
            padding = pad,
            gap = gap,
            cells = cells,
            orientation = orientation,
            scalePercent = percent,
        )
    }
}
