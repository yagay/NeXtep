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
    @Test fun portraitNeverTouchesStatusBarOrThreeButtonNavigationArea() {
        val safe = FloatingDockPlacement.Insets(top = 110, bottom = 160)
        val bubble = FloatingDockPlacement.Anchor(900, 30, 140)
        for (requestedHeight in listOf(300, 1500, 3000)) {
            val dock = FloatingDockPlacement.calculate(
                1080, 2400, 650, requestedHeight, bubble, 3f, safe,
            )
            assertTrue("Top: ${dock.y}", dock.y >= safe.top)
            assertTrue("Bottom: ${dock.y + dock.height}", dock.y + dock.height <= 2400 - safe.bottom)
            assertTrue(dock.x >= safe.left)
            assertTrue(dock.x + dock.width <= 1080 - safe.right)
        }
    }

    @Test fun horizontalAndVerticalOverlaysShareTheSameProtectedRectangle() {
        val safe = FloatingDockPlacement.Insets(top = 90, bottom = 130, right = 20)
        for (dimensions in listOf(1000 to 300, 220 to 1600)) {
            val (width, height) = dimensions
            val dock = FloatingDockPlacement.calculate(
                1080, 2350, width, height,
                FloatingDockPlacement.Anchor(930, 2200, 130), 3f, safe,
            )
            assertTrue(dock.y >= safe.top)
            assertTrue(dock.y + dock.height <= 2350 - safe.bottom)
            assertTrue(dock.x + dock.width <= 1080 - safe.right)
        }
    }

    @Test fun landscapeSideNavigationAndCutoutCannotBeCovered() {
        val safe = FloatingDockPlacement.Insets(left = 125, top = 52, right = 170, bottom = 36)
        val dock = FloatingDockPlacement.calculate(
            2400, 1080, 1900, 940,
            FloatingDockPlacement.Anchor(2100, 100, 140), 3f, safe,
        )
        assertTrue(dock.x >= safe.left)
        assertTrue(dock.x + dock.width <= 2400 - safe.right)
        assertTrue(dock.y >= safe.top)
        assertTrue(dock.y + dock.height <= 1080 - safe.bottom)
    }

    @Test fun noBubbleFallbackAlsoRespectsBars() {
        val safe = FloatingDockPlacement.Insets(top = 130, bottom = 150)
        val dock = FloatingDockPlacement.calculate(1080, 2200, 800, 2400, null, 3f, safe)
        assertTrue(dock.y >= 130)
        assertTrue(dock.y + dock.height <= 2050)
    }

}
