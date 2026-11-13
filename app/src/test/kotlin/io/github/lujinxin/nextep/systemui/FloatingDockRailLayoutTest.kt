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
}
