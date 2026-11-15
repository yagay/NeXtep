package io.github.lujinxin.nextep.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import io.github.lujinxin.nextep.config.SettingsRepository
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * UID-verified ingress. A selected source package can only report for itself.
 * No exported broadcast accepting unauthenticated media commands/data.
 */
class MediaBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        if (method != MediaBridgeContract.METHOD_ALLOWED &&
            method != MediaBridgeContract.METHOD_OBSERVE) return result
        val ctx = context ?: return result
        val source = extras?.getString(MediaBridgeContract.KEY_PACKAGE).orEmpty()
        val packages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
            ?.toSet().orEmpty()
        if (source.isBlank() || source !in packages ||
            source == ctx.packageName) return result
        val settings = SettingsRepository(ctx)
        val allowed = settings.isMediaBridgeEnabled() &&
            source in settings.mediaBridgePackages()
        result.putBoolean(MediaBridgeContract.KEY_ALLOWED, allowed)
        if (!allowed || method != MediaBridgeContract.METHOD_OBSERVE) return result
        val state = extras?.getString(MediaBridgeContract.KEY_STATE)
        if (state !in setOf(MediaBridgeContract.STATE_PLAYING,
                MediaBridgeContract.STATE_PAUSED, MediaBridgeContract.STATE_STOPPED)) return result
        fun clean(key: String): String? = extras?.getString(key)?.trim()
            ?.take(180)?.takeIf(String::isNotBlank)
        val snapshot = MediaBridgeSnapshot(
            packageName = source,
            state = state!!,
            source = clean(MediaBridgeContract.KEY_SOURCE) ?: "generic",
            title = clean(MediaBridgeContract.KEY_TITLE),
            artist = clean(MediaBridgeContract.KEY_ARTIST),
            durationMs = (extras?.getLong(MediaBridgeContract.KEY_DURATION, -1L) ?: -1L)
                .coerceIn(-1L, 86_400_000L),
            positionMs = (extras?.getLong(MediaBridgeContract.KEY_POSITION, -1L) ?: -1L)
                .coerceIn(-1L, 86_400_000L),
            controllable = extras?.getBoolean(MediaBridgeContract.KEY_CONTROLLABLE, false) == true,
            receivedAtMs = SystemClock.elapsedRealtime(),
        )
        MediaBridgeListenerService.accept(snapshot)
        result.putBoolean(MediaBridgeContract.KEY_ACCEPTED, true)
        NeXtepLog.debug("media_bridge", "SOURCE_EVENT pkg=$source state=$state source=${snapshot.source}")
        return result
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?): Int = 0
}
