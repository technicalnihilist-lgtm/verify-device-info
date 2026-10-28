package com.verifydeviceinfo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verifydeviceinfo.ui.screens.AgeScreen
import com.verifydeviceinfo.ui.screens.AuthenticityScreen
import com.verifydeviceinfo.ui.screens.BatteryScreen
import com.verifydeviceinfo.ui.screens.DashboardScreen
import com.verifydeviceinfo.ui.screens.DiagnosticsScreen
import com.verifydeviceinfo.ui.screens.HardwareScreen
import com.verifydeviceinfo.ui.screens.HistoryScreen
import com.verifydeviceinfo.ui.screens.OverviewScreen
import com.verifydeviceinfo.ui.screens.PartsScreen
import com.verifydeviceinfo.ui.screens.ReportScreen
import com.verifydeviceinfo.ui.screens.SecurityScreen
import com.verifydeviceinfo.ui.screens.SpecsScreen
import com.verifydeviceinfo.ui.screens.tests.AudioTestScreen
import com.verifydeviceinfo.ui.screens.tests.CameraTestScreen
import com.verifydeviceinfo.ui.screens.tests.DisplayTestScreen
import com.verifydeviceinfo.ui.screens.tests.MiscTestScreen
import com.verifydeviceinfo.ui.screens.tests.SensorTestScreen
import com.verifydeviceinfo.ui.screens.tests.TouchTestScreen

/** Destination graph. Simple and explicit — no reflection, no route strings to mistype. */
sealed interface Screen {
    data object Dashboard : Screen
    data object Overview : Screen
    data object Specs : Screen
    data object Hardware : Screen
    data object Authenticity : Screen
    data object Parts : Screen
    data object Battery : Screen
    data object Security : Screen
    data object Age : Screen
    data object Diagnostics : Screen
    data object Report : Screen
    data object History : Screen
    data class Test(val id: String) : Screen
}

@Composable
fun AppNav(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Screen>(Screen.Dashboard) }
    val snackbarHostState = remember { SnackbarHostState() }

    val navigate: (Screen) -> Unit = { screen -> stack.add(screen) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { back() }

    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = stack.last()) {
                Screen.Dashboard -> DashboardScreen(state, viewModel, navigate)
                Screen.Overview -> OverviewScreen(state, navigate, back)
                Screen.Specs -> SpecsScreen(state, back)
                Screen.Hardware -> HardwareScreen(state, back)
                Screen.Authenticity -> AuthenticityScreen(state, back)
                Screen.Parts -> PartsScreen(state, back)
                Screen.Battery -> BatteryScreen(state, back)
                Screen.Security -> SecurityScreen(state, back)
                Screen.Age -> AgeScreen(state, viewModel, back)
                Screen.Diagnostics -> DiagnosticsScreen(state, viewModel, navigate, back)
                Screen.Report -> ReportScreen(state, viewModel, back)
                Screen.History -> HistoryScreen(state, viewModel, back)
                is Screen.Test -> when (current.id) {
                    "touch" -> TouchTestScreen(viewModel, back)
                    "display_colour", "display_dead_pixels", "display_brightness" ->
                        DisplayTestScreen(current.id, viewModel, back)
                    "camera_rear", "camera_front", "flashlight" ->
                        CameraTestScreen(current.id, state, viewModel, back)
                    "speaker", "earpiece", "microphone" ->
                        AudioTestScreen(current.id, state, viewModel, back)
                    "sensor_accelerometer", "sensor_gyroscope", "sensor_proximity",
                    "sensor_light", "sensor_compass" ->
                        SensorTestScreen(current.id, viewModel, back)
                    else -> MiscTestScreen(current.id, state, viewModel, back)
                }
            }
        }
    }
}
