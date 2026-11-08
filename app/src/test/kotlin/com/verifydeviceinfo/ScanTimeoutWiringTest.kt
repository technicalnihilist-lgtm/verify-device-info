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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `AndroidDeviceInformationProvider` promises that a scan always finishes: a stage that never
 * answers is abandoned, recorded in `collectionErrors`, and the remaining stages still run, so the
 * app shows what it could read instead of staying on "Scanning" for the life of the process.
 *
 * Nothing exercised that wiring before — `StageTimerTest` covers the timer in isolation, and the
 * provider's collectors need a device. These tests drive the provider itself with a timer whose
 * whole-scan budget is already spent, which is the state a wedged scan reaches: every collection
 * stage must be reported as timed out and the snapshot must still be produced.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScanTimeoutWiringTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A provider whose stages can never run: the scan budget is spent before the first one. */
    private fun exhaustedProvider() = AndroidDeviceInformationProvider(
        context = context,
        props = SystemPropertyReader { emptyMap() },
        timerFactory = { scope -> StageTimer(scope, totalTimeoutMillis = 0L) }
    )

    @Test
    fun `a scan with no budget left still returns a snapshot`() = runBlocking {
        val snapshot = exhaustedProvider().collect { }

        assertNotNull(snapshot)
        assertFalse("a fallback snapshot is not mock data", snapshot.isMock)
        // Every collection stage is accounted for rather than silently missing: nine of them,
        // with connectivity/audio and security/software each sharing one progress step.
        assertEquals(9, snapshot.collectionErrors.size)
        snapshot.collectionErrors.forEach { error ->
            assertTrue("unexpected stage report: $error", error.contains("timed out"))
        }
    }

    @Test
    fun `the fallbacks are the values the app already showed`() = runBlocking {
        val snapshot = exhaustedProvider().collect { }

        // Identity falls back to the build properties rather than to blank strings.
        assertEquals(android.os.Build.MANUFACTURER ?: "Unknown", snapshot.identity.manufacturer)
        assertEquals(android.os.Build.MODEL ?: "Unknown", snapshot.identity.model)
        assertNull("the marketing name is not invented by the fallback", snapshot.identity.marketingName)
        assertEquals(0, snapshot.hardware.cpuCores)
        assertEquals(0L, snapshot.hardware.ramTotalBytes)
        assertEquals("Unknown", snapshot.battery.health)
        assertEquals(0, snapshot.battery.healthCode)
        assertEquals(0, snapshot.software.installedPackageCount)
        assertEquals(android.os.Build.VERSION.SDK_INT, snapshot.software.apiLevel)
        assertTrue("no field is invented for a scan that collected nothing", snapshot.fields.isEmpty())
    }
}
