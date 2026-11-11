package com.verifydeviceinfo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.data.android.collect.SensorCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the sensor section reports when the sensor service refuses to enumerate.
 *
 * `SensorCollector` wrapped `SensorManager.getSensorList(TYPE_ALL)` in a `try` whose `catch` returned
 * `emptyList()`, and the section then printed `"Sensors enumerated: 0"` through `FieldBuilder.value`
 * — a VERIFIED measurement with HIGH confidence, on a device whose sensor list was never read. This
 * is the camera defect (Z1) in another collector: a refused or throwing read became a measurement of
 * absence.
 *
 * The enumeration is now a [SensorCollector] constructor parameter so the failure can be driven
 * here: production passes nothing and keeps using `SensorManager.getSensorList(TYPE_ALL)`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SensorCollectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun countRow(fields: List<SpecField>): SpecField =
        fields.first { it.key == "sensor.count" }

    private fun categoryRows(fields: List<SpecField>): List<SpecField> =
        fields.filter { it.key.startsWith("sensor.") && it.key != "sensor.count" }

    @Test
    fun `a refused enumeration is not reported as a device with no sensors`() {
        val collector = SensorCollector(context) { throw IllegalStateException("sensor service refused") }

        val (sensors, fields) = collector.collect()

        assertTrue(sensors.isEmpty())
        val count = countRow(fields)
        assertEquals(DiagnosticStatus.UNVERIFIED, count.status)
        assertEquals(Confidence.NONE, count.confidence)
        assertEquals("Unavailable", count.value)
        assertFalse(count.value == "0")
        assertEquals(
            "Unreadable: sensor enumeration failed: sensor service refused",
            count.note
        )
    }

    @Test
    fun `an exception with no message still names what failed`() {
        val collector = SensorCollector(context) { throw IllegalStateException() }

        val (_, fields) = collector.collect()

        assertEquals(
            "Unreadable: sensor enumeration failed: IllegalStateException",
            countRow(fields).note
        )
    }

    @Test
    fun `a sensor service that enumerates nothing still reports zero sensors`() {
        val collector = SensorCollector(context) { emptyList() }

        val (sensors, fields) = collector.collect()

        assertTrue(sensors.isEmpty())
        val count = countRow(fields)
        assertEquals(DiagnosticStatus.VERIFIED, count.status)
        assertEquals(Confidence.HIGH, count.confidence)
        assertEquals("0", count.value)
        assertNull("an enumeration that answered is not a failure", count.note)
    }

    @Test
    fun `the seven category rows keep their wording when enumeration failed`() {
        val collector = SensorCollector(context) { throw IllegalStateException("binder died") }

        val (_, fields) = collector.collect()

        val categories = categoryRows(fields)
        assertEquals(7, categories.size)
        categories.forEach {
            assertEquals("Not reported by the sensor service.", it.note)
            assertEquals("Unavailable", it.value)
        }
    }

    @Test
    fun `the count row keeps the label, group and source a working device reports`() {
        val (_, fields) = SensorCollector(context).collect()

        val count = countRow(fields)
        assertEquals("Sensors enumerated", count.label)
        assertEquals(SpecGroup.SENSOR, count.group)
        assertEquals("SensorManager.getSensorList(TYPE_ALL)", count.source)
        assertEquals("0", count.value)
    }
}
