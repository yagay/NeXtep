package io.github.lujinxin.nextep.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/** Signature-permission-protected command to the injected SystemUI dock. */
object FloatingPanelCommand {
    sealed interface Result {
        data class Ready(val expanded: Boolean) : Result
        data class Failed(val reason: String) : Result
    }

    fun toggle(context: Context, callback: (Result) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var completed = false
        val timeout = Runnable {
            if (!completed) {
                completed = true
                callback(Result.Failed("SystemUI did not respond. Check LSPosed scope and reboot."))
            }
        }
        handler.postDelayed(timeout, 5_000L)
        fun finish(result: Result) {
            if (completed) return
            completed = true
            handler.removeCallbacks(timeout)
            callback(result)
        }
        val intent = Intent(TriggerBroadcastContract.ACTION_TOGGLE_FLOATING_PANEL)
            .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        runCatching {
            context.sendOrderedBroadcast(
                intent, null, object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val extras = getResultExtras(false)
                        if (resultCode == TriggerBroadcastContract.RESULT_HANDLED) {
                            finish(Result.Ready(extras?.getBoolean(
                                TriggerBroadcastContract.EXTRA_FLOATING_PANEL_ACTIVE, false,
                            ) == true))
                        } else {
                            finish(Result.Failed(extras?.getString(
                                TriggerBroadcastContract.EXTRA_FLOATING_PANEL_ERROR,
                            ) ?: "Floating panel unavailable in SystemUI"))
                        }
                    }
                }, handler, TriggerBroadcastContract.RESULT_NOT_READY, null, Bundle(),
            )
        }.onFailure { error ->
            finish(Result.Failed(error.message ?: "Could not send floating panel request"))
        }
    }
}
