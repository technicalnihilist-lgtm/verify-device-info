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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.ComponentAssessment
import com.verifydeviceinfo.core.model.ComponentState
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.ConfidenceText
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.theme.DiagnosticColors

@Composable
fun PartsScreen(state: UiState, onBack: () -> Unit) {
    val report = state.report
    AppScreen(title = "Parts and repairs", onBack = onBack) {
        if (report == null) {
            EmptyState("Run a scan first.")
            return@AppScreen
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionCard(
                title = "Five-state classification",
                subtitle = "Each component is placed in exactly one of these states. " +
                    "'Unable to inspect' is not the same as 'counterfeit'."
            ) {
                ComponentState.entries.forEach { componentState ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(
                            Modifier
                                .width(10.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(stateColor(componentState))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            componentState.name.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            report.parts.components.forEach { component ->
                ComponentCard(component)
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(8.dp))
            SectionCard(title = "Why this is limited") {
                report.parts.limitations.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun ComponentCard(component: ComponentAssessment) {
    val color = stateColor(component.state)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
    ) {
        Text(component.component, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            component.state.name.replace('_', ' '),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(component.explanation, style = MaterialTheme.typography.bodySmall)
        if (component.evidence.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            component.evidence.forEach { evidence ->
                Text(
                    "• ${evidence.description}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ConfidenceText(component.confidence)
    }
}

internal fun stateColor(state: ComponentState): Color = when (state) {
    ComponentState.ORIGINALITY_VERIFIED -> DiagnosticColors.Verified
    ComponentState.POSSIBLE_REPLACEMENT -> DiagnosticColors.Warning
    ComponentState.POSSIBLE_COUNTERFEIT -> DiagnosticColors.Failed
    ComponentState.NO_ANOMALY_UNVERIFIED -> DiagnosticColors.Unverified
    ComponentState.UNABLE_TO_INSPECT -> DiagnosticColors.NotSupported
}
