package com.verifydeviceinfo.ui.screens.tests

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.SectionCard

private val testColors = listOf(
    "White" to Color.White,
    "Black" to Color.Black,
    "Red" to Color(0xFFFF0000),
    "Green" to Color(0xFF00FF00),
    "Blue" to Color(0xFF0000FF),
    "Grey" to Color(0xFF808080)
)

/**
 * Full-screen display tests. The panel is driven to known states so defects can be seen;
 * the app does not attempt to detect defects on the user's behalf.
 */
@Composable
fun DisplayTestScreen(testId: String, viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var colorIndex by remember { mutableIntStateOf(0) }
    var brightness by remember { mutableStateOf(1f) }
    var showField by remember { mutableStateOf(true) }

    val window = (context as? Activity)?.window
    val originalBrightness = remember { window?.attributes?.screenBrightness ?: -1f }

    DisposableEffect(Unit) {
        onDispose {
            window?.let {
                val attributes = it.attributes
                attributes.screenBrightness = originalBrightness
                it.attributes = attributes
            }
        }
    }

    val title = when (testId) {
        "display_colour" -> "Colour and uniformity"
        "display_dead_pixels" -> "Dead and stuck pixels"
        else -> "Brightness range"
    }

    AppScreen(title = title, onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            if (testId == "display_brightness") {
                Text("Brightness ${(brightness * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = brightness,
                    onValueChange = {
                        brightness = it
                        window?.let { w ->
                            val attributes = w.attributes
                            attributes.screenBrightness = it.coerceAtLeast(0.01f)
                            w.attributes = attributes
                        }
                    }
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(if (showField) testColors[colorIndex].second else MaterialTheme.colorScheme.surface)
                    .clickable { showField = !showField },
                contentAlignment = Alignment.Center
            ) {
                if (testId == "display_brightness") {
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
                if (!showField) {
                    Text(
                        "Tap to show the test field again",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (testId != "display_brightness") {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    testColors.forEachIndexed { index, pair ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .background(pair.second)
                                .clickable { colorIndex = index; showField = true }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionCard(title = "What to look for") {
                Text(
                    when (testId) {
                        "display_dead_pixels" ->
                            "Cycle through the flat fields and look for individual pixels that stay dark " +
                                "(dead) or fixed on one colour (stuck). Visual inspection cannot guarantee " +
                                "that every defective sub-pixel is found."
                        "display_brightness" ->
                            "Sweep the slider from minimum to maximum. The backlight should respond " +
                                "smoothly across the whole range."
                        else ->
                            "Look for tinting, banding, dark corners or patches where the backlight is " +
                                "uneven. Compare the grey field carefully — uniformity faults show up best there."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
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
                                note = "Confirmed by the user after visual inspection."
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Looks correct") }
                Spacer(Modifier.padding(start = 8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = DiagnosticStatus.FAILED,
                                note = "The user reported a visible defect."
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Defect seen") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
