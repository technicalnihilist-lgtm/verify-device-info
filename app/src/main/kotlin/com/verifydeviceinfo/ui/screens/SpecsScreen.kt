package com.verifydeviceinfo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.SpecRow

@Composable
fun SpecsScreen(state: UiState, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var showInaccessibleOnly by remember { mutableStateOf(false) }
    val snapshot = state.snapshot

    AppScreen(title = "Specifications A–Z", onBack = onBack) {
        if (snapshot == null) {
            EmptyState("Run a scan first.")
            return@AppScreen
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search ${snapshot.fields.size} fields") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.FilterChip(
                selected = showInaccessibleOnly,
                onClick = { showInaccessibleOnly = !showInaccessibleOnly },
                label = { Text("Only unverifiable", style = MaterialTheme.typography.labelMedium) }
            )
            Spacer(Modifier.height(0.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
            Text(
                "Sorted by group",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))

        val filtered = snapshot.fields
            .filter { field ->
                val matchesQuery = query.isBlank() ||
                    field.label.contains(query, ignoreCase = true) ||
                    field.value.contains(query, ignoreCase = true) ||
                    field.key.contains(query, ignoreCase = true)
                val matchesFilter = !showInaccessibleOnly ||
                    field.status == DiagnosticStatus.NOT_SUPPORTED ||
                    field.status == DiagnosticStatus.UNVERIFIED ||
                    field.status == DiagnosticStatus.PERMISSION_REQUIRED
                matchesQuery && matchesFilter
            }
            .sortedWith(compareBy<SpecField>({ it.group.ordinal }, { it.label }))

        LazyColumn(Modifier.fillMaxWidth()) {
            var lastGroup: SpecGroup? = null
            filtered.forEach { field ->
                if (field.group != lastGroup) {
                    lastGroup = field.group
                    item(key = "header-${field.group.name}-${field.key}") {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                field.group.name.replace('_', ' '),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            HorizontalDivider(
                                Modifier.padding(top = 4.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
                item(key = field.key) { SpecRow(field) }
            }
            if (filtered.isEmpty()) {
                item { EmptyState("No field matches \"$query\".") }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
