package com.verifydeviceinfo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.data.android.collect.CameraCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the camera section reports when the camera service refuses to enumerate.
 *
 * `CameraCollector` read the id list and built the per-camera rows inside one `try`. When the read
 * threw, the `catch` emitted a "Camera modules — Not accessible" row and then execution carried on
 * to the section's summary row, which printed `"${cameras.size} (… rear, … front)"` — that is,
 * **`0 (0 rear, 0 front)`**, as a VERIFIED measurement with HIGH confidence, on a device whose camera
 * list was never read. The same report therefore said both "camera enumeration failed" and "this
 * device has no cameras", and the second of those is a fact the app never observed.
 *
 * The enumeration is now a [CameraCollector] constructor parameter so the failure can be driven
 * here: production passes nothing and keeps using `CameraManager.cameraIdList`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CameraCollectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun countRow(fields: List<SpecField>): SpecField =
        fields.first { it.key == "camera.count" }

    @Test
    fun `a refused enumeration is not reported as a device with no cameras`() {
        val collector = CameraCollector(context) { throw IllegalStateException("camera service refused") }

        val (cameras, fields) = collector.collect()

        assertTrue(cameras.isEmpty())
        val count = countRow(fields)
        assertEquals(DiagnosticStatus.UNVERIFIED, count.status)
        assertEquals("Unavailable", count.value)
        assertFalse(count.value.startsWith("0 ("))
        assertEquals(
            "Unreadable: camera enumeration failed: camera service refused",
            count.note
        )
    }

    @Test
    fun `the failure is reported once, not as a zero and a failure`() {
        val collector = CameraCollector(context) { throw IllegalStateException("binder died") }

        val (_, fields) = collector.collect()

        assertEquals(listOf("camera.list", "camera.count"), fields.map { it.key })
        assertEquals("Not accessible", fields[0].value)
        assertEquals(DiagnosticStatus.NOT_SUPPORTED, fields[0].status)
        assertEquals("Camera enumeration failed: binder died", fields[0].note)
    }

    @Test
    fun `an exception with no message still names what failed`() {
        val collector = CameraCollector(context) { throw IllegalStateException() }

        val (_, fields) = collector.collect()

        assertEquals(
            "Unreadable: camera enumeration failed: IllegalStateException",
            countRow(fields).note
        )
    }

    @Test
    fun `a camera service that enumerates nothing still reports zero cameras`() {
        val collector = CameraCollector(context) { emptyList() }

        val (cameras, fields) = collector.collect()

        assertTrue(cameras.isEmpty())
        val count = countRow(fields)
        assertEquals(DiagnosticStatus.VERIFIED, count.status)
        assertEquals("0 (0 rear, 0 front)", count.value)
        assertNull("an enumeration that answered is not a failure", count.note)
    }

    @Test
    fun `the count row keeps the label, group and source a working device reports`() {
        val (_, fields) = CameraCollector(context).collect()

        val count = countRow(fields)
        assertEquals("Camera modules enumerated", count.label)
        assertEquals(SpecGroup.CAMERA, count.group)
        assertEquals("CameraManager.cameraIdList", count.source)
        assertEquals("0 (0 rear, 0 front)", count.value)
    }
}
