package com.verifydeviceinfo

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.AppPermission
import com.verifydeviceinfo.core.model.PermissionState
import com.verifydeviceinfo.data.android.AndroidPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * [AndroidPermissionManager] on a Robolectric device: the preferences handle is resolved once
 * instead of on every check, and the "already asked for" flags are still read from the file earlier
 * versions wrote them to.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PermissionManagerTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
    }

    /** Counts the two lookups the manager used to repeat on every call. */
    private class CountingContext(base: Context) : ContextWrapper(base) {
        var preferencesOpened = 0
        var permissionChecks = 0

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            preferencesOpened++
            return super.getSharedPreferences(name, mode)
        }

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
            permissionChecks++
            return super.checkPermission(permission, pid, uid)
        }
    }

    private fun activity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).setup()

    @Test
    fun stateOpensThePreferencesFileOnce() {
        val context = CountingContext(app)
        val manager = AndroidPermissionManager(context)
        manager.attach(activity().get())

        assertEquals(PermissionState.DENIED, manager.state(AppPermission.CAMERA))
        assertEquals(PermissionState.DENIED, manager.state(AppPermission.CAMERA))
        assertEquals(PermissionState.DENIED, manager.state(AppPermission.CAMERA))

        assertEquals("one handle, not one per call", 1, context.preferencesOpened)
    }

    @Test
    fun oneHandleSurvivesActivityRecreation() {
        val context = CountingContext(app)
        val manager = AndroidPermissionManager(context)
        val first = activity()
        manager.attach(first.get())
        assertEquals(PermissionState.DENIED, manager.state(AppPermission.CAMERA))
        first.destroy()

        val second = activity()
        manager.attach(second.get())
        assertEquals(PermissionState.DENIED, manager.state(AppPermission.CAMERA))

        assertEquals(1, context.preferencesOpened)
    }

    @Test
    fun aRefusalAfterAnEarlierRequestIsPermanent() {
        val manager = AndroidPermissionManager(CountingContext(app))
        val controller = activity()
        val activity = controller.get()
        // Written exactly the way earlier versions wrote it: into the activity's preferences file.
        activity.getPreferences(0).edit()
            .putBoolean("requested_${Manifest.permission.CAMERA}", true)
            .commit()
        manager.attach(activity)

        assertEquals(PermissionState.PERMANENTLY_DENIED, manager.state(AppPermission.CAMERA))
    }

    @Test
    fun anUntouchedPermissionIsOnlyDenied() {
        val manager = AndroidPermissionManager(CountingContext(app))
        manager.attach(activity().get())

        assertEquals(PermissionState.DENIED, manager.state(AppPermission.CAMERA))
    }

    @Test
    fun theFlagIsWrittenBeforeTheRequestIsRaised() = runTest {
        val manager = AndroidPermissionManager(CountingContext(app))
        val controller = activity()
        val activity = controller.get()
        manager.attach(activity)

        var result: PermissionState? = null
        val job = launch(Dispatchers.Unconfined) { result = manager.request(AppPermission.CAMERA) }

        // The request is raised only after the flag is recorded, so a crash between the two cannot
        // leave a permission that reads as merely denied when it is permanently denied.
        assertTrue(
            activity.getPreferences(0).getBoolean("requested_${Manifest.permission.CAMERA}", false)
        )
        assertEquals(null, result)

        manager.onRequestPermissionsResult(4100, intArrayOf(PackageManager.PERMISSION_GRANTED))
        job.join()
        assertEquals(PermissionState.GRANTED, result)
    }

    @Test
    fun aGrantedPermissionNeedsOneLookupAndNoActivity() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val context = CountingContext(app)
        val manager = AndroidPermissionManager(context)

        assertTrue(manager.isGranted(AppPermission.CAMERA))
        assertEquals(PermissionState.GRANTED, manager.state(AppPermission.CAMERA))
        // isGranted resolves the state once; a refused permission used to be resolved twice.
        assertEquals(2, context.permissionChecks)
    }

    @Test
    fun isGrantedResolvesARefusalOnce() {
        val context = CountingContext(app)
        val manager = AndroidPermissionManager(context)

        assertFalse(manager.isGranted(AppPermission.CAMERA))
        assertEquals("one state() call, not two", 1, context.permissionChecks)
    }

    @Test
    @Config(sdk = [30])
    fun aPermissionThePlatformDoesNotHaveIsNotRequired() {
        // BLUETOOTH_CONNECT arrived in API 31; below it there is nothing to ask for, and the
        // manager must not open the preferences file to find that out.
        val context = CountingContext(app)
        val manager = AndroidPermissionManager(context)

        assertEquals(PermissionState.NOT_REQUIRED, manager.state(AppPermission.BLUETOOTH))
        assertTrue(manager.isGranted(AppPermission.BLUETOOTH))
        assertEquals(0, context.preferencesOpened)
        assertEquals(0, context.permissionChecks)
    }
}
