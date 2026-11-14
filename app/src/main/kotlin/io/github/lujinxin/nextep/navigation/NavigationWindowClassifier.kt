package io.github.lujinxin.nextep.navigation

import java.util.Locale

/** Window-state heuristic only. Never inspects nodes or touches app input. */
internal object NavigationWindowClassifier {
    fun classify(packageName: String, className: String, homePackages: Set<String>): String? {
        if (packageName.isBlank()) return null
        val pkg = packageName.lowercase(Locale.ROOT)
        val cls = className.lowercase(Locale.ROOT)
        val recents = listOf(
            "recents", "overview", "recenttask", "taskoverview", "task_switch", "taskswitch"
        ).any(cls::contains)
        val trustedRecentsHost = packageName in homePackages ||
            pkg == "com.android.systemui" ||
            pkg.contains("launcher") || pkg.contains("quickstep")
        return when {
            recents && trustedRecentsHost -> "recents"
            packageName in homePackages -> "home"
            else -> null
        }
    }
}
