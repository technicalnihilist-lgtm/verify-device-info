package com.verifydeviceinfo.data.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.verifydeviceinfo.core.model.AppPermission
import com.verifydeviceinfo.core.model.PermissionState
import com.verifydeviceinfo.domain.PermissionManager
import kotlinx.coroutines.CompletableDeferred

/**
 * Runtime permission handling. A permission is requested only when a diagnostic that needs it
 * is started, and a refusal is treated as a normal outcome, never as a test failure.
 */
class AndroidPermissionManager(private val context: Context) : PermissionManager {

    var activityProvider: (() -> ComponentActivity?)? = null

    private val pending = mutableMapOf<Int, CompletableDeferred<PermissionState>>()
    private var nextRequestCode = 4100

    fun androidPermission(permission: AppPermission): String? {
        return when (permission) {
            AppPermission.CAMERA -> Manifest.permission.CAMERA
            AppPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
            AppPermission.LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
            AppPermission.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null
            AppPermission.NEARBY_WIFI -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else null
        }
    }

    override fun state(permission: AppPermission): PermissionState {
        val name = androidPermission(permission)
            ?: return PermissionState.NOT_REQUIRED
        val granted = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
        if (granted) return PermissionState.GRANTED
        val activity = activityProvider?.invoke() ?: return PermissionState.DENIED
        val permanently = !ActivityCompat.shouldShowRequestPermissionRationale(activity, name) &&
            (activity.getPreferences(0).getBoolean("requested_$name", false))
        return if (permanently) PermissionState.PERMANENTLY_DENIED else PermissionState.DENIED
    }

    override fun isGranted(permission: AppPermission): Boolean =
        state(permission) == PermissionState.GRANTED || state(permission) == PermissionState.NOT_REQUIRED

    override suspend fun request(permission: AppPermission): PermissionState {
        val name = androidPermission(permission) ?: return PermissionState.NOT_REQUIRED
        if (isGranted(permission)) return PermissionState.GRANTED
        val activity = activityProvider?.invoke() ?: return PermissionState.DENIED
        val deferred = CompletableDeferred<PermissionState>()
        val code = nextRequestCode++
        pending[code] = deferred
        activity.getPreferences(0).edit().putBoolean("requested_$name", true).apply()
        ActivityCompat.requestPermissions(activity, arrayOf(name), code)
        return deferred.await()
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        val deferred = pending.remove(requestCode) ?: return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        deferred.complete(if (granted) PermissionState.GRANTED else PermissionState.DENIED)
    }
}
