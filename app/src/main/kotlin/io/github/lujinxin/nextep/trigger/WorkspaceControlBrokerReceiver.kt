package io.github.lujinxin.nextep.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import io.github.lujinxin.nextep.logging.NeXtepLog

class WorkspaceControlBrokerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val deadline = Runnable {
            if (finished.compareAndSet(false, true)) {
                NeXtepLog.warn("trigger_broker",
                    "HOST_SET_REQUEST_TIMEOUT action=${intent.action}; releasing broadcast")
                pendingResult.finish()
            }
        }
        // The coordinator can involve multiple ordered cross-process broadcasts.
        // Never hold this receiver indefinitely when a target process is hung.
        handler.postDelayed(deadline, 5_000L)
        val callback: (TriggerCoordinator.RequestResult) -> Unit = { result ->
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacks(deadline)
                NeXtepLog.info("trigger_broker", "SystemUI request completed result=$result")
                pendingResult.finish()
            }
        }
        when (intent.action) {
            TriggerBroadcastContract.ACTION_HOST_TOGGLE_REQUEST ->
                TriggerCoordinator.requestToggle(
                    context,
                    TriggerSource.TOP_RIGHT_STATUS_BAR_GESTURE,
                    callback,
                )
            TriggerBroadcastContract.ACTION_HOST_SET_REQUEST ->
                TriggerCoordinator.requestSet(
                    context,
                    intent.getBooleanExtra(
                        TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE,
                        false,
                    ),
                    TriggerSource.SYSTEM_RECOVERY,
                    onlyIfInactive = intent.getBooleanExtra(TriggerBroadcastContract.EXTRA_ONLY_IF_INACTIVE, false),
                    onResult = callback,
                )
            else -> {
                handler.removeCallbacks(deadline)
                if (finished.compareAndSet(false, true)) pendingResult.finish()
            }
        }
    }
}
