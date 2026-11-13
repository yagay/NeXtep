package io.github.lujinxin.nextep.config

/**
 * Never expose the list of selected packages to arbitrary installed apps.
 * The only IPC response is whether the caller's own UID is opted in.
 */
internal object PlaybackCompatAccessPolicy {
    fun mayReadOwnOptIn(
        requestedPackage: String?,
        uidPackages: Set<String>,
        enabled: Boolean,
        selected: Set<String>,
    ): Boolean {
        if (requestedPackage.isNullOrBlank() ||
            !requestedPackage.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return false
        return enabled && requestedPackage in uidPackages && requestedPackage in selected
    }
}
