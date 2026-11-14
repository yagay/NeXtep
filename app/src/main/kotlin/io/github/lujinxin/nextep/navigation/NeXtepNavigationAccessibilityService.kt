package io.github.lujinxin.nextep.navigation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import io.github.lujinxin.nextep.config.SettingsRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/**
 * Optional YFloat-style navigation fallback. Uses window metadata only:
 * no node traversal, screen capture, global actions or gesture interception.
 */
class NeXtepNavigationAccessibilityService : AccessibilityService() {
    private var homePackages: Set<String> = emptySet()
    private var lastSource: String? = null
    private var lastDispatch = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshHomePackages()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !SettingsRepository(this).isAccessibilityNavigationEnabled()) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == packageName) return
        if (homePackages.isEmpty()) refreshHomePackages()
        var reason = NavigationWindowClassifier.classify(
            pkg, event.className?.toString().orEmpty(), homePackages)
        if (reason == null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Launcher package may change after an OEM update or user selects
            // a different HOME app. Resolve again before ignoring.
            refreshHomePackages()
            reason = NavigationWindowClassifier.classify(
                pkg, event.className?.toString().orEmpty(), homePackages)
        }
        if (reason == null) return
        val source = "accessibility:$reason"
        val now = SystemClock.elapsedRealtime()
        if (source == lastSource && now - lastDispatch in 0L..500L) return
        lastSource = source
        lastDispatch = now
        sendBroadcast(Intent(TriggerBroadcastContract.ACTION_SYSTEM_NAVIGATION_FOLD)
            .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
            .putExtra(TriggerBroadcastContract.EXTRA_NAVIGATION_EVENT_SOURCE, source))
    }

    private fun refreshHomePackages() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val packages = mutableSetOf<String>()
        val pm = packageManager
        runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.takeIf { it != "android" }?.let(packages::add)
        }
        runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .filterTo(packages) { it != "android" }
        }
        homePackages = packages
    }

    override fun onInterrupt() = Unit
}
