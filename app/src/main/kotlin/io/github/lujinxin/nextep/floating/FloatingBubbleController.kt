package io.github.lujinxin.nextep.floating

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.SettingsRepository
import io.github.lujinxin.nextep.logging.NeXtepLog

/** Special-access grant and foreground-service lifecycle live in the app, not SystemUI. */
object FloatingBubbleController {
    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestPermission(activity: Activity) {
        if (hasPermission(activity)) {
            sync(activity)
            return
        }
        Toast.makeText(activity, R.string.floating_permission_guide, Toast.LENGTH_LONG).show()
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")),
            )
        }.recoverCatching {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }.onFailure { error ->
            NeXtepLog.warn("floating_workspace", "Permission settings unavailable", error)
            Toast.makeText(activity, R.string.floating_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Called on settings toggle and on Activity resume after special-access settings. */
    fun sync(context: Context) {
        val service = Intent(context, FloatingBubbleService::class.java)
        val enabled = SettingsRepository(context).topBarSettings().floatingBubbleEnabled
        if (!enabled || !hasPermission(context)) {
            context.stopService(service)
            return
        }
        runCatching { ContextCompat.startForegroundService(context, service) }
            .onFailure { error ->
                NeXtepLog.warn("floating_workspace", "Unable to start overlay service", error)
                if (context is Activity) {
                    Toast.makeText(context, R.string.floating_start_failed, Toast.LENGTH_LONG).show()
                }
            }
    }
}
