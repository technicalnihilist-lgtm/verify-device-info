package com.verifydeviceinfo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.DiagnosticTestDescriptor
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.Screen
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.components.StatusChip
import com.verifydeviceinfo.ui.components.SpecRow

@Composable
fun DiagnosticsScreen(
    state: UiState,
    viewModel: AppViewModel,
    navigate: (Screen) -> Unit,
    onBack: () -> Unit
) {
    val tests = viewModel.container.diagnosticsEngine.availableTests()
    var showAutomated by remember { mutableStateOf(true) }
    val automated = state.session.results.filter { it.testId.startsWith("auto_") || it.testId == "charging" }

    AppScreen(title = "Diagnostics", onBack = onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SectionCard(
                    title = "Automated checks",
                    subtitle = "Passive checks that need no permission and no interaction. Each one states " +
                        "exactly what it measured."
                ) {
                    Button(onClick = { viewModel.runAutomatedDiagnostics() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Run automated checks")
                    }
                    if (automated.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        automated.forEach { result ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(result.title, style = MaterialTheme.typography.bodyMedium)
                                    result.measured?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                StatusChip(result.status)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionCard(
                    title = "Interactive tests",
                    subtitle = "These need your eyes, ears or fingers. They are never auto-passed, and a " +
                        "skipped test stays unverified."
                ) {
                    Text(
                        "Results are recorded against this scan and included in the report.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            val grouped = tests.groupBy { it.category }
            grouped.forEach { (category, categoryTests) ->
                item(key = "cat-$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                    )
                }
                items(categoryTests, key = { it.id }) { test ->
                    TestRow(
                        test = test,
                        statusLabel = state.session.resultFor(test.id)?.status,
                        onClick = { navigate(Screen.Test(test.id)) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TestRow(
    test: DiagnosticTestDescriptor,
    statusLabel: com.verifydeviceinfo.core.model.DiagnosticStatus?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(test.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                test.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (test.permission != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Requires permission: ${test.permission.name.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (statusLabel != null) {
            StatusChip(statusLabel)
        } else {
            Text("Run", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
