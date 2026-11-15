package io.github.lujinxin.nextep.startup

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import io.github.lujinxin.nextep.config.SettingsRepository
import io.github.lujinxin.nextep.floating.FloatingBubbleController
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.media.MediaBridgeListenerService

/**
 * Restore NeXtep's enabled app-process services after the user is unlocked.
 *
 * The LSPosed hooks in SystemUI/system_server are managed by LSPosed at process
 * boot; launching the module app or restarting system services isn't necessary.
 * Never opens activities, creates a four-slot session, or launches slot apps.
 */
object SystemStartupCoordinator {
    fun onBootEvent(context: Context, action: String?) {
        if (action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_USER_UNLOCKED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )) return
        val app = context.applicationContext ?: context
        val reason = action?.substringAfterLast('.').orEmpty()
        val unlocked = runCatching {
            app.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        }.getOrDefault(false)
        if (!unlocked) {
            NeXtepLog.info("autostart", "DEFER_UNTIL_UNLOCK reason=$reason")
            return
        }

        val preferences = SettingsRepository(app)
        val enabled = preferences.isStartupRestoreEnabled()
        if (!enabled) {
            NeXtepLog.info("autostart", "DISABLED reason=$reason")
            return
        }

        val floatingEnabled = preferences.topBarSettings().floatingBubbleEnabled
        val overlayGranted = runCatching { Settings.canDrawOverlays(app) }
            .onFailure { NeXtepLog.warn("autostart", "OVERLAY_ACCESS_CHECK_FAILED", it) }
            .getOrDefault(false)
        val mediaEnabled = preferences.isMediaBridgeEnabled()
        val component = ComponentName(app, MediaBridgeListenerService::class.java)
        val listenerGranted = runCatching {
            app.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component) == true
        }.onFailure {
            NeXtepLog.warn("autostart", "LISTENER_ACCESS_CHECK_FAILED", it)
        }.getOrDefault(false)

        val plan = SystemStartupPolicy.plan(
            enabled, unlocked, floatingEnabled, overlayGranted,
            mediaEnabled, listenerGranted,
        )
        NeXtepLog.info("autostart",
            "RESTORE_PLAN reason=$reason floating=${plan.resumeFloatingIcon} " +
                "media=${plan.rebindMediaListener} enabledFloating=$floatingEnabled " +
                "overlayGranted=$overlayGranted enabledMedia=$mediaEnabled " +
                "listenerGranted=$listenerGranted")

        if (plan.resumeFloatingIcon) {
            // The existing controller is idempotent; the foreground service
            // checks its own settings and creates no duplicate floating icon.
            runCatching { FloatingBubbleController.sync(app) }
                .onSuccess { accepted ->
                    if (accepted) {
                        NeXtepLog.info("autostart",
                            "FLOATING_RESTORE_REQUESTED reason=$reason")
                    } else {
                        NeXtepLog.warn("autostart",
                            "FLOATING_RESTORE_REJECTED reason=$reason")
                    }
                }.onFailure {
                    NeXtepLog.warn("autostart",
                        "FLOATING_RESTORE_FAILED reason=$reason", it)
                }
        } else if (floatingEnabled && !overlayGranted) {
            NeXtepLog.warn("autostart", "FLOATING_REQUIRES_OVERLAY_PERMISSION")
        }

        if (plan.rebindMediaListener) {
            // NotificationListenerService is system-bound, not a normal
            // foreground service. A single request is safe before connection;
            // Android may decline it, so connection is logged by the listener.
            runCatching { NotificationListenerService.requestRebind(component) }
                .onSuccess {
                    NeXtepLog.info("autostart", "MEDIA_REBIND_REQUESTED reason=$reason")
                }.onFailure {
                    NeXtepLog.warn("autostart", "MEDIA_REBIND_FAILED reason=$reason", it)
                }
        } else if (mediaEnabled && !listenerGranted) {
            NeXtepLog.warn("autostart", "MEDIA_REQUIRES_NOTIFICATION_ACCESS")
        }
    }
}
