package com.verifydeviceinfo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.Finding
import com.verifydeviceinfo.core.model.RiskLevel
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.ConfidenceText
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.components.StatusChip
import com.verifydeviceinfo.ui.theme.DiagnosticColors
import com.verifydeviceinfo.ui.theme.statusColor

@Composable
fun AuthenticityScreen(state: UiState, onBack: () -> Unit) {
    val report = state.report
    AppScreen(title = "Authenticity", onBack = onBack) {
        if (report == null) {
            EmptyState("Run a scan first.")
            return@AppScreen
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val riskColor = when (report.authenticity.riskLevel) {
                RiskLevel.LOW -> DiagnosticColors.Verified
                RiskLevel.MODERATE, RiskLevel.ELEVATED -> DiagnosticColors.Warning
                RiskLevel.HIGH -> DiagnosticColors.Failed
                RiskLevel.UNKNOWN -> DiagnosticColors.Unverified
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(riskColor.copy(alpha = 0.12f))
                    .padding(16.dp)
            ) {
                Text(
                    "Risk level: ${report.authenticity.riskLevel.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = riskColor,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(report.authenticity.headline, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Reference data used") {
                Text(
                    report.authenticity.referenceSourceSummary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A matching model name does not prove authenticity. Reference data is a comparison " +
                        "baseline; regional variants that are missing from the set can produce false " +
                        "mismatches.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("Findings", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            report.authenticity.findings.forEach { finding ->
                FindingCard(finding)
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(8.dp))
            SectionCard(title = "How to read this") {
                Text(
                    "Every finding lists the evidence behind it and how confident the app is. Warnings are " +
                        "indicators that justify a closer look — they are not proof of counterfeit hardware. " +
                        "UNVERIFIED means the app could not compare, which is a coverage limit of this app, " +
                        "not a fault in the device.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun FindingCard(finding: Finding) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                finding.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            StatusChip(finding.status)
        }
        Spacer(Modifier.height(6.dp))
        Text(finding.explanation, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        finding.evidence.forEach { evidence ->
            Row {
                Text(
                    "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(finding.status)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${evidence.description}  (${evidence.source.name})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ConfidenceText(finding.confidence)
    }
}
