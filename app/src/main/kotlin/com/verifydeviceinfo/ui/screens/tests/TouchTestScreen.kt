package com.verifydeviceinfo.ui.screens.tests

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.theme.DiagnosticColors
import kotlin.math.roundToInt

private const val COLUMNS = 12
private const val ROWS = 24

/**
 * Touch coverage test. Every cell you physically touch is marked, so dead zones stay visible as
 * untouched cells. The app never assumes a cell was tested.
 */
@Composable
fun TouchTestScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val touched = remember { mutableStateListOf<Int>() }
    var maxPointers by remember { mutableIntStateOf(0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val coverage = (touched.size * 100f / (COLUMNS * ROWS))

    AppScreen(title = "Multi-touch and dead zones", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Drag your finger over the whole panel. Use several fingers at once to check " +
                    "multi-touch. Untouched cells stay dark — they are not assumed to work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Coverage ${coverage.roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                Text("Max simultaneous touches: $maxPointers", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { canvasSize = it }
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size > maxPointers) maxPointers = pressed.size
                                if (canvasSize.width > 0 && canvasSize.height > 0) {
                                    pressed.forEach { change ->
                                        val column = (change.position.x / canvasSize.width * COLUMNS)
                                            .toInt().coerceIn(0, COLUMNS - 1)
                                        val row = (change.position.y / canvasSize.height * ROWS)
                                            .toInt().coerceIn(0, ROWS - 1)
                                        val index = row * COLUMNS + column
                                        if (!touched.contains(index)) touched.add(index)
                                    }
                                }
                            }
                        }
                    }
            ) {
                val cellWidth = size.width / COLUMNS
                val cellHeight = size.height / ROWS
                for (row in 0 until ROWS) {
                    for (column in 0 until COLUMNS) {
                        val index = row * COLUMNS + column
                        val filled = touched.contains(index)
                        drawRect(
                            color = if (filled) DiagnosticColors.Verified.copy(alpha = 0.75f)
                            else DiagnosticColors.Unverified.copy(alpha = 0.12f),
                            topLeft = Offset(column * cellWidth + 1f, row * cellHeight + 1f),
                            size = Size(cellWidth - 2f, cellHeight - 2f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionCard(title = "Record the result") {
                Text(
                    "Only you can judge whether the panel responded everywhere. Coverage of ${coverage.roundToInt()}% " +
                        "with $maxPointers simultaneous touches is what was measured.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            viewModel.recordResult(
                                DiagnosticResult(
                                    testId = "touch",
                                    title = "Multi-touch and dead zones",
                                    status = DiagnosticStatus.PASS,
                                    measured = "${coverage.roundToInt()}% of cells touched, " +
                                        "up to $maxPointers simultaneous touches",
                                    note = "Confirmed by the user after visual inspection."
                                )
                            )
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("All areas respond") }
                    Spacer(Modifier.height(0.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.recordResult(
                                DiagnosticResult(
                                    testId = "touch",
                                    title = "Multi-touch and dead zones",
                                    status = DiagnosticStatus.FAILED,
                                    measured = "${coverage.roundToInt()}% coverage",
                                    note = "The user reported an unresponsive area."
                                )
                            )
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Dead area found") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { touched.clear(); maxPointers = 0 },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reset grid") }
            }
        }
    }
}
