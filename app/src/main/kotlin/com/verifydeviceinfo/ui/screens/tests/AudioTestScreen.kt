package com.verifydeviceinfo.ui.screens.tests

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.AppPermission
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.PermissionState
import com.verifydeviceinfo.data.android.diagnostics.AudioProbe
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.KeyValueRow
import com.verifydeviceinfo.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun AudioTestScreen(
    testId: String,
    state: UiState,
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val probe = remember { AudioProbe(context) }
    var measuring by remember { mutableStateOf(false) }
    var measured by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val title = when (testId) {
        "speaker" -> "Loudspeaker"
        "earpiece" -> "Earpiece"
        else -> "Microphone"
    }

    DisposableEffect(Unit) { onDispose { probe.release() } }

    AppScreen(title = title, onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            when (testId) {
                "microphone" -> {
                    SectionCard(
                        title = "Record and measure",
                        subtitle = "Three seconds are recorded to a temporary file and then deleted. Only " +
                            "the measured level is kept."
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val granted = viewModel.container.permissionManager
                                        .request(AppPermission.MICROPHONE)
                                    if (granted != PermissionState.GRANTED) {
                                        note = "The microphone permission was not granted, so nothing was recorded."
                                        return@launch
                                    }
                                    measuring = true
                                    note = null
                                    measured = probe.measureMicrophone(3000)
                                    measuring = false
                                    if (measured == null) {
                                        note = "The recorder could not be started on this device."
                                    }
                                }
                            },
                            enabled = !measuring,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (measuring) "Recording…" else "Record 3 seconds") }

                        measured?.let { (peak, average) ->
                            Spacer(Modifier.height(10.dp))
                            KeyValueRow("Peak amplitude", "$peak / 32767")
                            KeyValueRow("Average amplitude", "$average / 32767")
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (peak < 200) {
                                    "Very low level. Speak close to the microphone and try again before " +
                                        "concluding that it is faulty."
                                } else {
                                    "The microphone responded to sound."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    SectionCard(
                        title = "Play a reference tone",
                        subtitle = if (testId == "earpiece") {
                            "A 440 Hz tone is played at media volume. On many devices the earpiece is only " +
                                "used during calls, so the tone may come from the main speaker — that is a " +
                                "routing behaviour, not a fault."
                        } else {
                            "A 440 Hz sine wave is generated and played at the current media volume."
                        }
                    ) {
                        Button(
                            onClick = { probe.playTone(440.0, 2500) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Play tone") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { probe.stopTone() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Stop") }
                    }
                }
            }

            note?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(10.dp))
            SectionCard(title = "Audio endpoints reported") {
                val audioFields = state.snapshot?.fieldsIn(com.verifydeviceinfo.core.model.SpecGroup.AUDIO).orEmpty()
                if (audioFields.isEmpty()) {
                    Text("Run a scan to see the reported audio endpoints.", style = MaterialTheme.typography.bodySmall)
                } else {
                    audioFields.take(6).forEach { field ->
                        KeyValueRow(field.label, field.value)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = DiagnosticStatus.PASS,
                                measured = measured?.let { "peak ${it.first}/32767" },
                                note = "Confirmed by the user."
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Works") }
                Spacer(Modifier.padding(start = 8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = DiagnosticStatus.FAILED,
                                measured = measured?.let { "peak ${it.first}/32767" },
                                note = "The user reported that this component does not work."
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Faulty") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
