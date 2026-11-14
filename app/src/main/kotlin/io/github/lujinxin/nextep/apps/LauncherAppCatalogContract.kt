package io.github.lujinxin.nextep.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock

/** SystemUI has cross-user access that the ordinary settings APK does not. */
object LauncherAppCatalogContract {
    private const val ACTION_QUERY = "io.github.lujinxin.nextep.action.QUERY_PROFILE_APPS"
    private const val RESULT_CATALOG = 35_201
    private const val EXTRA_APPS = "profile_apps"
    private const val EXTRA_LANGUAGE_TAG = "language_tag"
    private val registered = AtomicBoolean(false)

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        runCatching {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!isOrderedBroadcast || intent.action != ACTION_QUERY) return
                    val pending = goAsync()
                    val started = SystemClock.elapsedRealtime()
                    val done = AtomicBoolean(false)
                    val main = Handler(Looper.getMainLooper())
                    val deadline = Runnable {
                        if (done.compareAndSet(false, true)) {
                            NeXtepLog.warn("app_catalog",
                                "PROFILE_APPS_TIMEOUT elapsed=${SystemClock.elapsedRealtime() - started}ms; " +
                                    "finishing broadcast without result")
                            pending.finish()
                        }
                    }
                    // Even if ColorOS cross-user queries deadlock, never keep a
                    // foreground ordered broadcast open until ActivityManager kills SystemUI.
                    main.postDelayed(deadline, 2_500L)
                    Thread({
                        var records: ArrayList<Bundle>? = null
                        try {
                            val repository = LauncherAppRepository(context)
                            AppLanguage.updateHostLanguage(intent.getStringExtra(EXTRA_LANGUAGE_TAG).orEmpty())
                            records = ArrayList(repository.load()
                                .filter { it.userId != repository.currentUserId }
                                .map { app -> Bundle().apply {
                                    putString("key", app.key)
                                    putString("component", app.component.flattenToString())
                                    putInt("user_id", app.userId)
                                    putString("label", app.label)
                                    putBoolean("is_clone", app.isClone)
                                } })
                        } catch (error: Throwable) {
                            NeXtepLog.warn("app_catalog", "Unable to query profile apps", error)
                        } finally {
                            main.post {
                                if (done.compareAndSet(false, true)) {
                                    main.removeCallbacks(deadline)
                                    if (records != null) {
                                        pending.setResultExtras(Bundle().apply {
                                            putParcelableArrayList(EXTRA_APPS, records)
                                        })
                                        pending.resultCode = RESULT_CATALOG
                                    }
                                    pending.finish()
                                    NeXtepLog.info("app_catalog",
                                        "PROFILE_APPS_FINISHED elapsed=${SystemClock.elapsedRealtime() - started}ms")
                                }
                            }
                        }
                    }, "NeXtep-AppCatalog").apply { isDaemon = true }.start()
                }
            }, IntentFilter(ACTION_QUERY), TriggerBroadcastContract.CONTROL_PERMISSION,
                Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED)
        }.onFailure {
            registered.set(false)
            NeXtepLog.warn("app_catalog", "Unable to register profile catalogue", it)
        }
    }

    fun query(context: Context, callback: (List<LauncherAppRepository.AppEntry>?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val timeout = Runnable {
            if (!finished) { finished = true; callback(null) }
        }
        fun finish(result: List<LauncherAppRepository.AppEntry>?) {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeout)
            callback(result)
        }
        handler.postDelayed(timeout, 4_000L)
        runCatching {
            context.sendOrderedBroadcast(Intent(ACTION_QUERY)
                .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
                .putExtra(EXTRA_LANGUAGE_TAG, AppLanguage.selectedTag(context))
                , null, object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (finished) return
                    if (resultCode != RESULT_CATALOG) { finish(null); return }
                    val records = getResultExtras(false)
                        ?.getParcelableArrayList(EXTRA_APPS, Bundle::class.java)
                    if (records == null) { finish(null); return }
                    val repository = LauncherAppRepository(context)
                    finish(records.mapNotNull { record ->
                        val component = record.getString("component")
                            ?.let(ComponentName::unflattenFromString) ?: return@mapNotNull null
                        repository.fromMetadata(record.getString("key").orEmpty(), component,
                            record.getInt("user_id", -1), record.getString("label").orEmpty(),
                            record.getBoolean("is_clone"))
                    })
                }
            }, handler, 0, null, null)
        }.onFailure { finish(null) }
    }
}
