package com.verifydeviceinfo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.AppPermission
import com.verifydeviceinfo.core.model.DiagnosticTestDescriptor
import com.verifydeviceinfo.data.android.AndroidHardwareDiagnosticsEngine
import com.verifydeviceinfo.data.android.AndroidPermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The interactive half of the diagnostics catalogue: the 22 descriptors that become the rows of
 * the Diagnostics screen.
 *
 * Two things are pinned here.
 *
 * First, the contents. `AutomatedChecksTest` pins the ids the automated pass *reports*; nothing
 * pinned the catalogue the screen *lists*, so a dropped entry, a duplicated one, a reordered one,
 * a row moved to another category or a permission that went missing would have shown up only as a
 * changed `07-diagnostics` screenshot — a change that a deliberate screenshot update would have
 * swallowed. The table below is the whole catalogue: id, category, required permission and the two
 * flags, in the order the screen renders them.
 *
 * Second, that the catalogue is built once. It used to be a `listOf(...)` inside `availableTests`,
 * rebuilt on every call — and `DiagnosticsScreen` calls it on every recomposition — so the same
 * immutable 22 descriptors were allocated again and again. The same-instance test is what makes
 * that guarantee checkable: if the catalogue ever goes back to being built per call, this fails.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DiagnosticsCatalogueTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun engine() =
        AndroidHardwareDiagnosticsEngine(app, AndroidPermissionManager(app))

    /**
     * One row of the catalogue: `id`, category, required permission, `requiresManualConfirmation`.
     * Every entry is `automated = false` — a test that needs eyes, ears or a finger is never
     * auto-passed — which the last assertion checks for the whole list rather than per row.
     */
    private class Row(
        val id: String,
        val category: String,
        val permission: AppPermission?,
        val requiresManualConfirmation: Boolean
    )

    private val expected = listOf(
        Row("touch", "Display", null, true),
        Row("display_colour", "Display", null, true),
        Row("display_dead_pixels", "Display", null, true),
        Row("display_brightness", "Display", null, true),
        Row("camera_rear", "Camera", AppPermission.CAMERA, true),
        Row("camera_front", "Camera", AppPermission.CAMERA, true),
        Row("flashlight", "Camera", AppPermission.CAMERA, true),
        Row("speaker", "Audio", null, true),
        Row("earpiece", "Audio", null, true),
        Row("microphone", "Audio", AppPermission.MICROPHONE, true),
        Row("vibration", "Haptics", null, true),
        Row("sensor_accelerometer", "Sensors", null, true),
        Row("sensor_gyroscope", "Sensors", null, true),
        Row("sensor_proximity", "Sensors", null, true),
        Row("sensor_light", "Sensors", null, true),
        Row("sensor_compass", "Sensors", null, true),
        Row("gps", "Connectivity", AppPermission.LOCATION, true),
        Row("wifi", "Connectivity", null, false),
        Row("bluetooth", "Connectivity", AppPermission.BLUETOOTH, false),
        Row("nfc", "Connectivity", null, false),
        Row("charging", "Power", null, false),
        Row("biometric", "Security", null, false)
    )

    @Test
    fun theCatalogueListsEveryInteractiveTestInTheOrderTheScreenRendersThem() {
        val actual = engine().availableTests()
        assertEquals(
            "the catalogue changed; see DiagnosticsCatalogueTest.expected",
            expected.map { it.id },
            actual.map { it.id }
        )
    }

    @Test
    fun everyDescriptorKeepsItsCategoryPermissionAndConfirmationFlag() {
        val actual = engine().availableTests()
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (row, descriptor) ->
            assertEquals("category of ${row.id}", row.category, descriptor.category)
            assertEquals("permission of ${row.id}", row.permission, descriptor.permission)
            assertEquals(
                "requiresManualConfirmation of ${row.id}",
                row.requiresManualConfirmation,
                descriptor.requiresManualConfirmation
            )
            // Nothing in this catalogue is auto-passed: each one needs a person to judge it.
            assertEquals("automated flag of ${row.id}", false, descriptor.automated)
        }
    }

    @Test
    fun everyDescriptorCarriesARowTitleAndADescription() {
        engine().availableTests().forEach { descriptor ->
            assertTrue("blank title for ${descriptor.id}", descriptor.title.isNotBlank())
            assertTrue("blank description for ${descriptor.id}", descriptor.description.isNotBlank())
        }
    }

    /**
     * The catalogue is a constant, so one engine builds it once and hands out the same instance on
     * every call. This is the assertion that stops it from quietly becoming a per-call allocation
     * again — `DiagnosticsScreen` asks for it on every recomposition, not once per screen.
     */
    @Test
    fun theCatalogueIsBuiltOnceRatherThanPerCall() {
        val diagnostics = engine()
        val first = diagnostics.availableTests()
        repeat(3) {
            assertSame(
                "availableTests() rebuilt the catalogue instead of returning the built one",
                first,
                diagnostics.availableTests()
            )
        }
    }

    /** Two engines describe the same tests: the catalogue carries no device state. */
    @Test
    fun twoEnginesListTheSameTests() {
        val a: List<DiagnosticTestDescriptor> = engine().availableTests()
        val b: List<DiagnosticTestDescriptor> = engine().availableTests()
        assertEquals(a, b)
    }
}
