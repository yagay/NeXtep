package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingDockRailLayoutTest {
    @Test fun keepsEveryOriginalSlotSizeAndOneRow() {
        val sizes = listOf(242 to 365, 242 to 364, 242 to 364, 242 to 363)
        val plan = FloatingDockRailLayout.calculate(1080, 2400, 3f, sizes)
        assertEquals(4, plan.cells.size)
        plan.cells.forEachIndexed { index, cell ->
            assertEquals(sizes[index].first, cell.width)
            assertEquals(sizes[index].second, cell.height)
            assertTrue(cell.x >= plan.padding)
            assertTrue(cell.y >= plan.padding)
        }
        val row = plan.cells
        for (index in 1 until row.size) {
            assertEquals(row[index - 1].x + row[index - 1].width + plan.gap, row[index].x)
        }
        assertTrue(plan.panelWidth <= 1080)
        assertTrue(plan.railContentWidth > plan.panelWidth)
    }

    @Test fun landscapeStillUsesSingleRowWithUnmodifiedSizes() {
        val sizes = listOf(155 to 145, 155 to 145, 155 to 145, 155 to 144)
        val plan = FloatingDockRailLayout.calculate(2340, 1080, 3f, sizes)
        assertTrue(plan.panelHeight <= 1080)
        assertEquals(sizes.map { it.first }, plan.cells.map { it.width })
        assertEquals(sizes.map { it.second }, plan.cells.map { it.height })
        assertEquals(4, plan.cells.size)
    }
    @Test fun verticalRailKeepsFourCardsInSingleColumn() {
        val sizes = listOf(242 to 365, 242 to 364, 242 to 364, 242 to 363)
        val plan = FloatingDockRailLayout.calculate(1080, 2400, 3f, sizes,
            FloatingDockRailLayout.Orientation.VERTICAL, 100)
        assertEquals(4, plan.cells.size)
        assertEquals(sizes.map { it.first }, plan.cells.map { it.width })
        assertEquals(sizes.map { it.second }, plan.cells.map { it.height })
        for (i in 1 until plan.cells.size) {
            assertEquals(plan.cells[i - 1].y + plan.cells[i - 1].height + plan.gap,
                plan.cells[i].y)
        }
        assertTrue(plan.panelHeight <= 2400)
        assertTrue(plan.railHeight >= plan.panelHeight - plan.headerHeight)
    }

    @Test fun sizePresetsUseOneProportionalFactorForBothAxes() {
        val w = 200
        val h = 400
        for (orientation in FloatingDockRailLayout.Orientation.entries) {
            for (scale in 60..140 step 10) {
                val plan = FloatingDockRailLayout.calculate(1080, 2400, 3f,
                    List(4) { w to h }, orientation, scale)
                plan.cells.forEach {
                    assertEquals(w * scale / 100, it.width)
                    assertEquals(h * scale / 100, it.height)
                    assertEquals(2, it.height / it.width)
                }
            }
        }
    }

    @Test fun sizeClampsToSafePresets() {
        assertEquals(60, FloatingDockRailLayout.normalizedScale(-90))
        assertEquals(140, FloatingDockRailLayout.normalizedScale(155))
        assertEquals(90, FloatingDockRailLayout.normalizedScale(99))
    }
    @Test fun verticalDoesNotKeepGiantEmptyBackingPanel() {
        // A 100px-wide scaled slot should have a compact ~2-button-width
        // column, never a compulsory 240dp opaque block.
        val plan = FloatingDockRailLayout.calculate(
            1080, 2400, 3f, List(4) { 100 to 220 },
            FloatingDockRailLayout.Orientation.VERTICAL, 100,
        )
        assertTrue(plan.panelWidth < 250)
        assertTrue(plan.panelWidth >= plan.cells[0].width)
        assertTrue(plan.panelHeight < 1300)
        assertEquals(4, plan.cells.size)
    }

    @Test fun horizontalMeasuresTheLiveRowWithoutArtificialEmptyHeight() {
        val plan = FloatingDockRailLayout.calculate(
            1500, 2400, 3f, List(4) { 120 to 210 },
            FloatingDockRailLayout.Orientation.HORIZONTAL, 100,
        )
        assertTrue(plan.panelHeight < 290)
        assertTrue(plan.panelWidth >= plan.railContentWidth)
        assertEquals(4, plan.cells.size)
    }

    @Test fun compactColumnKeepsAllFourSlotsAtOriginalAspectRatio() {
        val plan = FloatingDockRailLayout.calculate(
            1080, 2400, 3f, List(4) { 100 to 200 },
            FloatingDockRailLayout.Orientation.VERTICAL, 60,
        )
        plan.cells.forEach { cell ->
            assertEquals(60, cell.width)
            assertEquals(120, cell.height)
        }
    }

}
