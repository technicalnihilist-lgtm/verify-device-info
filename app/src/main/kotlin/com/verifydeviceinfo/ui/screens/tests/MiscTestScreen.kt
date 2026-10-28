package com.verifydeviceinfo.ui.screens.tests

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.verifydeviceinfo.core.model.AppPermission
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.PermissionState
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.KeyValueRow
import com.verifydeviceinfo.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * Tests whose outcome is a state read rather than a rendered surface: radios, charging,
 * vibration, GNSS and biometric availability.
 */
@Composable
fun MiscTestScreen(
    testId: String,
    state: UiState,
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = state.snapshot
    var note by remember { mutableStateOf<String?>(null) }
    var gpsResult by remember { mutableStateOf<String?>(null) }

    val title = when (testId) {
        "vibration" -> "Vibration motor"
        "wifi" -> "Wi-Fi"
        "bluetooth" -> "Bluetooth"
        "nfc" -> "NFC"
        "gps" -> "Location and GNSS"
        "charging" -> "Charging and battery temperature"
        "biometric" -> "Biometric authentication"
        "thermal" -> "Thermal state"
        "memory" -> "Memory and storage"
        else -> "Test"
    }

    AppScreen(title = title, onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            when (testId) {
                "vibration" -> SectionCard(
                    title = "Fire the vibration motor",
                    subtitle = "Uses the platform vibration API with a short pattern."
                ) {
                    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                            ?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    }
                    Button(
                        onClick = {
                            try {
                                if (vibrator?.hasVibrator() == true) {
                                    if (Build.VERSION.SDK_INT >= 26) {
                                        vibrator.vibrate(
                                            VibrationEffect.createWaveform(
                                                longArrayOf(0, 250, 150, 250, 150, 400), -1
                                            )
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        vibrator.vibrate(longArrayOf(0, 250, 150, 250), -1)
                                    }
                                    note = "Pattern sent to the vibration service."
                                } else {
                                    note = "No vibration motor was reported by the platform."
                                }
                            } catch (e: Exception) {
                                note = "Vibration failed: ${e.message}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Vibrate") }
                    KeyValueRow("Motor reported", if (snapshot?.hardware?.vibratorAvailable == true) "Yes" else "No")
                }

                "gps" -> SectionCard(
                    title = "Location fix",
                    subtitle = "Requests the location permission and reads the last known fix. " +
                        "No location is stored or transmitted."
                ) {
                    Button(onClick = {
                        scope.launch {
                            val granted = viewModel.container.permissionManager
                                .request(AppPermission.LOCATION)
                            if (granted != PermissionState.GRANTED) {
                                gpsResult = "Location permission was not granted, so no fix could be read."
                                return@launch
                            }
                            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasPermission || manager == null) {
                                gpsResult = "The location service is unavailable."
                                return@launch
                            }
                            val location = try {
                                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                            } catch (e: SecurityException) {
                                null
                            }
                            gpsResult = if (location != null) {
                                "Last known fix: ${String.format(java.util.Locale.US, "%.4f, %.4f", location.latitude, location.longitude)} " +
                                    "(accuracy ${location.accuracy.toInt()} m)"
                            } else {
                                "No cached fix. Step outside with a clear view of the sky and retry — an " +
                                    "empty cache is not evidence of a faulty GNSS receiver."
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Check location fix") }
                    gpsResult?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    KeyValueRow("GNSS hardware", if (snapshot?.hardware?.gnssAvailable == true) "Present" else "Not reported")
                }

                "biometric" -> SectionCard(
                    title = "Biometric availability",
                    subtitle = "The platform reports whether biometric hardware exists and is enrolled. " +
                        "This app never reads a template, image or identifier."
                ) {
                    val biometricFields = snapshot
                        ?.fieldsIn(com.verifydeviceinfo.core.model.SpecGroup.BIOMETRIC).orEmpty()
                    if (biometricFields.isEmpty()) {
                        Text("Run a scan to read the biometric state.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        biometricFields.forEach { KeyValueRow(it.label, it.value) }
                    }
                }

                "thermal" -> SectionCard(title = "Thermal state") {
                    KeyValueRow("Current status", snapshot?.software?.thermalStatus ?: "Unknown")
                    KeyValueRow("Supported", if (snapshot?.software?.thermalStatusSupported == true) "Yes" else "No")
                }

                "memory" -> SectionCard(title = "Memory and storage") {
                    KeyValueRow(
                        "Available RAM",
                        snapshot?.hardware?.ramAvailableBytes?.let { com.verifydeviceinfo.core.util.Formatters.bytes(it) }
                            ?: "Unknown"
                    )
                    KeyValueRow(
                        "Free storage",
                        snapshot?.hardware?.storageAvailableBytes?.let { com.verifydeviceinfo.core.util.Formatters.bytes(it) }
                            ?: "Unknown"
                    )
                }

                else -> SectionCard(title = "Radio state") {
                    val hardware = snapshot?.hardware
                    val label = when (testId) {
                        "wifi" -> "Wi-Fi"
                        "bluetooth" -> "Bluetooth"
                        "nfc" -> "NFC"
                        else -> "Charging"
                    }
                    val present = when (testId) {
                        "wifi" -> hardware?.wifiAvailable == true
                        "bluetooth" -> hardware?.bluetoothAvailable == true
                        "nfc" -> hardware?.nfcAvailable == true
                        else -> true
                    }
                    KeyValueRow("$label hardware", if (present) "Present" else "Not reported")
                    snapshot?.fields?.filter {
                        it.key.startsWith("net.${testId}") || it.key == "net.${testId}State"
                    }?.forEach { KeyValueRow(it.label, it.value) }
                    if (testId == "charging") {
                        KeyValueRow("Battery temperature",
                            snapshot?.battery?.temperatureCelsius?.let {
                                String.format(java.util.Locale.US, "%.1f °C", it)
                            } ?: "Unavailable")
                        KeyValueRow("Status", snapshot?.battery?.status ?: "Unknown")
                        KeyValueRow("Power source", snapshot?.battery?.plugged ?: "Unknown")
                    }
                    note?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = DiagnosticStatus.PASS,
                                measured = gpsResult ?: note,
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
                                measured = gpsResult ?: note,
                                note = "The user reported a problem with this component."
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
