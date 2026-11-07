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
import kotlinx.coroutines.CancellationException

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

    /**
     * Requests that have been raised and are waiting for the user's answer.
     *
     * The bookkeeping lives in [PendingPermissionRequests] because every request has to end with
     * its entry gone, including the ones that are never answered: a caller that is cancelled, and
     * a request the platform refuses to raise. See [request].
     */
    private val pending = PendingPermissionRequests()

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
        val request = pending.begin()
        requestLogFor(activity).markRequested(name)
        return try {
            ActivityCompat.requestPermissions(activity, arrayOf(name), request.code)
            request.deferred.await()
        } catch (e: CancellationException) {
            // The caller went away before the user answered — the screen was left while the dialog
            // was up — so nobody will ever read this answer. Drop the entry instead of keeping it,
            // and a later answer for this code is simply ignored.
            pending.abandon(request.code)
            throw e
        } catch (e: Exception) {
            // A request the platform will not raise (an activity that is already finishing, for
            // instance) is never answered. Report it as a refusal rather than failing the caller's
            // coroutine with an exception no test screen expects, and drop the entry.
            pending.abandon(request.code)
            PermissionState.DENIED
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        // An answer for a code nothing is waiting on — an abandoned request, or one already
        // answered — is ignored rather than throwing.
        pending.complete(requestCode, if (granted) PermissionState.GRANTED else PermissionState.DENIED)
    }

    /** Requests still waiting for an answer. Exists so the lifecycle in [request] can be tested. */
    internal fun pendingRequestCount(): Int = pending.size()
}
