package io.github.lujinxin.nextep.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * Read-only, UID-bound permission check used by the optional LSPosed
 * diagnostics inside a user-scoped player app. Never exposes package lists,
 * activity state, preferences or media-session controls to third parties.
 */
class PlaybackCompatPolicyProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "is_enabled") return null
        val owner = context ?: return Bundle().apply { putBoolean("enabled", false) }
        val callers = owner.packageManager.getPackagesForUid(Binder.getCallingUid())
            .orEmpty().toSet()
        val settings = SettingsRepository(owner).topBarSettings()
        return Bundle().apply {
            putBoolean("enabled", PlaybackCompatAccessPolicy.mayReadOwnOptIn(
                arg, callers, settings.playbackCompatEnabled, settings.playbackCompatPackages))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
