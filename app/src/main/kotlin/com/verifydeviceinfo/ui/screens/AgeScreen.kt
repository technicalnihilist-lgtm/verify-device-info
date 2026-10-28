package com.verifydeviceinfo.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.util.Formatters
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.theme.DiagnosticColors
import java.util.Calendar

@Composable
fun AgeScreen(state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val report = state.report
    val context = LocalContext.current
    AppScreen(title = "Device age", onBack = onBack) {
        if (report == null) {
            EmptyState("Run a scan first.")
            return@AppScreen
        }
        val age = report.age

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionCard(
                title = "Age sources",
                subtitle = "Each estimate is reported separately. A model's launch date is not this " +
                    "handset's manufacturing date."
            ) {
                TimelineItem(
                    title = "Model launch",
                    value = age.modelLaunchDate ?: "Unknown",
                    detail = "Model age ${Formatters.duration(age.modelLaunchAgeDays)} — describes the model, " +
                        "not this unit.",
                    active = age.modelLaunchDate != null
                )
                TimelineItem(
                    title = "Firmware build",
                    value = age.firmwareBuildDate ?: "Unknown",
                    detail = "Installed system image is ${Formatters.duration(age.firmwareBuildAgeDays)} old. " +
                        "The handset cannot be older than this image, but it may be newer.",
                    active = age.firmwareBuildDate != null
                )
                TimelineItem(
                    title = "Ownership",
                    value = age.purchaseDateUsed ?: "No purchase date set",
                    detail = if (age.ownershipAgeDays != null) {
                        "Owned for ${Formatters.duration(age.ownershipAgeDays)}."
                    } else {
                        "Enter the purchase date to compute ownership age. It is never guessed."
                    },
                    active = age.ownershipAgeDays != null
                )
                TimelineItem(
                    title = "Estimated usage",
                    value = Formatters.duration(age.estimatedUsageAgeDays),
                    detail = "Confidence: ${age.confidence.name.lowercase()}.",
                    active = age.estimatedUsageAgeDays != null
                )
                TimelineItem(
                    title = "Security patch",
                    value = age.securityPatchDate ?: "Unknown",
                    detail = "Last patched ${Formatters.duration(age.daysSinceSecurityPatch)} ago.",
                    active = age.securityPatchDate != null
                )
            }

            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(
                    onClick = {
                        val calendar = Calendar.getInstance()
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                val picked = Calendar.getInstance().apply {
                                    set(year, month, day, 0, 0, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                viewModel.setPurchaseDate(picked.timeInMillis)
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    }
                ) { Text("Set purchase date") }
                Spacer(Modifier.width(8.dp))
                if (age.purchaseDateUsed != null) {
                    OutlinedButton(onClick = { viewModel.setPurchaseDate(null) }) {
                        Text("Clear")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Notes and limitations") {
                age.notes.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(title: String, value: String, detail: String, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .width(12.dp)
                    .height(12.dp)
                    .clip(CircleShape)
                    .background(if (active) DiagnosticColors.Accent else DiagnosticColors.NotSupported)
            )
            Box(
                Modifier
                    .width(2.dp)
                    .height(46.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
