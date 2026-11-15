package io.github.lujinxin.nextep.media

/**
 * Only UID matches from the exact retained dock tasks may be published.
 * Media focus, running-process lists and foreground activity are insufficient.
 */
internal object DockMediaMatchPolicy {
    fun audiblePackages(
        dockPackagesAndUids: List<Pair<String, Int>>,
        audibleUids: Set<Int>,
    ): Set<String> = dockPackagesAndUids.asSequence()
        .filter { (_, uid) -> uid >= 10_000 && uid in audibleUids }
        .map { (pkg, _) -> pkg }
        .filter(String::isNotBlank)
        .take(4)
        .toSet()
}
