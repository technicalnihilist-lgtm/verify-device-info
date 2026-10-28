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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.HistoryEntry
import com.verifydeviceinfo.core.util.Formatters
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.MonospaceBlock
import com.verifydeviceinfo.ui.components.SectionCard

@Composable
fun HistoryScreen(state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    var expandedJson by remember { mutableStateOf<String?>(null) }

    AppScreen(title = "Diagnostic history", onBack = onBack) {
        if (state.history.isEmpty()) {
            EmptyState("No scans have been stored yet. Each scan is saved locally on this device only.")
            return@AppScreen
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { viewModel.refreshHistory() }) { Text("Refresh") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { viewModel.clearHistory() }) { Text("Clear history") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.history, key = { it.id }) { entry ->
                    HistoryCard(
                        entry = entry,
                        expanded = expandedId == entry.id,
                        json = if (expandedId == entry.id) expandedJson else null,
                        onClick = {
                            if (expandedId == entry.id) {
                                expandedId = null
                                expandedJson = null
                            } else {
                                expandedId = entry.id
                                expandedJson = null
                                viewModel.loadRawReport(entry.id) { expandedJson = it }
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    expanded: Boolean,
    json: String?,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(entry.modelLabel, style = MaterialTheme.typography.titleSmall)
        Text(
            Formatters.date(entry.timestampMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Risk ${entry.riskLevel.name} · ${entry.issueCount} issue(s) · " +
                "hardware ${entry.hardwareScore ?: "—"} · authenticity ${entry.authenticityScore ?: "—"}",
            style = MaterialTheme.typography.bodySmall
        )
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            if (json == null) {
                Text("Loading stored report…", style = MaterialTheme.typography.labelSmall)
            } else {
                SectionCard(title = "Stored report (redacted JSON)") {
                    MonospaceBlock(json)
                }
            }
        }
    }
}
