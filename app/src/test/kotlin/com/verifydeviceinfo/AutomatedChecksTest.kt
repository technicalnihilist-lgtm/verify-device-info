package com.verifydeviceinfo

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.data.android.AndroidHardwareDiagnosticsEngine
import com.verifydeviceinfo.data.android.AndroidPermissionManager
import com.verifydeviceinfo.data.android.automatedCheck
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The automated half of the diagnostics pass — the results that feed the hardware and security
 * scores — on a Robolectric device.
 *
 * Two things are pinned here. First, the shape of the pass: which checks it reports, in which
 * order, and what each one asserts about a device. A new check, a dropped check or a reordered one
 * changes what the Diagnostics screen lists and what the score is built from, so it has to break a
 * test first.
 *
 * Second, that one check which cannot be read no longer costs the whole pass. `runAutomatedChecks`
 * wrapped the storage and memory checks in `try`/`catch` but read the thermal, camera, accelerometer
 * and charging checks bare, and `AppViewModel.runAutomatedDiagnostics` turns an escaping exception
 * into "Diagnostics failed" — so a vendor service that threw took every already-measured result with
 * it. The last test drives exactly that: a `Context` whose power service throws.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutomatedChecksTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun engine(context: Context = app) =
        AndroidHardwareDiagnosticsEngine(context, AndroidPermissionManager(context))

    /** The ids the pass reports, in the order the screen lists them. */
    private val expectedIds = listOf(
        "auto_storage",
        "auto_memory",
        "auto_thermal",
        "auto_camera_enum",
        "auto_sensor_liveness",
        "charging"
    )

    @Test
    fun theAutomatedPassReportsEveryCheckInTheDocumentedOrder() = runTest {
        val results = engine().runAutomatedChecks()

        assertEquals(expectedIds, results.map { it.testId })
    }

    @Test
    fun everyResultExplainsItself() = runTest {
        val results = engine().runAutomatedChecks()

        results.forEach { result ->
            assertTrue("${result.testId} has no title", result.title.isNotBlank())
            assertTrue(
                "${result.testId} reports neither a measurement nor an explanation",
                result.measured != null || result.note != null
            )
        }
    }

    @Test
    fun storageHeadroomReportsFreeSpaceAsAPercentage() = runTest {
        val storage = engine().runAutomatedChecks().first { it.testId == "auto_storage" }

        assertTrue(
            "unexpected measurement: ${storage.measured}",
            Regex("^\\d+% free \\(.+ of .+\\)$").matches(storage.measured.orEmpty())
        )
        assertTrue(storage.status in scoredStatuses)
    }

    @Test
    fun memoryHeadroomReportsFreeSpaceAsAPercentage() = runTest {
        val memory = engine().runAutomatedChecks().first { it.testId == "auto_memory" }

        assertTrue(
            "unexpected measurement: ${memory.measured}",
            Regex("^\\d+% free \\(.+ of .+\\)$").matches(memory.measured.orEmpty())
        )
        assertTrue(memory.status in scoredStatuses)
    }

    @Test
    fun thermalStateIsScoredFromThePlatformThermalStatus() = runTest {
        val thermal = engine().runAutomatedChecks().first { it.testId == "auto_thermal" }

        // Robolectric's PowerManager reports THERMAL_STATUS_NONE, which is a pass.
        assertEquals(DiagnosticStatus.PASS, thermal.status)
        assertEquals("No throttling", thermal.measured)
        assertNull(thermal.note)
    }

    @Test
    fun cameraEnumerationFailsOnlyWhenNoModuleIsReported() = runTest {
        val cameras = engine().runAutomatedChecks().first { it.testId == "auto_camera_enum" }

        val reported = Regex("^(\\d+) camera module\\(s\\) reported$")
            .find(cameras.measured.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toInt()
        assertNotNull("unexpected measurement: ${cameras.measured}", reported)

        if (reported!! > 0) {
            assertEquals(DiagnosticStatus.PASS, cameras.status)
            assertNull(cameras.note)
        } else {
            assertEquals(DiagnosticStatus.FAILED, cameras.status)
            assertNotNull(cameras.note)
        }
    }

    @Test
    fun accelerometerLivenessNeverPassesWithoutAReading() = runTest {
        val sensor = engine().runAutomatedChecks().first { it.testId == "auto_sensor_liveness" }

        when (sensor.status) {
            DiagnosticStatus.PASS -> {
                assertTrue(
                    "a pass must carry the vector it measured: ${sensor.measured}",
                    Regex("^x=-?\\d+\\.\\d\\d y=-?\\d+\\.\\d\\d z=-?\\d+\\.\\d\\d m/s.?\$")
                        .matches(sensor.measured.orEmpty())
                )
                assertNull(sensor.note)
            }
            DiagnosticStatus.FAILED -> {
                assertNull("a missing sensor measures nothing", sensor.measured)
                assertNotNull(sensor.note)
            }
            DiagnosticStatus.WARNING -> {
                assertNull("a silent sensor measures nothing", sensor.measured)
                assertNotNull(sensor.note)
            }
            else -> throw AssertionError("unexpected status ${sensor.status}")
        }
    }

    @Test
    fun chargingIsUnverifiedOnlyWhenTheBroadcastCarriesNoTemperature() = runTest {
        val charging = engine().runAutomatedChecks().first { it.testId == "charging" }

        val measured = charging.measured.orEmpty()
        assertTrue(
            "the power source is always reported: $measured",
            measured.contains("on battery") || measured.contains("external power connected")
        )
        if (measured.contains("°C")) {
            assertTrue(charging.status in scoredStatuses)
        } else {
            assertEquals(DiagnosticStatus.UNVERIFIED, charging.status)
        }
    }

    /**
     * The bug this item was opened for: a service that throws used to escape the pass and discard
     * every result measured before it. Here the power service throws inside the thermal check, and
     * the other five checks must still be reported.
     */
    @Test
    fun aServiceThatThrowsCostsItsOwnCheckAndNothingElse() = runTest {
        val results = engine(ThrowingPowerService(app)).runAutomatedChecks()

        assertEquals(expectedIds, results.map { it.testId })
        val thermal = results.first { it.testId == "auto_thermal" }
        assertEquals(DiagnosticStatus.UNVERIFIED, thermal.status)
        assertEquals("Could not read the thermal state: power service unavailable", thermal.note)
        // Everything measured before and after it survived.
        assertFalse(results.first { it.testId == "auto_storage" }.status == DiagnosticStatus.UNVERIFIED)
        assertFalse(results.first { it.testId == "auto_memory" }.status == DiagnosticStatus.UNVERIFIED)
    }

    @Test
    fun aCheckThatThrowsBecomesAnUnverifiedRow() = runTest {
        val result = automatedCheck("auto_thermal", "Thermal state", "the thermal state") {
            throw IllegalStateException("boom")
        }

        assertEquals("auto_thermal", result.testId)
        assertEquals("Thermal state", result.title)
        assertEquals(DiagnosticStatus.UNVERIFIED, result.status)
        assertNull(result.measured)
        assertEquals("Could not read the thermal state: boom", result.note)
    }

    @Test
    fun aCheckThatSucceedsIsReportedUnchanged() = runTest {
        val measured = DiagnosticResult("auto_thermal", "Thermal state", DiagnosticStatus.PASS, "No throttling")

        val result = automatedCheck("auto_thermal", "Thermal state", "the thermal state") { measured }

        assertSame(measured, result)
    }

    /** Everything a score is built from: a check either measured a value or it did not. */
    private val scoredStatuses = setOf(
        DiagnosticStatus.PASS,
        DiagnosticStatus.WARNING,
        DiagnosticStatus.FAILED
    )

    private class ThrowingPowerService(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? {
            if (name == Context.POWER_SERVICE) throw IllegalStateException("power service unavailable")
            return super.getSystemService(name)
        }
    }
}
