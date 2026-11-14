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

    @Test fun nativeLauncherCanUseMainScreenUntilItEntersBackground() {
        assertEquals(false, WorkspaceConfigContract.shouldReturnLauncherTaskImmediately(
            WorkspaceConfigContract.FLOATING_INTERACTION_NATIVE, true))
        assertEquals(true, WorkspaceConfigContract.shouldReturnLauncherTaskImmediately(
            WorkspaceConfigContract.FLOATING_INTERACTION_NATIVE, false))
    }

    @Test fun stableModeAlwaysPinsLauncherReusedTask() {
        assertEquals(true, WorkspaceConfigContract.shouldReturnLauncherTaskImmediately(
            WorkspaceConfigContract.FLOATING_INTERACTION_STABLE, true))
        assertEquals(true, WorkspaceConfigContract.shouldReturnLauncherTaskImmediately(
            WorkspaceConfigContract.FLOATING_INTERACTION_STABLE, false))
        assertEquals(true, WorkspaceConfigContract.shouldReturnLauncherTaskImmediately(
            null, true))
    }

    @Test fun explicitNativeAndStableChoicesPersistAsRecognizableValues() {
        assertEquals(WorkspaceConfigContract.FLOATING_INTERACTION_NATIVE,
            WorkspaceConfigContract.normalizeFloatingInteractionMode("native"))
        assertEquals(WorkspaceConfigContract.FLOATING_INTERACTION_STABLE,
            WorkspaceConfigContract.normalizeFloatingInteractionMode("stable"))
    }
}
