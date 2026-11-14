package io.github.lujinxin.nextep.config

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingInteractionModeTest {
    @Test fun defaultsToStableForMissingOrUnrecognizedPreferences() {
        assertEquals(WorkspaceConfigContract.FLOATING_INTERACTION_STABLE,
            WorkspaceConfigContract.normalizeFloatingInteractionMode(null))
        assertEquals(WorkspaceConfigContract.FLOATING_INTERACTION_STABLE,
            WorkspaceConfigContract.normalizeFloatingInteractionMode(""))
        assertEquals(WorkspaceConfigContract.FLOATING_INTERACTION_STABLE,
            WorkspaceConfigContract.normalizeFloatingInteractionMode("invalid"))
    }

    @Test fun explicitNativeAndStableChoicesPersistAsRecognizableValues() {
        assertEquals(WorkspaceConfigContract.FLOATING_INTERACTION_NATIVE,
            WorkspaceConfigContract.normalizeFloatingInteractionMode("native"))
        assertEquals(WorkspaceConfigContract.FLOATING_INTERACTION_STABLE,
            WorkspaceConfigContract.normalizeFloatingInteractionMode("stable"))
    }
}
