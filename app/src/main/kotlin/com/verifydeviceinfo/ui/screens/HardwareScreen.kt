package com.verifydeviceinfo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.util.Formatters
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.KeyValueRow
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.components.SpecRow

@Composable
fun HardwareScreen(state: UiState, onBack: () -> Unit) {
    val snapshot = state.snapshot
    AppScreen(title = "Hardware explorer", onBack = onBack) {
        if (snapshot == null) {
            EmptyState("Run a scan first.")
            return@AppScreen
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val hardware = snapshot.hardware

            SectionCard(title = "Processor", subtitle = "From Build, the activity manager and kernel sysfs") {
                KeyValueRow("SoC manufacturer", snapshot.identity.socManufacturer ?: "Not exposed")
                KeyValueRow("SoC model", snapshot.identity.socModel ?: "Not exposed")
                KeyValueRow("Cores", hardware.cpuCores.toString())
                KeyValueRow(
                    "Max frequency",
                    hardware.cpuMaxFreqKhz?.let {
                        String.format(java.util.Locale.US, "%.2f GHz", it / 1_000_000.0)
                    } ?: "Not exposed"
                )
                KeyValueRow("ABIs", hardware.cpuAbis.joinToString(", ").ifBlank { "Unknown" })
                KeyValueRow("Hardware name", hardware.cpuHardware)
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Graphics") {
                KeyValueRow("GPU", hardware.gpuRenderer ?: "Not exposed")
                KeyValueRow("GPU vendor", hardware.gpuVendor ?: "Unknown")
                KeyValueRow("OpenGL ES", hardware.glesVersion ?: "Unknown")
                KeyValueRow("Vulkan", if (hardware.vulkanSupported == true) "Supported" else "Not reported")
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Memory and storage") {
                KeyValueRow("Total RAM", Formatters.bytes(hardware.ramTotalBytes))
                KeyValueRow("Available RAM", Formatters.bytes(hardware.ramAvailableBytes))
                KeyValueRow("Low-RAM device", if (hardware.isLowRamDevice) "Yes" else "No")
                KeyValueRow("Per-app heap", "${hardware.memoryClassMb} MB")
                KeyValueRow("Total storage", Formatters.bytes(hardware.storageTotalBytes))
                KeyValueRow("Free storage", Formatters.bytes(hardware.storageAvailableBytes))
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Display") {
                val display = hardware.display
                if (display == null) {
                    Text("Display configuration unavailable.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    KeyValueRow("Resolution", "${display.widthPx} × ${display.heightPx} px")
                    KeyValueRow("Density", "${display.densityDpi} dpi")
                    KeyValueRow(
                        "Physical size",
                        String.format(java.util.Locale.US, "%.2f in (derived)", display.diagonalInches)
                    )
                    KeyValueRow("Refresh rate", String.format(java.util.Locale.US, "%.0f Hz", display.refreshRateHz))
                    KeyValueRow(
                        "Supported rates",
                        display.supportedRefreshRates.joinToString(", ") {
                            String.format(java.util.Locale.US, "%.0f", it)
                        } + " Hz"
                    )
                    KeyValueRow("HDR", display.hdrTypes.joinToString(", ").ifBlank { "None reported" })
                    KeyValueRow("Wide colour", if (display.wideColorGamut) "Yes" else "Not reported")
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(
                title = "Cameras",
                subtitle = "${hardware.cameras.size} modules enumerated through Camera2"
            ) {
                if (hardware.cameras.isEmpty()) {
                    Text("No cameras were enumerated.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    hardware.cameras.forEach { camera ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                "${camera.lensFacing} · id ${camera.id}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                buildString {
                                    append(String.format(java.util.Locale.US, "%.1f MP", camera.megapixels))
                                    append(" · ${camera.hardwareLevel} level")
                                    if (camera.apertures.isNotEmpty()) {
                                        append(" · f/${camera.apertures.first()}")
                                    }
                                    if (camera.flashAvailable) append(" · flash")
                                    if (camera.physicalCameraIds.isNotEmpty()) {
                                        append(" · sub-cameras ${camera.physicalCameraIds.joinToString(",")}")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(
                title = "Sensors",
                subtitle = "${hardware.sensors.size} sensors reported by the sensor service"
            ) {
                hardware.sensors
                    .distinctBy { it.stringType }
                    .sortedBy { it.category }
                    .take(24)
                    .forEach { sensor ->
                        Column(Modifier.padding(vertical = 5.dp)) {
                            Text(sensor.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${sensor.vendor} · ${sensor.category} · ${sensor.reportingMode}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Radios and ports") {
                KeyValueRow("Wi-Fi", if (hardware.wifiAvailable) "Present" else "Not present")
                KeyValueRow("Bluetooth", if (hardware.bluetoothAvailable) "Present" else "Not present")
                KeyValueRow("NFC", if (hardware.nfcAvailable) "Present" else "Not present")
                KeyValueRow("GNSS", if (hardware.gnssAvailable) "Present" else "Not present")
                KeyValueRow("Cellular", if (hardware.telephonyAvailable) "Present" else "Not present")
                KeyValueRow("USB host", if (hardware.usbHostAvailable) "Supported" else "Not reported")
                KeyValueRow("Biometrics", if (hardware.biometricAnyAvailable) "Available" else "Not available")
                KeyValueRow("StrongBox", if (hardware.strongBoxAvailable) "Supported" else "Not reported")
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Audio and haptics") {
                KeyValueRow("Speaker", if (hardware.speakerAvailable) "Present" else "Not reported")
                KeyValueRow("Microphone", if (hardware.microphoneAvailable) "Present" else "Not reported")
                KeyValueRow("Vibration motor", if (hardware.vibratorAvailable) "Present" else "Not reported")
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Detailed display fields") {
                snapshot.fieldsIn(SpecGroup.DISPLAY).forEach { SpecRow(it) }
            }
        }
    }
}
