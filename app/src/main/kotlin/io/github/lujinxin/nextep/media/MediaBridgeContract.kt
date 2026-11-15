package io.github.lujinxin.nextep.media

import android.net.Uri

/** Only SystemUI can report the current four-slot occupants and audible UIDs. */
object MediaBridgeContract {
    const val AUTHORITY = "io.github.lujinxin.nextep.media.bridge"
    val URI: Uri = Uri.parse("content://$AUTHORITY")
    const val METHOD_DOCK_SNAPSHOT = "dock_snapshot"
    const val KEY_DOCK_PACKAGES = "dock_packages"
    const val KEY_PLAYING_PACKAGES = "playing_packages"
    const val KEY_UID_READABLE = "audio_uid_readable"
    const val KEY_ACCEPTED = "accepted"
    const val STATE_PLAYING = "playing"
    const val STATE_PAUSED = "paused"
    const val STATE_STOPPED = "stopped"
}
