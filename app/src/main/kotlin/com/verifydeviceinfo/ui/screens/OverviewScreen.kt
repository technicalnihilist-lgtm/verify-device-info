package com.verifydeviceinfo.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.util.Formatters
import com.verifydeviceinfo.ui.Screen
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.KeyValueRow
import com.verifydeviceinfo.ui.components.MockDataBanner
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.components.SpecRow

@Composable
fun OverviewScreen(state: UiState, navigate: (Screen) -> Unit, onBack: () -> Unit) {
    val snapshot = state.snapshot
    AppScreen(title = "Device overview", onBack = onBack) {
        if (snapshot == null) {
            EmptyState("Run a scan from the dashboard to populate this page.")
            return@AppScreen
        }
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
        ) {
            if (snapshot.isMock) {
                MockDataBanner()
                Spacer(Modifier.height(12.dp))
            }

            SectionCard(title = "Identity") {
                KeyValueRow("Manufacturer", snapshot.identity.manufacturer)
                KeyValueRow("Brand", snapshot.identity.brand)
                KeyValueRow("Model", snapshot.identity.model)
                snapshot.identity.marketingName?.let { KeyValueRow("Marketing name", it) }
                KeyValueRow("Codename", snapshot.identity.device)
                KeyValueRow("Product", snapshot.identity.product)
                KeyValueRow("Hardware", snapshot.identity.hardware)
                KeyValueRow("Board", snapshot.identity.board)
                KeyValueRow(
                    "SoC",
                    listOfNotNull(snapshot.identity.socManufacturer, snapshot.identity.socModel)
                        .joinToString(" ").ifBlank { "Not exposed" }
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Software") {
                KeyValueRow("Android", "${snapshot.software.androidRelease} (API ${snapshot.software.apiLevel})")
                KeyValueRow("Security patch", snapshot.software.securityPatch ?: "Unknown")
                KeyValueRow("Build", snapshot.identity.buildId)
                KeyValueRow("Build type", snapshot.identity.buildType)
                KeyValueRow("Build tags", snapshot.identity.buildTags)
                KeyValueRow("Uptime", Formatters.uptime(snapshot.software.uptimeMillis))
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Key hardware") {
                val display = snapshot.hardware.display
                KeyValueRow(
                    "Display",
                    if (display != null) "${display.widthPx}×${display.heightPx} · ${display.densityDpi} dpi" else "Unknown"
                )
                KeyValueRow("Refresh rate", display?.let { "${it.refreshRateHz} Hz" } ?: "Unknown")
                KeyValueRow("RAM", Formatters.bytes(snapshot.hardware.ramTotalBytes))
                KeyValueRow("Storage", Formatters.bytes(snapshot.hardware.storageTotalBytes))
                KeyValueRow("CPU cores", snapshot.hardware.cpuCores.toString())
                KeyValueRow("Cameras", "${snapshot.hardware.cameras.size} modules")
                KeyValueRow("Sensors", "${snapshot.hardware.sensors.size} reported")
                KeyValueRow("Battery health", snapshot.battery.health)
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(
                title = "Headline facts",
                subtitle = "A few values with their provenance. The full list is on the specifications page."
            ) {
                snapshot.fields.take(6).forEach { field ->
                    SpecRow(field)
                }
                Text(
                    "Open All specifications for the complete, searchable list.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (snapshot.collectionErrors.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionCard(title = "Collection notes") {
                    snapshot.collectionErrors.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(message: String) {
    SectionCard(title = "Nothing to show yet") {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
