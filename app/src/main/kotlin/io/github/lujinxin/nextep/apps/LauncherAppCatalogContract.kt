package io.github.lujinxin.nextep.apps

import android.content.Context
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * Stability quarantine for the SystemUI ordered cross-user catalogue bridge.
 *
 * OOS 17 activity-manager events show com.android.systemui terminated twice
 * after QUERY_PROFILE_APPS receiver execution. The former dynamic receiver
 * called goAsync() and performed unbounded PackageManager cross-user queries.
 * Preserve the API for callers, but do NOT deliver the suspect broadcast or
 * register the receiver until a bounded external IPC implementation is ready.
 *
 * The current-user catalogue does not depend on this optional bridge.
 */
object LauncherAppCatalogContract {
    fun register(context: Context) {
        NeXtepLog.warn(
            "app_catalog",
            "PROFILE_APPS_RECEIVER_QUARANTINED on SystemUI; no receiver registered"
        )
    }

    fun query(context: Context, callback: (List<LauncherAppRepository.AppEntry>?) -> Unit) {
        callback(null) // Explicit optional cross-profile fallback
    }
}
