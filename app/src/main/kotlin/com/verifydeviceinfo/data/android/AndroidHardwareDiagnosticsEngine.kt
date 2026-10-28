package com.verifydeviceinfo.data.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.os.BatteryManager
import android.os.Build
import com.verifydeviceinfo.core.model.AppPermission
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.DiagnosticTestDescriptor
import com.verifydeviceinfo.core.util.Formatters
import com.verifydeviceinfo.data.android.diagnostics.SensorProbe
import com.verifydeviceinfo.domain.HardwareDiagnosticsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The catalogue of tests plus the subset that can be verified without user interaction.
 *
 * Automated checks only assert what they actually measured. Everything that requires eyes,
 * ears or a finger is listed as a manual test and is never auto-passed.
 */
class AndroidHardwareDiagnosticsEngine(
    private val context: Context,
    private val permissionManager: AndroidPermissionManager
) : HardwareDiagnosticsEngine {

    override fun availableTests(): List<DiagnosticTestDescriptor> = listOf(
        DiagnosticTestDescriptor(
            "touch", "Multi-touch and dead zones",
            "Draw across the whole panel to prove every region responds, and press with several " +
                "fingers to confirm the touch controller reports them.",
            "Display", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "display_colour", "Colour and uniformity",
            "Full-screen flat colours reveal tinting, banding and patchy backlight uniformity.",
            "Display", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "display_dead_pixels", "Dead and stuck pixels",
            "Cycles black, white, red, green and blue fields. Visual inspection cannot guarantee " +
                "that every defective sub-pixel is found.",
            "Display", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "display_brightness", "Brightness range",
            "Ramps brightness from minimum to maximum to check that the backlight responds across " +
                "its full range.",
            "Display", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "camera_rear", "Rear camera",
            "Opens a live preview from the main rear camera.",
            "Camera", AppPermission.CAMERA, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "camera_front", "Front camera",
            "Opens a live preview from the front camera.",
            "Camera", AppPermission.CAMERA, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "flashlight", "Flashlight",
            "Switches the rear flash on and off.",
            "Camera", AppPermission.CAMERA, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "speaker", "Loudspeaker",
            "Plays a reference tone through the main speaker.",
            "Audio", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "earpiece", "Earpiece",
            "Routes a tone to the earpiece receiver at low volume.",
            "Audio", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "microphone", "Microphone",
            "Records three seconds and reports the measured peak level, so you can confirm that the " +
                "microphone responds to sound.",
            "Audio", AppPermission.MICROPHONE, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "vibration", "Vibration motor",
            "Fires a short vibration pattern.",
            "Haptics", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "sensor_accelerometer", "Accelerometer",
            "Streams live acceleration values and checks that the vector responds to movement.",
            "Sensors", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "sensor_gyroscope", "Gyroscope",
            "Streams live rotation values and checks that rotation is reported.",
            "Sensors", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "sensor_proximity", "Proximity sensor",
            "Shows the live proximity reading so you can cover the sensor and watch it change.",
            "Sensors", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "sensor_light", "Ambient light sensor",
            "Shows the live lux reading so you can change the lighting and watch it respond.",
            "Sensors", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "sensor_compass", "Compass / magnetometer",
            "Shows the magnetic field vector and derived heading.",
            "Sensors", null, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "gps", "Location and GNSS",
            "Reports whether a location fix can be obtained. Requires the location permission.",
            "Connectivity", AppPermission.LOCATION, automated = false, requiresManualConfirmation = true
        ),
        DiagnosticTestDescriptor(
            "wifi", "Wi-Fi",
            "Confirms that the Wi-Fi radio is present and enabled.",
            "Connectivity", null, automated = false, requiresManualConfirmation = false
        ),
        DiagnosticTestDescriptor(
            "bluetooth", "Bluetooth",
            "Confirms that the Bluetooth radio is present and enabled.",
            "Connectivity", AppPermission.BLUETOOTH, automated = false, requiresManualConfirmation = false
        ),
        DiagnosticTestDescriptor(
            "nfc", "NFC",
            "Confirms that an NFC controller is present and enabled.",
            "Connectivity", null, automated = false, requiresManualConfirmation = false
        ),
        DiagnosticTestDescriptor(
            "charging", "Charging and battery temperature",
            "Reads the charging state, power source and battery temperature.",
            "Power", null, automated = false, requiresManualConfirmation = false
        ),
        DiagnosticTestDescriptor(
            "biometric", "Biometric authentication",
            "Checks that biometric hardware is present and enrolled. No template is ever read.",
            "Security", null, automated = false, requiresManualConfirmation = false
        )
    )

    override suspend fun runAutomatedChecks(): List<DiagnosticResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DiagnosticResult>()

        // Storage headroom
        try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val percentFree = if (total > 0) (free * 100 / total).toInt() else 0
            results += DiagnosticResult(
                testId = "auto_storage",
                title = "Storage headroom",
                status = when {
                    percentFree >= 15 -> DiagnosticStatus.PASS
                    percentFree >= 5 -> DiagnosticStatus.WARNING
                    else -> DiagnosticStatus.FAILED
                },
                measured = "$percentFree% free (${Formatters.bytes(free)} of ${Formatters.bytes(total)})",
                note = "Low free space degrades performance and can break updates."
            )
        } catch (e: Exception) {
            results += DiagnosticResult(
                "auto_storage", "Storage headroom", DiagnosticStatus.UNVERIFIED,
                note = "Could not read storage statistics: ${e.message}"
            )
        }

        // Memory headroom
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val percentFree = if (info.totalMem > 0) (info.availMem * 100 / info.totalMem).toInt() else 0
            results += DiagnosticResult(
                testId = "auto_memory",
                title = "Memory headroom",
                status = when {
                    percentFree >= 20 -> DiagnosticStatus.PASS
                    percentFree >= 10 -> DiagnosticStatus.WARNING
                    else -> DiagnosticStatus.FAILED
                },
                measured = "$percentFree% free (${Formatters.bytes(info.availMem)} of ${Formatters.bytes(info.totalMem)})"
            )
        } catch (e: Exception) {
            results += DiagnosticResult(
                "auto_memory", "Memory headroom", DiagnosticStatus.UNVERIFIED,
                note = "Could not read memory statistics: ${e.message}"
            )
        }

        // Thermal
        if (Build.VERSION.SDK_INT >= 29) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val status = powerManager.currentThermalStatus
            results += DiagnosticResult(
                testId = "auto_thermal",
                title = "Thermal state",
                status = when (status) {
                    android.os.PowerManager.THERMAL_STATUS_NONE,
                    android.os.PowerManager.THERMAL_STATUS_LIGHT -> DiagnosticStatus.PASS
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> DiagnosticStatus.WARNING
                    else -> DiagnosticStatus.FAILED
                },
                measured = when (status) {
                    android.os.PowerManager.THERMAL_STATUS_NONE -> "No throttling"
                    android.os.PowerManager.THERMAL_STATUS_LIGHT -> "Light throttling"
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Moderate throttling"
                    android.os.PowerManager.THERMAL_STATUS_SEVERE -> "Severe throttling"
                    android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                    android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                    else -> "Shutdown imminent"
                }
            )
        } else {
            results += DiagnosticResult(
                "auto_thermal", "Thermal state", DiagnosticStatus.NOT_SUPPORTED,
                note = "Thermal status reporting requires Android 10 (API 29) or newer."
            )
        }

        // Camera enumeration
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
        val cameraIds = try {
            cameraManager?.cameraIdList.orEmpty()
        } catch (e: Exception) {
            emptyArray()
        }
        results += DiagnosticResult(
            testId = "auto_camera_enum",
            title = "Camera enumeration",
            status = when {
                cameraIds.isEmpty() -> DiagnosticStatus.FAILED
                else -> DiagnosticStatus.PASS
            },
            measured = "${cameraIds.size} camera module(s) reported",
            note = if (cameraIds.isEmpty()) {
                "The camera service reported no modules. This is a strong indicator of a hardware fault."
            } else null
        )

        // Accelerometer liveness
        val probe = SensorProbe(context)
        val reading = probe.probeLiveness(Sensor.TYPE_ACCELEROMETER)
        results += DiagnosticResult(
            testId = "auto_sensor_liveness",
            title = "Accelerometer liveness",
            status = when {
                !probe.hasSensor(Sensor.TYPE_ACCELEROMETER) -> DiagnosticStatus.FAILED
                reading == null -> DiagnosticStatus.WARNING
                else -> DiagnosticStatus.PASS
            },
            measured = reading?.let {
                String.format(
                    Locale.US, "x=%.2f y=%.2f z=%.2f m/s²", it[0], it[1], it[2]
                )
            },
            note = when {
                !probe.hasSensor(Sensor.TYPE_ACCELEROMETER) ->
                    "No accelerometer is present, which is abnormal for a smartphone or tablet."
                reading == null ->
                    "The accelerometer is listed but produced no reading within 1.5 seconds."
                else -> null
            }
        )

        // Charging and battery temperature
        val batteryIntent: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            null
        }
        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10f)
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        results += DiagnosticResult(
            testId = "charging",
            title = "Charging and battery temperature",
            status = when {
                temperature == null -> DiagnosticStatus.UNVERIFIED
                temperature > 45f -> DiagnosticStatus.FAILED
                temperature > 40f -> DiagnosticStatus.WARNING
                else -> DiagnosticStatus.PASS
            },
            measured = listOfNotNull(
                temperature?.let { String.format(Locale.US, "%.1f °C", it) },
                voltage?.let { "$it mV" },
                if (plugged != 0) "external power connected" else "on battery"
            ).joinToString(", "),
            note = if (temperature != null && temperature > 40f) {
                "Elevated battery temperature. Let the device cool and re-check; persistent heat can " +
                    "indicate a failing cell or a faulty charging circuit."
            } else null
        )

        results
    }
}
