package com.verifydeviceinfo.data.android.collect

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.SensorFacts
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.util.logicalSensorCount
import java.util.Locale

/** Enumerates every sensor the sensor service reports, with vendor strings intact. */
class SensorCollector(
    private val context: Context,
    /**
     * Reads the sensor list from the sensor service.
     *
     * Injectable only so the enumeration-failure path can be driven by a test without a device:
     * production always uses `SensorManager.getSensorList(Sensor.TYPE_ALL)`. It is a
     * `SensorManager ->` lambda rather than a `() ->` one so the manager lookup stays in [collect],
     * where it already was.
     */
    private val sensorList: (SensorManager) -> List<Sensor> = { it.getSensorList(Sensor.TYPE_ALL) }
) {

    fun collect(): Pair<List<SensorFacts>, List<SpecField>> {
        val fields = mutableListOf<SpecField>()
        val facts = mutableListOf<SensorFacts>()

        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return emptyList<SensorFacts>() to listOf(
                FieldBuilder.unsupported(
                    "sensor.list", "Sensors", SpecGroup.SENSOR, "SensorManager",
                    "The sensor service is unavailable on this device."
                )
            )

        var enumerationFailure: String? = null
        val sensors = try {
            sensorList(manager)
        } catch (e: Exception) {
            enumerationFailure = e.message ?: e.javaClass.simpleName
            emptyList()
        }

        sensors.forEach { sensor ->
            facts += SensorFacts(
                name = sensor.name ?: "Unnamed sensor",
                vendor = sensor.vendor ?: "Unknown",
                version = sensor.version,
                type = sensor.type,
                stringType = sensor.stringType ?: "unknown",
                category = categorise(sensor),
                maxRange = sensor.maximumRange,
                resolution = sensor.resolution,
                powerMilliAmp = sensor.power,
                wakeUpSensor = sensor.isWakeUpSensor,
                reportingMode = when (sensor.reportingMode) {
                    Sensor.REPORTING_MODE_CONTINUOUS -> "Continuous"
                    Sensor.REPORTING_MODE_ON_CHANGE -> "On change"
                    Sensor.REPORTING_MODE_ONE_SHOT -> "One shot"
                    Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "Special trigger"
                    else -> "Unknown"
                }
            )
        }

        fields += if (enumerationFailure == null) {
            FieldBuilder.value(
                "sensor.count", "Sensors enumerated", logicalSensorCount(facts).toString(),
                SpecGroup.SENSOR, "SensorManager.getSensorList(TYPE_ALL)"
            )
        } else {
            // A refused enumeration is not a device with no sensors: the row reports the read
            // failure instead of "0", which is a measurement it never took. The seven category
            // rows below keep their wording, since "not reported" is still true.
            FieldBuilder.value(
                "sensor.count", "Sensors enumerated", null, SpecGroup.SENSOR,
                "SensorManager.getSensorList(TYPE_ALL)",
                note = "Unreadable: sensor enumeration failed: $enumerationFailure"
            )
        }

        val grouped = facts.groupBy { it.category }
        val expected = listOf("Accelerometer", "Gyroscope", "Magnetometer", "Proximity", "Light", "Barometer", "Step counter")
        expected.forEach { category ->
            val matches = grouped[category].orEmpty()
            fields += if (matches.isEmpty()) {
                FieldBuilder.value(
                    "sensor.$category", category, null, SpecGroup.SENSOR, "SensorManager",
                    note = "Not reported by the sensor service."
                )
            } else {
                FieldBuilder.value(
                    "sensor.$category", category,
                    matches.joinToString("; ") { "${it.name} (${it.vendor})" },
                    SpecGroup.SENSOR, "SensorManager", Confidence.HIGH,
                    note = "Range ${String.format(Locale.US, "%.2f", matches.first().maxRange)}, " +
                        "resolution ${String.format(Locale.US, "%.4f", matches.first().resolution)}, " +
                        "reporting ${matches.first().reportingMode.lowercase(Locale.US)}"
                )
            }
        }

        facts.distinctBy { it.stringType }.sortedBy { it.category }.forEachIndexed { index, sensor ->
            fields += FieldBuilder.value(
                "sensor.detail.$index", sensor.name,
                "${sensor.vendor} · v${sensor.version} · ${sensor.reportingMode}",
                SpecGroup.SENSOR, "SensorManager", Confidence.HIGH,
                note = "Type ${sensor.stringType}, power ${String.format(Locale.US, "%.2f", sensor.powerMilliAmp)} mA" +
                    if (sensor.wakeUpSensor) ", wake-up sensor" else ""
            )
        }

        return facts to fields
    }

    private fun categorise(sensor: Sensor): String = when (sensor.type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
        Sensor.TYPE_PROXIMITY -> "Proximity"
        Sensor.TYPE_LIGHT -> "Light"
        Sensor.TYPE_PRESSURE -> "Barometer"
        Sensor.TYPE_STEP_COUNTER -> "Step counter"
        Sensor.TYPE_STEP_DETECTOR -> "Step detector"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear acceleration"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant motion"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "Humidity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient temperature"
        Sensor.TYPE_HEART_RATE -> "Heart rate"
        Sensor.TYPE_HINGE_ANGLE -> "Hinge angle"
        else -> when {
            sensor.stringType.contains("accelerometer", true) -> "Accelerometer"
            sensor.stringType.contains("gyroscope", true) -> "Gyroscope"
            sensor.stringType.contains("magnet", true) -> "Magnetometer"
            sensor.stringType.contains("proximity", true) -> "Proximity"
            sensor.stringType.contains("light", true) -> "Light"
            else -> "Other"
        }
    }
}
