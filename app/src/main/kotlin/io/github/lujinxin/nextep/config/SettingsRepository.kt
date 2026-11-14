package io.github.lujinxin.nextep.config

import android.content.Context

class SettingsRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("nextep_settings", Context.MODE_PRIVATE)

    data class TopBarSettings(
        val title: String,
        val contentMode: String,
        val showSeconds: Boolean,
        val frostStrength: Int,
        val manualAppOrder: Boolean,
        val appComponents: List<String>,
        val textScroll: Boolean,
        val textFontFamily: String,
        val textSizeSp: Int,
        val textBold: Boolean,
        val statusBarGestureEnabled: Boolean,
        val floatingBubbleEnabled: Boolean,
        val floatingPadModeEnabled: Boolean,
        val floatingIndependentDisplayEnabled: Boolean,
        val playbackCompatEnabled: Boolean,
        val playbackCompatAutoResume: Boolean,
        val playbackCompatLockAudio: Boolean,
        val playbackCompatPackages: Set<String>,
        val floatingInteractionMode: String,
        val floatingDockOrientation: String,
        val floatingDockSizePercent: Int,
        val slotBackgroundSwipeEnabled: Boolean,
        val autoMinimizeMainOnTopAppSwitch: Boolean,
        val navigationButtonsEnabled: Boolean,
        val swapAnimationDurationMs: Int,
        val fourFingerGestureEnabled: Boolean = true,
        val fourFingerGestureBlockGames: Boolean = true,
        val fourFingerGestureBlacklist: Set<String> = emptySet(),
        val blockSystemDragShareEnabled: Boolean = WorkspaceConfigContract.DEFAULT_BLOCK_SYSTEM_DRAG_SHARE_ENABLED,
        val returnAfterSharingEnabled: Boolean = WorkspaceConfigContract.DEFAULT_RETURN_AFTER_SHARING_ENABLED,
    )

    fun topBarSettings(): TopBarSettings = TopBarSettings(
        title = preferences.getString(KEY_TOP_TITLE, DEFAULT_TOP_TITLE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_TOP_TITLE,
        contentMode = TopContentMode.resolve(preferences.getString("top_content_mode", null), preferences.getString(KEY_TOP_TITLE, "").orEmpty()),
        showSeconds = preferences.getBoolean("top_show_seconds", false),
        frostStrength = preferences.getInt("frost_strength", 50).coerceIn(0, 100),
        manualAppOrder = preferences.getBoolean(KEY_MANUAL_APP_ORDER, false),
        appComponents = preferences.getString(KEY_TOP_APPS, null)
            ?.split(COMPONENT_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty(),
        textScroll = preferences.getBoolean("top_text_scroll", false),
        textFontFamily = TopTextStyle.resolveFamily(preferences.getString("top_text_font", null)),
        textSizeSp = preferences.getInt("top_text_size", TopTextStyle.DEFAULT_SIZE_SP)
            .coerceIn(TopTextStyle.MIN_SIZE_SP, TopTextStyle.MAX_SIZE_SP),
        textBold = preferences.getBoolean("top_text_bold", false),
        statusBarGestureEnabled = preferences.getBoolean("status_bar_gesture_enabled", true),
        floatingBubbleEnabled = preferences.getBoolean("floating_bubble_enabled", false),
        floatingPadModeEnabled = preferences.getBoolean(KEY_FLOATING_PAD_MODE_ENABLED, false),
        floatingIndependentDisplayEnabled = preferences.getBoolean(KEY_FLOATING_INDEPENDENT_DISPLAY_ENABLED, false),
        playbackCompatEnabled = preferences.getBoolean(KEY_PLAYBACK_COMPAT_ENABLED, false),
        playbackCompatAutoResume = preferences.getBoolean(KEY_PLAYBACK_COMPAT_AUTO_RESUME, false),
        playbackCompatLockAudio = preferences.getBoolean(KEY_PLAYBACK_COMPAT_LOCK_AUDIO, false),
        playbackCompatPackages = preferences.getStringSet(KEY_PLAYBACK_COMPAT_PACKAGES, emptySet())
            .orEmpty().toSet(),
        floatingInteractionMode = WorkspaceConfigContract.normalizeFloatingInteractionMode(
            preferences.getString(KEY_FLOATING_INTERACTION_MODE,
                WorkspaceConfigContract.DEFAULT_FLOATING_INTERACTION_MODE),
        ),
        floatingDockOrientation = WorkspaceConfigContract.normalizeFloatingDockOrientation(
            preferences.getString(KEY_FLOATING_DOCK_ORIENTATION,
                WorkspaceConfigContract.DEFAULT_FLOATING_DOCK_ORIENTATION),
        ),
        floatingDockSizePercent = WorkspaceConfigContract.normalizeFloatingDockSizePercent(
            preferences.getInt(KEY_FLOATING_DOCK_SIZE_PERCENT,
                WorkspaceConfigContract.DEFAULT_FLOATING_DOCK_SIZE_PERCENT),
        ),
        slotBackgroundSwipeEnabled = preferences.getBoolean("slot_background_swipe_enabled", true),
        autoMinimizeMainOnTopAppSwitch = preferences.getBoolean("auto_minimize_main_on_top_app_switch", false),
        navigationButtonsEnabled = preferences.getBoolean("navigation_buttons_enabled", false),
        swapAnimationDurationMs = WorkspaceConfigContract.normalizeSwapAnimationDurationMs(
            preferences.getInt(KEY_SWAP_ANIMATION_DURATION_MS, WorkspaceConfigContract.DEFAULT_SWAP_ANIMATION_DURATION_MS),
        ),
        fourFingerGestureEnabled = preferences.getBoolean(KEY_FOUR_FINGER_GESTURE_ENABLED, true),
        fourFingerGestureBlockGames = preferences.getBoolean(KEY_FOUR_FINGER_GESTURE_BLOCK_GAMES, true),
        fourFingerGestureBlacklist = preferences.getStringSet(KEY_FOUR_FINGER_GESTURE_BLACKLIST, emptySet())
            .orEmpty().toSet(),
        blockSystemDragShareEnabled = preferences.getBoolean(KEY_BLOCK_SYSTEM_DRAG_SHARE_ENABLED,
            WorkspaceConfigContract.DEFAULT_BLOCK_SYSTEM_DRAG_SHARE_ENABLED),
        returnAfterSharingEnabled = preferences.getBoolean(KEY_RETURN_AFTER_SHARING_ENABLED,
            WorkspaceConfigContract.DEFAULT_RETURN_AFTER_SHARING_ENABLED),
    )

    fun setFourFingerGestureEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FOUR_FINGER_GESTURE_ENABLED, enabled).apply()
        notifyTopContentChanged()
    }

    fun setFourFingerGestureBlockGames(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FOUR_FINGER_GESTURE_BLOCK_GAMES, enabled).apply()
        notifyTopContentChanged()
    }

    fun setFourFingerGestureBlacklist(packages: Set<String>) {
        preferences.edit().putStringSet(KEY_FOUR_FINGER_GESTURE_BLACKLIST,
            packages.map(String::trim).filter(String::isNotEmpty).toSet()).apply()
        notifyTopContentChanged()
    }

    fun setSwapAnimationDurationMs(durationMs: Int) {
        preferences.edit().putInt(KEY_SWAP_ANIMATION_DURATION_MS,
            WorkspaceConfigContract.normalizeSwapAnimationDurationMs(durationMs)).apply()
        notifyTopContentChanged()
    }

    fun setNavigationButtonsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("navigation_buttons_enabled", enabled).apply()
        notifyTopContentChanged()
    }

    fun setStatusBarGestureEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("status_bar_gesture_enabled", enabled).apply()
        notifyTopContentChanged()
    }

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("floating_bubble_enabled", enabled).apply()
        notifyTopContentChanged()
    }

    fun setFloatingIndependentDisplayEnabled(enabled: Boolean) {
        if (preferences.edit().putBoolean(KEY_FLOATING_INDEPENDENT_DISPLAY_ENABLED, enabled).commit()) {
            notifyTopContentChanged()
        }
    }

    fun setFloatingPadModeEnabled(enabled: Boolean) {
        // Commit before notifying: the verified cross-process config reader
        // may run immediately when Android delivers the broadcast.
        if (preferences.edit().putBoolean(KEY_FLOATING_PAD_MODE_ENABLED, enabled).commit()) {
            notifyTopContentChanged()
        } else {
            io.github.lujinxin.nextep.logging.NeXtepLog.warn(
                "floating_pad", "Failed to save Pad multi-task preference")
        }
    }

    /** Opt-in. Nothing is controlled or resumed until the user selects apps. */
    fun setPlaybackCompatEnabled(enabled: Boolean) =
        savePlaybackCompat { it.putBoolean(KEY_PLAYBACK_COMPAT_ENABLED, enabled) }

    fun setPlaybackCompatAutoResume(enabled: Boolean) =
        savePlaybackCompat { it.putBoolean(KEY_PLAYBACK_COMPAT_AUTO_RESUME, enabled) }

    fun setPlaybackCompatLockAudio(enabled: Boolean) =
        savePlaybackCompat { it.putBoolean(KEY_PLAYBACK_COMPAT_LOCK_AUDIO, enabled) }

    fun setPlaybackCompatPackages(packages: Set<String>) =
        savePlaybackCompat {
            it.putStringSet(KEY_PLAYBACK_COMPAT_PACKAGES,
                packages.filter { name ->
                    name.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
                }.take(60).toSet())
        }

    private inline fun savePlaybackCompat(block: (android.content.SharedPreferences.Editor) -> Unit) {
        val editor = preferences.edit()
        block(editor)
        if (editor.commit()) notifyTopContentChanged()
        else io.github.lujinxin.nextep.logging.NeXtepLog.warn(
            "playback_compat", "Could not persist selected-player preferences")
    }

    /** Live selection affects only the floating dock; ordinary workspace is unchanged. */
    fun setFloatingInteractionMode(mode: String) {
        val normalized = WorkspaceConfigContract.normalizeFloatingInteractionMode(mode)
        if (preferences.edit().putString(KEY_FLOATING_INTERACTION_MODE, normalized).commit()) {
            notifyTopContentChanged()
        } else {
            io.github.lujinxin.nextep.logging.NeXtepLog.warn(
                "floating_mode", "Could not save floating interaction mode")
        }
    }

    /** One canonical source for the app's settings and the injected SystemUI dock. */
    fun setFloatingDockLayout(orientation: String, sizePercent: Int) {
        val normalizedOrientation = WorkspaceConfigContract.normalizeFloatingDockOrientation(orientation)
        val normalizedSize = WorkspaceConfigContract.normalizeFloatingDockSizePercent(sizePercent)
        // Commit before broadcasting: SystemUI reads settings from a
        // different process and must not observe the previous value.
        val saved = preferences.edit()
            .putString(KEY_FLOATING_DOCK_ORIENTATION, normalizedOrientation)
            .putInt(KEY_FLOATING_DOCK_SIZE_PERCENT, normalizedSize)
            .commit()
        if (saved) notifyTopContentChanged()
        else io.github.lujinxin.nextep.logging.NeXtepLog.warn(
            "floating_settings", "Could not persist layout preference changes",
        )
    }

    fun resetFloatingDockLayout() {
        setFloatingDockLayout(WorkspaceConfigContract.DEFAULT_FLOATING_DOCK_ORIENTATION,
            WorkspaceConfigContract.DEFAULT_FLOATING_DOCK_SIZE_PERCENT)
    }

    fun setAutoMinimizeMainOnTopAppSwitch(enabled: Boolean) {
        preferences.edit().putBoolean("auto_minimize_main_on_top_app_switch", enabled).apply()
        notifyTopContentChanged()
    }

    fun setSlotBackgroundSwipeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("slot_background_swipe_enabled", enabled).apply()
        notifyTopContentChanged()
    }

    fun setBlockSystemDragShareEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_BLOCK_SYSTEM_DRAG_SHARE_ENABLED, enabled).apply()
        notifyTopContentChanged()
    }

    fun setReturnAfterSharingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RETURN_AFTER_SHARING_ENABLED, enabled).apply()
        notifyTopContentChanged()
    }

    private fun notifyTopContentChanged() {
        context.sendBroadcast(android.content.Intent(WorkspaceConfigContract.ACTION_CHANGED)
            .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            .setPackage("com.android.systemui"))
    }

    fun setTopTitle(title: String) {
        preferences.edit()
            .putString(KEY_TOP_TITLE, title.trim().ifEmpty { DEFAULT_TOP_TITLE })
            .putString("top_content_mode", if (title.isBlank()) TopContentMode.ICON else TopContentMode.TEXT)
            .apply()
        notifyTopContentChanged()
    }

    fun topTitleDraft(): String =
        preferences.getString("top_title_draft", preferences.getString(KEY_TOP_TITLE, "")).orEmpty()

    fun setTopTitleDraft(value: String) {
        preferences.edit().putString("top_title_draft", value).apply()
    }

    fun setTopContentMode(mode: String) {
        preferences.edit().putString("top_content_mode", TopContentMode.resolve(mode, "")).apply()
        notifyTopContentChanged()
    }

    fun setTopShowSeconds(enabled: Boolean) {
        preferences.edit().putBoolean("top_show_seconds", enabled).apply()
        notifyTopContentChanged()
    }

    fun setTopTextScroll(enabled: Boolean) {
        preferences.edit().putBoolean("top_text_scroll", enabled).apply()
        notifyTopContentChanged()
    }

    fun setTopTextFontFamily(family: String) {
        preferences.edit().putString("top_text_font", TopTextStyle.resolveFamily(family)).apply()
        notifyTopContentChanged()
    }

    fun setTopTextSize(sizeSp: Int) {
        preferences.edit().putInt("top_text_size", sizeSp.coerceIn(
            TopTextStyle.MIN_SIZE_SP, TopTextStyle.MAX_SIZE_SP,
        )).apply()
        notifyTopContentChanged()
    }

    fun setTopTextBold(enabled: Boolean) {
        preferences.edit().putBoolean("top_text_bold", enabled).apply()
        notifyTopContentChanged()
    }

    fun setFrostStrength(value: Int) {
        preferences.edit().putInt("frost_strength", value.coerceIn(0, 100)).apply()
        notifyTopContentChanged()
    }

    fun setTopApps(components: List<String>) {
        preferences.edit()
            .putString(
                KEY_TOP_APPS,
                components.distinct().take(MAX_TOP_APPS).joinToString(COMPONENT_SEPARATOR),
            )
            .apply()
        notifyTopContentChanged()
    }

    fun setManualAppOrder(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_MANUAL_APP_ORDER, enabled).apply()
        notifyTopContentChanged()
    }

    private companion object {
        const val KEY_TOP_TITLE = "top_title"
        const val KEY_FLOATING_PAD_MODE_ENABLED = "floating_pad_mode_enabled"
        const val KEY_FLOATING_INDEPENDENT_DISPLAY_ENABLED = "floating_independent_display_enabled"
        const val KEY_PLAYBACK_COMPAT_ENABLED = "playback_compat_enabled"
        const val KEY_PLAYBACK_COMPAT_AUTO_RESUME = "playback_compat_auto_resume"
        const val KEY_PLAYBACK_COMPAT_LOCK_AUDIO = "playback_compat_lock_audio"
        const val KEY_PLAYBACK_COMPAT_PACKAGES = "playback_compat_packages"
        const val KEY_FLOATING_INTERACTION_MODE = "floating_interaction_mode"
        const val KEY_FLOATING_DOCK_ORIENTATION = "floating_dock_orientation"
        const val KEY_FLOATING_DOCK_SIZE_PERCENT = "floating_dock_size_percent"
        const val KEY_TOP_APPS = "top_apps"
        const val KEY_MANUAL_APP_ORDER = "manual_app_order"
        const val KEY_SWAP_ANIMATION_DURATION_MS = "window_swap_animation_duration_ms"
        const val KEY_FOUR_FINGER_GESTURE_ENABLED = "four_finger_gesture_enabled"
        const val KEY_FOUR_FINGER_GESTURE_BLOCK_GAMES = "four_finger_gesture_block_games"
        const val KEY_FOUR_FINGER_GESTURE_BLACKLIST = "four_finger_gesture_blacklist"
        const val KEY_BLOCK_SYSTEM_DRAG_SHARE_ENABLED = "block_system_drag_share_enabled"
        const val KEY_RETURN_AFTER_SHARING_ENABLED = "return_after_sharing_enabled"
        const val DEFAULT_TOP_TITLE = ""
        const val COMPONENT_SEPARATOR = "\n"
        const val MAX_TOP_APPS = 36
    }
}
