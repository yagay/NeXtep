package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingDockPlacementTest {
    @Test fun rightEdgeBubblePlacesDockImmediatelyToItsLeft() {
        val bubble = FloatingDockPlacement.Anchor(900, 310, 140)
        val dock = FloatingDockPlacement.calculate(1080, 2340, 1040, 710, bubble, 3f)
        assertEquals(FloatingDockPlacement.Side.LEFT_OF_BUBBLE, dock.side)
        assertEquals(bubble.x - 24, dock.x + dock.width)
        assertTrue(dock.width < 1040) // viewport scrolls; original cards do not shrink
        assertTrue(dock.y >= 0)
    }

    @Test fun leftEdgeBubblePlacesDockToItsRight() {
        val bubble = FloatingDockPlacement.Anchor(20, 1700, 135)
        val dock = FloatingDockPlacement.calculate(1080, 2340, 980, 710, bubble, 3f)
        assertEquals(FloatingDockPlacement.Side.RIGHT_OF_BUBBLE, dock.side)
        assertEquals(bubble.x + bubble.size + 24, dock.x)
        assertTrue(dock.y + dock.height <= 2340)
    }

    @Test fun verticallyFollowsBubbleAndClampsAtScreenEdges() {
        val top = FloatingDockPlacement.calculate(1080, 2200, 800, 800,
            FloatingDockPlacement.Anchor(890, 40, 150), 3f)
        val bottom = FloatingDockPlacement.calculate(1080, 2200, 800, 800,
            FloatingDockPlacement.Anchor(890, 1970, 150), 3f)
        assertTrue(top.y >= 24)
        assertTrue(bottom.y > top.y)
        assertTrue(bottom.y + bottom.height <= 2200 - 24)
    }

    @Test fun noBubbleUsesOriginalCenteredPlacement() {
        val dock = FloatingDockPlacement.calculate(1080, 2340, 850, 700, null, 3f)
        assertEquals(FloatingDockPlacement.Side.FALLBACK, dock.side)
        assertEquals((2340 - 700) / 2, dock.y)
    }

    @Test fun centerBubbleUsesSideWithRoomAndNeverOverlaps() {
        val bubble = FloatingDockPlacement.Anchor(590, 800, 150)
        val dock = FloatingDockPlacement.calculate(1080, 2340, 850, 600, bubble, 3f)
        assertTrue(dock.x + dock.width <= bubble.x ||
            dock.x >= bubble.x + bubble.size)
        assertTrue(dock.x >= 0 && dock.x + dock.width <= 1080)
    }
}
