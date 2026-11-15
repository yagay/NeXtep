package io.github.lujinxin.nextep.media

import android.net.Uri

/** Single IPC contract shared by app-owned service and scoped LSPosed observers. */
object MediaBridgeContract {
    const val AUTHORITY = "io.github.lujinxin.nextep.media.bridge"
    val URI: Uri = Uri.parse("content://$AUTHORITY")
    const val METHOD_ALLOWED = "is_allowed"
    const val METHOD_OBSERVE = "observe"
    const val KEY_PACKAGE = "package"
    const val KEY_STATE = "state"
    const val KEY_SOURCE = "source"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_DURATION = "duration"
    const val KEY_POSITION = "position"
    const val KEY_CONTROLLABLE = "controllable"
    const val KEY_ALLOWED = "allowed"
    const val KEY_ACCEPTED = "accepted"
    const val STATE_PLAYING = "playing"
    const val STATE_PAUSED = "paused"
    const val STATE_STOPPED = "stopped"
    const val ACTION_COMMAND = "io.github.lujinxin.nextep.media.COMMAND"
    const val EXTRA_COMMAND = "command"
    const val COMMAND_PLAY = "play"
    const val COMMAND_PAUSE = "pause"
    // Sender permission checked by receiver registered inside the scoped app.
    const val COMMAND_SENDER_PERMISSION =
        "io.github.lujinxin.nextep.permission.MEDIA_BRIDGE_CONTROL"
}
