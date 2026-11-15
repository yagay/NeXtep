package io.github.lujinxin.nextep.media

/** Pure selection model: no SystemUI or player lifecycle dependencies. */
internal data class MediaBridgeSnapshot(
    val packageName: String,
    val state: String,
    val source: String,
    val title: String?,
    val artist: String?,
    val durationMs: Long,
    val positionMs: Long,
    val controllable: Boolean,
    val receivedAtMs: Long,
)

internal object MediaBridgePolicy {
    const val PLAYING_STALE_MS = 26_000L
    const val PAUSED_STALE_MS = 45_000L

    fun choose(
        samples: Collection<MediaBridgeSnapshot>,
        nowMs: Long,
        originalSessionPackages: Set<String>,
    ): MediaBridgeSnapshot? = samples
        .asSequence()
        .filter { it.packageName !in originalSessionPackages }
        .filter { it.state == MediaBridgeContract.STATE_PLAYING ||
            it.state == MediaBridgeContract.STATE_PAUSED }
        .filter {
            val ttl = if (it.state == MediaBridgeContract.STATE_PLAYING)
                PLAYING_STALE_MS else PAUSED_STALE_MS
            nowMs >= it.receivedAtMs && nowMs - it.receivedAtMs <= ttl
        }
        .sortedWith(compareByDescending<MediaBridgeSnapshot> {
            it.state == MediaBridgeContract.STATE_PLAYING
        }.thenByDescending { it.receivedAtMs })
        .firstOrNull()
}
