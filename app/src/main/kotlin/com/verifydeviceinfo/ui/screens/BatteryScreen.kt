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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.ScoreBand
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.KeyValueRow
import com.verifydeviceinfo.ui.components.ScoreRing
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.components.SpecRow

@Composable
fun BatteryScreen(state: UiState, onBack: () -> Unit) {
    val snapshot = state.snapshot
    AppScreen(title = "Battery and performance", onBack = onBack) {
        if (snapshot == null) {
            EmptyState("Run a scan first.")
            return@AppScreen
        }
        val battery = snapshot.battery
        val score = state.report?.scores?.byName("Battery condition")
        val performance = state.report?.scores?.byName("Performance")

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionCard(title = "Condition") {
                ScoreRing(
                    score = score?.score,
                    band = score?.band ?: ScoreBand.UNKNOWN,
                    label = "Battery",
                    size = 110
                )
                Spacer(Modifier.height(10.dp))
                Text(score?.summary ?: "No battery score was computed.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Coverage: ${score?.coveragePercent ?: 0}% of inputs were readable. " +
                        "Unknown inputs are excluded rather than scored as zero.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Live readings") {
                KeyValueRow("Charge level", battery.percent?.let { "$it%" } ?: "Unavailable")
                KeyValueRow("Health", battery.health)
                KeyValueRow(
                    "Temperature",
                    battery.temperatureCelsius?.let { String.format(java.util.Locale.US, "%.1f °C", it) }
                        ?: "Unavailable"
                )
                KeyValueRow("Voltage", battery.voltageMillivolts?.let { "$it mV" } ?: "Unavailable")
                KeyValueRow("Technology", battery.technology ?: "Unavailable")
                KeyValueRow("Status", battery.status)
                KeyValueRow("Power source", battery.plugged)
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(
                title = "Capacity and wear",
                subtitle = "Cycle count and design capacity are only available on some devices."
            ) {
                KeyValueRow(
                    "Design capacity",
                    battery.designCapacityMah?.let { "$it mAh" } ?: "Not exposed"
                )
                KeyValueRow(
                    "Charge counter",
                    battery.chargeCounterMicroAmpHours?.let {
                        String.format(java.util.Locale.US, "%.0f mAh", it / 1000.0)
                    } ?: "Not exposed"
                )
                KeyValueRow(
                    "Estimated capacity",
                    battery.healthPercent?.let { "$it% of design" } ?: "Not measurable now"
                )
                KeyValueRow("Charge cycles", battery.cycleCount?.toString() ?: "Not exposed")
                Spacer(Modifier.height(8.dp))
                Text(
                    "The estimated capacity compares the charge counter at full charge with the platform's " +
                        "design capacity. Charge to 100% for the most meaningful reading; it is still an " +
                        "estimate, not a laboratory measurement.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Performance") {
                ScoreRing(
                    score = performance?.score,
                    band = performance?.band ?: ScoreBand.UNKNOWN,
                    label = "Performance",
                    size = 96
                )
                Spacer(Modifier.height(8.dp))
                performance?.inputs?.forEach { input ->
                    KeyValueRow(input.label, input.value)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Free memory and thermal state change second to second; this is a point-in-time reading.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Battery fields") {
                snapshot.fieldsIn(com.verifydeviceinfo.core.model.SpecGroup.BATTERY).forEach { SpecRow(it) }
            }
        }
    }
}
