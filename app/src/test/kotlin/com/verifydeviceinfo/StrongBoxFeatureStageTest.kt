package com.verifydeviceinfo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.util.StageTimer
import com.verifydeviceinfo.data.android.AndroidDeviceInformationProvider
import com.verifydeviceinfo.data.android.SystemPropertyReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The StrongBox feature query is the one framework call in
 * [AndroidDeviceInformationProvider.collect] that is not a collector stage.
 * `PackageManager.hasSystemFeature` is a Binder call into the system service, so it can block like
 * the camera or sensor service can, and on a device whose package service has died it throws.
 * Called directly, either one took the whole scan with it: every field the nine collector stages had
 * already gathered was discarded and the user was told "Scan failed" because a feature flag could
 * not be read. It now runs under the same [StageTimer] deadline as the stages, so a failure costs
 * one `collectionErrors` line and a timeout costs the flag alone.
 *
 * The query is a constructor parameter, like [AndroidDeviceInformationProvider]'s timer factory, so
 * these two paths can be driven without a device whose package manager misbehaves. The stage budget
 * is deliberately small and the whole-scan budget large: a real collector under Robolectric either
 * answers quickly or is abandoned inside a second, so the scan reaches the feature check promptly
 * whatever the harness does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StrongBoxFeatureStageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun provider(strongBoxFeature: () -> Boolean) = AndroidDeviceInformationProvider(
        context = context,
        props = SystemPropertyReader { emptyMap() },
        timerFactory = { scope ->
            StageTimer(scope, stageTimeoutMillis = 1_000L, totalTimeoutMillis = 60_000L)
        },
        strongBoxFeature = strongBoxFeature
    )

    private fun reportsAboutStrongBox(errors: List<String>) =
        errors.filter { it.startsWith("StrongBox feature check") }

    @Test
    fun `a feature query that throws costs one error line, not the scan`() = runBlocking {
        val snapshot = provider { throw IllegalStateException("package manager has died") }
            .collect { }

        assertNotNull("the scan still produces a snapshot", snapshot)
        assertFalse("an unreadable flag is not reported as present", snapshot.hardware.strongBoxAvailable)
        val reports = reportsAboutStrongBox(snapshot.collectionErrors)
        assertEquals("exactly one line about the check", 1, reports.size)
        assertTrue(
            "the line names the check and carries the cause: ${reports.single()}",
            reports.single().contains("failed") && reports.single().contains("package manager has died")
        )
    }

    @Test
    fun `a feature query that answers still reports the device's value`() = runBlocking {
        val snapshot = provider { true }.collect { }

        assertTrue("the flag reaches the snapshot", snapshot.hardware.strongBoxAvailable)
        assertEquals(
            "a query that answered reports nothing",
            emptyList<String>(),
            reportsAboutStrongBox(snapshot.collectionErrors)
        )
    }
}
