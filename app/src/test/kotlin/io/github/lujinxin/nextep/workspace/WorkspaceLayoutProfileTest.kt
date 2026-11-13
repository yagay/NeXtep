package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceLayoutProfileTest {
    private fun legacyEncoding(profile: WorkspaceLayoutProfile, version: String): String {
        require(version in setOf("2", "3", "4", "5"))
        val fields = profile.encode().split(',').take(20).toMutableList()
        fields[0] = version
        val count = 3
        val height = (profile.geometry.sidebarLogicalHeight - profile.gapPixels * (count - 1))
            .coerceAtLeast(count)
        val base = height / count
        val remainder = height % count
        repeat(count) { index ->
            fields.add((profile.geometry.sidebarWidth - profile.gapPixels).toString())
            fields.add((base + if (index < remainder) 1 else 0).toString())
            fields.add((index * (base + profile.gapPixels) + minOf(index, remainder)).toString())
        }
        if (version == "5") fields.add(profile.geometry.displayRotation.toString())
        return fields.joinToString(",")
    }

    @Test
    fun fourSlotsShareOneRailWithoutOverlap() {
        val profile = WorkspaceLayoutProfile.create(
            1080, 2354, 480, WorkspaceNavigationInsets(bottom = 132),
        )
        assertEquals(4, profile.slots.size)
        profile.slots.zipWithNext().forEach { (a, b) ->
            assertEquals(a.top + a.height + profile.gapPixels, b.top)
        }
        assertEquals(profile.geometry.sidebarLogicalHeight,
            profile.slots.last().top + profile.slots.last().height)
        assertEquals("6", profile.encode().substringBefore(','))
        assertEquals(33, profile.encode().split(',').size)
    }

    @Test
    fun legacyThreeSlotV5MigratesToFourSlotLayoutAndPreservesRotation() {
        val recorded = WorkspaceLayoutProfile(
            WorkspaceGeometry(2354, 1080, 624, 286, SidebarSide.RIGHT,
                WorkspaceNavigationInsets(left = 132), displayRotation = 3),
            480, 3,
        )
        val decoded = checkNotNull(WorkspaceLayoutProfile.decode(legacyEncoding(recorded, "5")))
        assertEquals(4, decoded.slots.size)
        assertEquals(3, decoded.geometry.displayRotation)
        assertEquals(recorded.geometry.navigationInsets, decoded.geometry.navigationInsets)
        assertEquals("6", decoded.encode().substringBefore(','))
        assertEquals(decoded, WorkspaceLayoutProfile.decode(decoded.encode()))
    }

    @Test
    fun everySlotFitsItsRecordedBoundsInBothNavigationModesAndRotations() {
        for ((width, height, densityDpi) in listOf(
            Triple(1080, 2354, 480), Triple(2354, 1080, 480),
            Triple(1600, 2560, 320), Triple(2560, 1600, 320),
        )) {
            for (insets in listOf(WorkspaceNavigationInsets(),
                if (width < height) WorkspaceNavigationInsets(bottom = 132)
                else WorkspaceNavigationInsets(right = 132))) {
                val profile = WorkspaceLayoutProfile.create(width, height, densityDpi, insets)
                for (side in SidebarSide.entries) {
                    val positioned = profile.copy(geometry = profile.geometry(side))
                    positioned.slots.forEachIndexed { index, slot ->
                        val transform = positioned.previewTransform(slot.width, slot.height)
                        val drawnWidth = slot.width * transform.scaleX
                        val drawnHeight = slot.height * transform.scaleY
                        val message = "$width x $height navigation=$insets side=$side slot=$index"
                        // Whole-buffer fitting may leave at most integer-pixel rounding.
                        // Keep the entire app visible even when the control bar
                        // minimum makes a four-slot rail slightly letterboxed.
                        assertTrue(message, drawnWidth <= slot.width + 1.5f)
                        assertTrue(message, drawnHeight <= slot.height + 1.5f)
                        assertTrue(message, kotlin.math.abs(slot.width - drawnWidth) <= 1.5f ||
                            kotlin.math.abs(slot.height - drawnHeight) <= 1.5f)
                        assertEquals(message, slot.width / positioned.canvas.width.toFloat() * transform.scaleX,
                            slot.height / positioned.canvas.height.toFloat() * transform.scaleY, .000001f)
                    }
                    assertEquals(positioned.geometry.sidebarLogicalHeight,
                        positioned.slots.last().top + positioned.slots.last().height)
                    assertEquals(positioned.geometry.sidebarWidth,
                        positioned.slots.first().width + positioned.gapPixels)
                }
            }
        }
    }

    @Test
    fun savedProfileRoundTripsBothNavigationModesAndRotations() {
        for ((width, height) in listOf(1080 to 2354, 2354 to 1080)) {
            for (insets in listOf(WorkspaceNavigationInsets(),
                if (width < height) WorkspaceNavigationInsets(bottom = 132)
                else WorkspaceNavigationInsets(right = 132))) {
                for (rotation in if (width > height) listOf(1, 3) else listOf(0)) {
                    val profile = WorkspaceLayoutProfile.create(width, height, 480, insets, rotation)
                    val restored = checkNotNull(WorkspaceLayoutProfile.decode(profile.encode()))
                    assertEquals(profile, restored)
                    assertEquals(profile.slots, restored.slots)
                    assertEquals(profile.canvas, restored.canvas)
                    assertEquals(profile.source, restored.source)
                    assertEquals(profile.geometry(SidebarSide.LEFT), restored.geometry(SidebarSide.LEFT))
                    assertEquals(profile.geometry(SidebarSide.RIGHT), restored.geometry(SidebarSide.RIGHT))
                }
            }
        }
    }

    @Test
    fun actualPixelsUseTheSameScaleOnBothAxesAndFitInsideEverySlot() {
        for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132))) {
            val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, insets)
            profile.slots.forEach { slot ->
                val transform = profile.previewTransform(slot.width, slot.height)
                val sx = slot.width * transform.scaleX / profile.canvas.width
                val sy = slot.height * transform.scaleY / profile.canvas.height
                assertEquals(sx, sy, .000001f)
                val source = profile.source
                val left = source.left * sx + transform.x
                val right = source.right * sx + transform.x
                val top = source.top * sy + transform.y
                val bottom = source.bottom * sy + transform.y
                assertTrue(left >= -.01f && top >= -.01f)
                assertTrue(right <= slot.width + .01f && bottom <= slot.height + .01f)
            }
        }
    }

    @Test
    fun canvasUsesTheRecordedUsableSizeAndDoesNotDependOnTheSlotOrSide() {
        val full = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets())
        val buttons = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets(bottom = 132))
        assertEquals(1080, buttons.canvas.width)
        assertEquals(2222, buttons.canvas.height)
        assertEquals(2354, full.source.height)
        assertEquals(2222, buttons.source.height)
        assertEquals(2222, buttons.geometry.availableHeight)
        assertNotEquals(full.slots, buttons.slots)
        assertEquals(buttons.canvas, buttons.copy(geometry = buttons.geometry(SidebarSide.LEFT)).canvas)
    }

    @Test
    fun previewKeepsTheWholeAppIncludingItsBottomControlsInButtonMode() {
        val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets(bottom = 132))
        // A virtual display has no physical navigation bar. Cropping 132 pixels
        // off its app would remove the app's own bottom controls.
        assertEquals(2222, profile.geometry.availableHeight)
        assertEquals(0, profile.source.top)
        assertEquals(profile.canvas.height, profile.source.bottom)
    }

    @Test
    fun oldFullCanvasProfileMigratesUsingItsRecordedNavigationInsets() {
        val buttons = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets(bottom = 132))
        val old = legacyEncoding(buttons, "2").split(',').toMutableList()
        old[15] = "2354"
        old[19] = "2354"
        val migrated = checkNotNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
        assertEquals(buttons, migrated)
        assertEquals(2222, migrated.canvas.height)
        assertEquals("6", migrated.encode().substringBefore(','))
    }

    @Test
    fun oldRecordedRailDimensionsAreMigratedWithoutChangingTheNavigationMode() {
        for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132))) {
            val oldGeometry = WorkspaceGeometry(1080, 2354, 624,
                if (insets.bottom == 0) 286 else 303, SidebarSide.RIGHT, insets)
            val recorded = WorkspaceLayoutProfile(oldGeometry, 480, 3)
            for (version in listOf("2", "3")) {
                val old = legacyEncoding(recorded, version).split(',').toMutableList()
                if (version == "2") {
                    old[15] = "2354"
                    old[19] = "2354"
                }
                val migrated = checkNotNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
                assertEquals(insets, migrated.geometry.navigationInsets)
                assertEquals(1080, migrated.canvas.width)
                assertEquals(2354 - insets.bottom, migrated.canvas.height)
                assertEquals(WorkspaceLayoutProfile.create(1080, 2354, 480, insets), migrated)
                assertNotEquals(oldGeometry.topHeight, migrated.geometry.topHeight)
                assertNotEquals(recorded.slots, migrated.slots)
                assertEquals(migrated, WorkspaceLayoutProfile.decode(migrated.encode()))
            }
        }
    }

    @Test
    fun oldLayoutProfileKeepsRecordedDimensionsWhenRotationIsAdded() {
        val profile = WorkspaceLayoutProfile.create(2354, 1080, 480, WorkspaceNavigationInsets(left = 132))
        val old = legacyEncoding(profile, "4").split(',').toMutableList()
        val restored = checkNotNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
        val reversed = restored.copy(geometry = restored.geometry.copy(displayRotation = 1))
        assertEquals(profile, restored)
        assertEquals(restored.canvas, reversed.canvas)
        assertEquals(restored.slots, reversed.slots)
        assertEquals(reversed, WorkspaceLayoutProfile.decode(reversed.encode()))
    }

    @Test
    fun inconsistentSavedDimensionsAreRejected() {
        val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets())
        val parts = profile.encode().split(',').toMutableList()
        parts[14] = "300" // stale native canvas width must not be combined with this profile.
        assertNull(WorkspaceLayoutProfile.decode(parts.joinToString(",")))
        assertNull(WorkspaceLayoutProfile.decode("1080,2354,3,0,0,0,0"))
    }
}
