package com.verifydeviceinfo

import com.verifydeviceinfo.core.util.logicalSensorCount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins how wake-up handles are folded into the sensor count. Android publishes a wake-up variant
 * of a sensor as a second handle of the same type, so the raw `getSensorList(TYPE_ALL)` length is
 * not the number of sensors in the device.
 */
class SensorInventoryTest {

    private val accelerometer = 1
    private val magneticField = 2
    private val gyroscope = 4
    private val light = 5
    private val significantMotion = 17

    private fun sensor(type: Int, wakeUp: Boolean, name: String = "sensor-$type") =
        TestFixtures.sensor(category = name, name = name, type = type, wakeUp = wakeUp)

    @Test
    fun `a wake-up handle is not a second sensor`() {
        val enumerated = listOf(
            sensor(accelerometer, wakeUp = false),
            sensor(accelerometer, wakeUp = true),
            sensor(gyroscope, wakeUp = false),
            sensor(gyroscope, wakeUp = true)
        )

        // Four handles, two sensors — the pair a phone with wake-up delivery enumerates per sensor.
        assertEquals(2, logicalSensorCount(enumerated))
    }

    @Test
    fun `a wake-up handle is counted when its type has no ordinary handle`() {
        val enumerated = listOf(
            sensor(significantMotion, wakeUp = true),
            sensor(accelerometer, wakeUp = false),
            sensor(accelerometer, wakeUp = true)
        )

        // Significant motion is wake-up only; dropping it would hide a real sensor.
        assertEquals(2, logicalSensorCount(enumerated))
    }

    @Test
    fun `two ordinary sensors of one type stay two sensors`() {
        val twoChips = listOf(
            sensor(accelerometer, wakeUp = false, name = "LSM6DSO Accelerometer"),
            sensor(accelerometer, wakeUp = false, name = "BMI160 Accelerometer")
        )
        assertEquals(2, logicalSensorCount(twoChips))

        // Each chip publishing its own wake-up handle does not turn two sensors into four.
        val withWakeUpHandles = twoChips + listOf(
            sensor(accelerometer, wakeUp = true, name = "LSM6DSO Accelerometer"),
            sensor(accelerometer, wakeUp = true, name = "BMI160 Accelerometer")
        )
        assertEquals(2, logicalSensorCount(withWakeUpHandles))
    }

    @Test
    fun `the wake-up handle is dropped wherever it appears in the enumeration`() {
        val wakeUpFirst = listOf(
            sensor(magneticField, wakeUp = true),
            sensor(magneticField, wakeUp = false)
        )
        assertEquals(1, logicalSensorCount(wakeUpFirst))
    }

    @Test
    fun `no sensors counts zero`() {
        assertEquals(0, logicalSensorCount(emptyList()))
    }

    @Test
    fun `an all ordinary enumeration is counted one for one`() {
        val enumerated = listOf(
            sensor(accelerometer, wakeUp = false),
            sensor(gyroscope, wakeUp = false),
            sensor(light, wakeUp = false)
        )
        assertEquals(3, logicalSensorCount(enumerated))
    }
}
