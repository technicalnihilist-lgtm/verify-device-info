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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.components.SpecRow
import com.verifydeviceinfo.ui.components.StatusChip
import com.verifydeviceinfo.ui.theme.statusColor

@Composable
fun SecurityScreen(state: UiState, onBack: () -> Unit) {
    val report = state.report
    val snapshot = state.snapshot
    AppScreen(title = "Security and software", onBack = onBack) {
        if (report == null || snapshot == null) {
            EmptyState("Run a scan first.")
            return@AppScreen
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(statusColor(report.integrity.overallStatus).copy(alpha = 0.12f))
                    .padding(14.dp)
            ) {
                Text(
                    report.integrity.overallStatus.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor(report.integrity.overallStatus)
                )
                Spacer(Modifier.height(4.dp))
                Text(report.integrity.summary, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            report.integrity.checks.forEach { check ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            check.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusChip(check.status)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(check.explanation, style = MaterialTheme.typography.bodySmall)
                    if (check.evidence.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        check.evidence.forEach {
                            Text(
                                "• ${it.description}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(
                title = "Installed software",
                subtitle = if (snapshot.software.packageVisibilityRestricted) {
                    "Android 11+ hides apps that are not declared in the manifest queries element, so this " +
                        "count is a lower bound."
                } else "Counted through PackageManager."
            ) {
                Text(
                    "${snapshot.software.installedPackageCount} packages visible · " +
                        "${snapshot.software.systemPackageCount} system · " +
                        "${snapshot.software.userPackageCount} user",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "This app does not enumerate other apps' contents, and does not request " +
                        "QUERY_ALL_PACKAGES.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(
                title = "Security fields",
                subtitle = "What the platform discloses, with status and confidence."
            ) {
                snapshot.fieldsIn(com.verifydeviceinfo.core.model.SpecGroup.SECURITY).forEach { SpecRow(it) }
                snapshot.fieldsIn(com.verifydeviceinfo.core.model.SpecGroup.BIOMETRIC).forEach { SpecRow(it) }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "What a clean result means") {
                Text(
                    "A passing integrity check shows that the checks this app performs found nothing. It does " +
                        "not prove that every physical component is genuine, and it cannot detect a " +
                        "well-hidden root. No security control was bypassed to produce these results.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                if (report.integrity.checks.any { it.status == DiagnosticStatus.NOT_SUPPORTED }) {
                    Text(
                        "Some checks are marked NOT SUPPORTED because the platform does not expose the " +
                            "information, or because the feature requires credentials this build does not ship.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
