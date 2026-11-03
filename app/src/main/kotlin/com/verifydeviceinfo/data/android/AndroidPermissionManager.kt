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
import com.verifydeviceinfo.core.util.WeakRefHolder
import com.verifydeviceinfo.domain.PermissionManager
import kotlinx.coroutines.CompletableDeferred

/**
 * Runtime permission handling. A permission is requested only when a diagnostic that needs it
 * is started, and a refusal is treated as a normal outcome, never as a test failure.
 */
class AndroidPermissionManager(private val context: Context) : PermissionManager {

    /**
     * The activity a permission request is started from. Held weakly and detached by its owner on
     * destroy: this manager is process-scoped (it lives in [com.verifydeviceinfo.AppContainer], which
     * outlives the activity across configuration changes), so a strong reference here would keep a
     * destroyed activity — and its whole view tree — alive for the rest of the process.
     */
    private val activityRef = WeakRefHolder<ComponentActivity>()

    /**
     * The "already asked for" flags, resolved once per process.
     *
     * They live in the activity's own preferences file, named after
     * [android.app.Activity.getLocalClassName] by [android.app.Activity.getPreferences], so the name
     * is taken from the first activity that attaches; every later activity of the same class resolves
     * to the same file. The handle itself is opened from the application context — the file belongs to
     * the package, not to the activity — so nothing here keeps an activity alive.
     */
    @Volatile
    private var requestLog: PermissionRequestLog? = null

    /** Binds the current activity, replacing any earlier one. */
    fun attach(activity: ComponentActivity) {
        activityRef.set(activity)
        requestLogFor(activity)
    }

    private fun requestLogFor(activity: ComponentActivity): PermissionRequestLog =
        requestLog ?: synchronized(this) {
            requestLog ?: PermissionRequestLog(context, activity.localClassName).also { requestLog = it }
        }

    /** Unbinds [activity]; a detach from an activity that has already been replaced is ignored. */
    fun detach(activity: ComponentActivity) {
        activityRef.clear(activity)
    }

    private fun currentActivity(): ComponentActivity? = activityRef.get()

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
        if (ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED) {
            return PermissionState.GRANTED
        }
        val activity = currentActivity() ?: return PermissionState.DENIED
        val permanently = !ActivityCompat.shouldShowRequestPermissionRationale(activity, name) &&
            requestLogFor(activity).wasRequested(name)
        return if (permanently) PermissionState.PERMANENTLY_DENIED else PermissionState.DENIED
    }

    override fun isGranted(permission: AppPermission): Boolean {
        // One lookup, not one per branch: a refused permission used to be resolved twice per call.
        val current = state(permission)
        return current == PermissionState.GRANTED || current == PermissionState.NOT_REQUIRED
    }

    override suspend fun request(permission: AppPermission): PermissionState {
        val name = androidPermission(permission) ?: return PermissionState.NOT_REQUIRED
        if (isGranted(permission)) return PermissionState.GRANTED
        val activity = currentActivity() ?: return PermissionState.DENIED
        val deferred = CompletableDeferred<PermissionState>()
        val code = nextRequestCode++
        pending[code] = deferred
        requestLogFor(activity).markRequested(name)
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
