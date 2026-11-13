package io.github.lujinxin.nextep.floating

/**
 * Keep the user-authorized floating icon while the display is awake, even
 * before USER_PRESENT. Actual drawing is still subject to Android / ColorOS
 * keyguard overlay policy. Never allow task docking behind the keyguard.
 */
internal object FloatingBubbleWakePolicy {
    enum class Event { SCREEN_OFF, SCREEN_ON, USER_PRESENT, SERVICE_START, CONFIGURATION_CHANGED }
    enum class Action { HIDE, SHOW, WAIT_FOR_SCREEN, REMOVE }

    fun decide(event: Event, interactive: Boolean, enabled: Boolean, overlayAllowed: Boolean): Action {
        if (!enabled || !overlayAllowed) return Action.REMOVE
        if (event == Event.SCREEN_OFF) return Action.HIDE
        if (interactive) return Action.SHOW
        return if (event == Event.SCREEN_ON || event == Event.USER_PRESENT)
            Action.WAIT_FOR_SCREEN else Action.HIDE
    }

    fun mayOpenTaskDock(keyguardLocked: Boolean, interactive: Boolean): Boolean =
        interactive && !keyguardLocked
}
