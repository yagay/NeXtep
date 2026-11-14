package io.github.lujinxin.nextep.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingSlotDisplayIdentityTest {
    @Test fun namesAreExclusiveToTheirOwner() {
        for (slot in 0 until WORKSPACE_SLOT_COUNT) {
            assertEquals("NeXtep-slot-$slot",
                FloatingSlotDisplayIdentity.displayName(slot, false))
            assertFalse(FloatingSlotDisplayIdentity.belongsToFloatingDock(
                FloatingSlotDisplayIdentity.displayName(slot, false)))
            assertEquals("NeXtep-floating-slot-$slot",
                FloatingSlotDisplayIdentity.displayName(slot, true))
            assertTrue(FloatingSlotDisplayIdentity.belongsToFloatingDock(
                FloatingSlotDisplayIdentity.displayName(slot, true)))
        }
    }

    @Test fun cannotAccidentallyGuardMainOrForeignDisplays() {
        for (name in listOf("NeXtep-slot-0", "NeXtep-slot-3",
            "NeXtep-floating-slot-4", "NeXtep-floating-slot--1",
            "NeXtep-floating-slot-00", "NeXtep-floating-slot-0-extra",
            "NeXtep-slot-float-0", "OPlus-floating-slot-0")) {
            assertFalse(FloatingSlotDisplayIdentity.belongsToFloatingDock(name))
        }
    }
}
