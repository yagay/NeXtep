package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/**
 * Fast, passive OOS17 policy event BEFORE the usual keyguard/screen-off
 * callbacks. The user's framework services.jar confirms:
 * PhoneWindowManager.interceptPowerKeyDown(KeyEvent, boolean, boolean).
 *
 * Never consumes the power event, blocks sleep, alters Activity state, or
 * calls task APIs under policy locks. SystemUI validates the exact pinned
 * Task and handles the migration through the existing rollback-capable
 * SlotTaskCoordinator.
 */
internal object PhysicalPowerKeyPrelockHook {
    private const val TAG = "NeXtepPrelock"
    private var lastSentAt = 0L

    fun install(module: XposedModule, loader: ClassLoader) {
        val policy = loader.loadClass("com.android.server.policy.PhoneWindowManager")
        val methods = policy.declaredMethods.filter { method ->
            method.name == "interceptPowerKeyDown" &&
                method.parameterCount == 3 &&
                method.parameterTypes[0] == KeyEvent::class.java &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[2] == Boolean::class.javaPrimitiveType
        }
        if (methods.isEmpty()) {
            Log.w(TAG, "OOS17 early power-key method missing; SCREEN_OFF fallback retained")
            return
        }
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // Before chain.proceed(): the ordinary keyguard / power
                        // path must not be required to start before pre-parking.
                        runCatching {
                            val event = chain.args.firstOrNull() as? KeyEvent
                            if (event != null) dispatch(chain.thisObject, event)
                        }.onFailure {
                            Log.w(TAG, "Early prelock notification failed open", it)
                        }
                        return chain.proceed()
                    }
                })
                Log.i(TAG, "EARLY_POWER_DOWN_HOOK_INSTALLED")
            }.onFailure { Log.w(TAG, "EARLY_POWER_DOWN_INSTALL_FAILED", it) }
        }
    }

    private fun dispatch(policy: Any?, event: KeyEvent) {
        if (event.keyCode != KeyEvent.KEYCODE_POWER ||
            event.action != KeyEvent.ACTION_DOWN ||
            event.repeatCount != 0 || event.isCanceled ||
            event.displayId > 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSentAt in 0L..799L) return
        val context = generateSequence(policy?.javaClass) { it.superclass }
            .mapNotNull { cls -> runCatching {
                cls.getDeclaredField("mContext").apply { isAccessible = true }
                    .get(policy) as? Context
            }.getOrNull() }.firstOrNull() ?: return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (!power.isInteractive) return
        lastSentAt = now
        // Receiver requires android.permission.DEVICE_POWER, available to the
        // real system_server sender. This is not a public app-trigger action.
        context.sendBroadcast(
            Intent(TriggerBroadcastContract.ACTION_POWER_KEY_PRELOCK)
                .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
                .putExtra(TriggerBroadcastContract.EXTRA_POWER_KEY_ELAPSED, now),
        )
        Log.i(TAG, "EARLY_POWER_DOWN_SENT elapsed=$now")
    }
}
