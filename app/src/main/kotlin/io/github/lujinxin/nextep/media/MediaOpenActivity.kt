package io.github.lujinxin.nextep.media

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/**
 * User-initiated notification trampoline.
 *
 * Ask trusted SystemUI to promote the actual live VirtualDisplay Task.
 * Launch a fresh app entry ONLY when SystemUI confirms no matching dock
 * Task remains. Timeouts, busy transitions, and ambiguous clone packages
 * never start a duplicate player instance.
 */
class MediaOpenActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var completed = false
    private val timeout = Runnable {
        complete(false, false, "SYSTEMUI_TIMEOUT", null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(TriggerBroadcastContract.EXTRA_MEDIA_APP_PACKAGE)
            ?.trim().orEmpty()
        if (!pkg.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")) ||
            pkg == packageName) {
            NeXtepLog.warn("media_bridge", "MEDIA_OPEN_INVALID_TARGET")
            finish()
            return
        }
        handler.postDelayed(timeout, 5_500L)
        val request = Intent(TriggerBroadcastContract.ACTION_OPEN_MEDIA_DOCK_APP)
            .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(TriggerBroadcastContract.EXTRA_MEDIA_APP_PACKAGE, pkg)
            .putExtra(TriggerBroadcastContract.EXTRA_FLOATING_REQUEST_ELAPSED,
                SystemClock.elapsedRealtime())
        NeXtepLog.info("media_bridge", "MEDIA_OPEN_ROUTER_REQUEST pkg=$pkg")
        runCatching {
            sendOrderedBroadcast(request, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val success = resultCode == TriggerBroadcastContract.RESULT_HANDLED
                    val details = getResultExtras(false)
                    val notInDock = details?.getBoolean(
                        TriggerBroadcastContract.EXTRA_MEDIA_APP_NOT_IN_DOCK, false) == true
                    val reason = details?.getString(
                        TriggerBroadcastContract.EXTRA_FLOATING_PANEL_ERROR) ?: "NOT_READY"
                    complete(success, notInDock, reason, pkg)
                }
            }, handler, TriggerBroadcastContract.RESULT_NOT_READY, null, Bundle())
        }.onFailure {
            NeXtepLog.warn("media_bridge", "MEDIA_OPEN_REQUEST_FAILED", it)
            complete(false, false, "BROADCAST_FAILED", null)
        }
    }

    private fun complete(success: Boolean, notInDock: Boolean,
        reason: String, targetPackage: String?) {
        if (completed) return
        completed = true
        handler.removeCallbacks(timeout)
        NeXtepLog.info("media_bridge",
            "MEDIA_OPEN_ROUTER_RESULT success=$success noDock=$notInDock reason=$reason")
        if (!success && notInDock && targetPackage != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent == null) {
                NeXtepLog.warn("media_bridge",
                    "MEDIA_OPEN_FALLBACK_UNAVAILABLE pkg=$targetPackage")
            } else {
                runCatching {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(launchIntent)
                    NeXtepLog.info("media_bridge",
                        "MEDIA_OPEN_FALLBACK_LAUNCHED pkg=$targetPackage")
                }.onFailure {
                    NeXtepLog.warn("media_bridge",
                        "MEDIA_OPEN_FALLBACK_FAILED pkg=$targetPackage", it)
                }
            }
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }
}
