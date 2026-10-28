package com.verifydeviceinfo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.util.Formatters
import com.verifydeviceinfo.ui.Sharing
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.KeyValueRow
import com.verifydeviceinfo.ui.components.SectionCard

@Composable
fun ReportScreen(state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val report = state.report
    val context = LocalContext.current
    AppScreen(title = "Report and export", onBack = onBack) {
        if (report == null) {
            EmptyState("Run a scan first.")
            return@AppScreen
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionCard(
                title = "Contents",
                subtitle = "Generated ${Formatters.date(report.generatedAtMillis)} · app ${report.appVersion}"
            ) {
                listOf(
                    "Device identification",
                    "Operating system and build",
                    "${report.snapshot.fields.size} specifications with source, status and confidence",
                    "${report.session.results.size} diagnostic test results",
                    "${report.authenticity.findings.size} authenticity findings with evidence",
                    "${report.parts.components.size} component assessments",
                    "Device age estimates with their sources",
                    "${report.integrity.checks.size} integrity checks",
                    "${report.scores.scores.size} health scores with inputs and coverage",
                    "Every field the app could not verify, with the reason"
                ).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Privacy") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Redact sensitive identifiers", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Serial number, Bluetooth name and signing-certificate digest are replaced " +
                                "with REDACTED. On by default.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.redacted, onCheckedChange = { viewModel.setRedacted(it) })
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.exportPdf() },
                    modifier = Modifier.weight(1f)
                ) { Text("Export PDF") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { viewModel.exportJson() },
                    modifier = Modifier.weight(1f)
                ) { Text("Export JSON") }
            }

            state.exportFile?.let { file ->
                Spacer(Modifier.height(12.dp))
                SectionCard(title = "Ready to share") {
                    KeyValueRow("File", file.name)
                    KeyValueRow("Size", Formatters.bytes(file.length()))
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val mime = if (file.name.endsWith(".pdf")) "application/pdf" else "application/json"
                            Sharing.share(context, file, mime)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Review and share") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sharing opens the system share sheet so you choose the destination. Nothing is " +
                            "uploaded by this app.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "What this report cannot tell you") {
                Text(
                    "It cannot prove that physical components are genuine. It cannot see part serial " +
                        "numbers, service records or manufacturing dates, because Android does not expose " +
                        "them to apps. It cannot detect every replaced part or every defect. A clean report " +
                        "means 'nothing inconsistent was found in the data available', and nothing more.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
