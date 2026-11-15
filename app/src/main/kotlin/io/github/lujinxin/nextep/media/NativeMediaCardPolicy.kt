package io.github.lujinxin.nextep.media

/** Suppress a dock proxy only when Android already has a real media notification.
 *
 * MediaSessionManager.getActiveSessions() can contain dormant, metadata-less
 * sessions. A token alone is NOT proof that the app publishes a usable media
 * card. This policy prevents "native session present" from hiding all dock audio.
 */
internal object NativeMediaCardPolicy {
    const val PLAYING = 3
    const val PAUSED = 2
    const val BUFFERING = 6
    const val CONNECTING = 8

    fun shouldPreferNative(
        hasMediaStyleNotification: Boolean,
        playbackState: Int?,
    ): Boolean = hasMediaStyleNotification && playbackState in setOf(
        PLAYING, PAUSED, BUFFERING, CONNECTING
    )
}
