package com.verifydeviceinfo.data.android

import android.content.Context
import android.content.SharedPreferences

/**
 * The record of which runtime permissions have already been asked for, used to tell a plain refusal
 * from a permanent one.
 *
 * The handle is resolved once and kept, so the callers below no longer pay for the name resolution
 * and the preferences lookup on every check — `state` is read whenever a diagnostic screen is shown
 * and once per permission on every `isGranted` call, and `request` writes on every tap.
 *
 * The file name is supplied by the caller rather than derived here because the flags written by
 * earlier versions live in the activity's own preferences file, whose name is
 * [android.app.Activity.getLocalClassName] — see [AndroidPermissionManager.attach]. Keeping the same
 * name is what lets a permission refused before an update still be recognised as permanently
 * refused after it.
 */
internal class PermissionRequestLog(
    private val context: Context,
    private val fileName: String,
) {

    private val preferences: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    /** True when this permission has been requested from the user at least once. */
    fun wasRequested(androidPermission: String): Boolean =
        preferences.getBoolean(key(androidPermission), false)

    /** Records that this permission is about to be requested. */
    fun markRequested(androidPermission: String) {
        preferences.edit().putBoolean(key(androidPermission), true).apply()
    }

    private fun key(androidPermission: String) = "requested_$androidPermission"
}
