package io.github.lujinxin.nextep.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import io.github.lujinxin.nextep.config.SettingsRepository
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * Accepts complete dock snapshots exclusively from the SystemUI process.
 * Never accepts player-originated events or unauthenticated playback commands.
 * The manifest additionally guards this provider with STATUS_BAR_SERVICE.
 */
class MediaBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val response = Bundle().apply {
            putBoolean(MediaBridgeContract.KEY_ACCEPTED, false)
        }
        if (method != MediaBridgeContract.METHOD_DOCK_SNAPSHOT) return response
        val ctx = context ?: return response
        val callingPackages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
            ?.toSet().orEmpty()
        if ("com.android.systemui" !in callingPackages) {
            NeXtepLog.warn("media_bridge", "UNTRUSTED_DOCK_REPORT uid=${Binder.getCallingUid()}")
            return response
        }
        if (!SettingsRepository(ctx).isMediaBridgeEnabled()) return response

        fun validPackages(key: String) =
            extras?.getStringArrayList(key).orEmpty()
                .filter { it.length in 3..180 &&
                    it.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")) }
                .distinct().take(4).toSet()
        val occupied = validPackages(MediaBridgeContract.KEY_DOCK_PACKAGES)
        val playing = validPackages(MediaBridgeContract.KEY_PLAYING_PACKAGES)
            .intersect(occupied)
        MediaBridgeListenerService.acceptDock(
            occupied, playing, extras?.getBoolean(MediaBridgeContract.KEY_UID_READABLE) == true)
        response.putBoolean(MediaBridgeContract.KEY_ACCEPTED, true)
        return response
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?): Int = 0
}
