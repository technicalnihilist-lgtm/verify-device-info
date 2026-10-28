package com.verifydeviceinfo.ui.screens.tests

import android.hardware.Sensor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.data.android.diagnostics.SensorProbe
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.KeyValueRow
import com.verifydeviceinfo.ui.components.SectionCard
import java.util.Locale
import kotlin.math.abs

@Composable
fun SensorTestScreen(testId: String, viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val probe = remember { SensorProbe(context) }

    val sensorType = when (testId) {
        "sensor_gyroscope" -> Sensor.TYPE_GYROSCOPE
        "sensor_proximity" -> Sensor.TYPE_PROXIMITY
        "sensor_light" -> Sensor.TYPE_LIGHT
        "sensor_compass" -> Sensor.TYPE_MAGNETIC_FIELD
        else -> Sensor.TYPE_ACCELEROMETER
    }
    val title = when (testId) {
        "sensor_gyroscope" -> "Gyroscope"
        "sensor_proximity" -> "Proximity sensor"
        "sensor_light" -> "Ambient light sensor"
        "sensor_compass" -> "Compass (magnetometer)"
        else -> "Accelerometer"
    }
    val unit = when (testId) {
        "sensor_gyroscope" -> "rad/s"
        "sensor_proximity" -> "cm"
        "sensor_light" -> "lx"
        "sensor_compass" -> "µT"
        else -> "m/s²"
    }
    val instruction = when (testId) {
        "sensor_gyroscope" -> "Rotate the device in each axis. The values should change smoothly."
        "sensor_proximity" -> "Cover the top of the screen with your hand. The reading should change."
        "sensor_light" -> "Cover and uncover the screen. The lux value should rise and fall."
        "sensor_compass" -> "Move the device in a figure-eight, away from metal and magnets."
        else -> "Tilt the device in every direction. Gravity should stay near 9.8 m/s² on one axis."
    }

    val exists = remember { probe.hasSensor(sensorType) }
    var values by remember { mutableStateOf<FloatArray?>(null) }
    var minValue by remember { mutableStateOf<Float?>(null) }
    var maxValue by remember { mutableStateOf<Float?>(null) }
    var readingCount by remember { mutableStateOf(0) }
    var responded by remember { mutableStateOf(false) }

    DisposableEffect(testId) {
        val started = probe.start(sensorType) { reading ->
            values = reading
            readingCount += 1
            val magnitude = reading.firstOrNull() ?: 0f
            val currentMin = minValue
            val currentMax = maxValue
            if (currentMin == null || magnitude < currentMin) minValue = magnitude
            if (currentMax == null || magnitude > currentMax) maxValue = magnitude
            if (currentMin != null && currentMax != null &&
                abs(magnitude - currentMin!!) > 0.05f && abs(magnitude - currentMax!!) > 0.05f
            ) {
                responded = true
            }
            if (readingCount > 6) {
                val spread = (maxValue ?: 0f) - (minValue ?: 0f)
                if (spread > 0.05f) responded = true
            }
        }
        onDispose {
            probe.stop()
            if (!started) Unit
        }
    }

    AppScreen(title = title, onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            if (!exists) {
                SectionCard(title = "Sensor not present") {
                    Text(
                        "The sensor service did not report a $title. On a smartphone or tablet this is " +
                            "abnormal and usually indicates a hardware or firmware fault rather than a " +
                            "replaced part on its own.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = DiagnosticStatus.FAILED,
                                note = "The sensor was not reported by the sensor service."
                            )
                        )
                        onBack()
                    }) { Text("Record as failed") }
                }
                return@AppScreen
            }

            SectionCard(title = "Live readings", subtitle = instruction) {
                values?.let { reading ->
                    reading.forEachIndexed { index, value ->
                        KeyValueRow(
                            "Axis ${('X' + index)}",
                            String.format(Locale.US, "%.4f %s", value, unit)
                        )
                    }
                } ?: Text("Waiting for the first reading…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Readings received", readingCount.toString())
                KeyValueRow(
                    "Range observed",
                    if (minValue == null) "—" else String.format(
                        Locale.US, "%.3f … %.3f %s", minValue!!, maxValue!!, unit
                    )
                )
                KeyValueRow("Responds to input", if (responded) "Yes" else "Not yet")
            }

            Spacer(Modifier.height(10.dp))
            SectionCard(title = "Note") {
                Text(
                    "A sensor that is listed but never reports is a real fault. A sensor that reports a " +
                        "constant value while you move the device is also a fault. Only you can confirm that " +
                        "the values match the physical movement.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = DiagnosticStatus.PASS,
                                measured = "${readingCount} readings, responded=${responded}",
                                note = "Confirmed by the user."
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Works") }
                Spacer(Modifier.padding(start = 8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = DiagnosticStatus.FAILED,
                                measured = "${readingCount} readings, responded=${responded}",
                                note = "The user reported that this sensor does not respond correctly."
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Faulty") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
