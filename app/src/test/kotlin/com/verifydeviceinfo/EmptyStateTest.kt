package com.verifydeviceinfo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.UiState
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
import com.verifydeviceinfo.ui.theme.VerifyDeviceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every destination in [com.verifydeviceinfo.ui.Screen] rendered in the state it actually meets on
 * a fresh install: no snapshot, no report, no stored history.
 *
 * The app writes `snapshot` and `report` together, but a screen can be opened before the first scan
 * finishes, and a report can be rebuilt from a snapshot after a diagnostics run. Both halves of that
 * pair are therefore nullable at every screen, and a screen that reads one without checking it
 * crashes the process on first launch rather than showing an empty state. These tests assert the
 * empty state is rendered — not merely that rendering did not throw — so a future edit that drops a
 * guard fails here instead of on a user's phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class EmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun viewModel(): AppViewModel =
        AppViewModel(ApplicationProvider.getApplicationContext())

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            VerifyDeviceTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { content() }
            }
        }
        composeRule.waitForIdle()
    }

    /** The shared placeholder every snapshot- or report-dependent screen falls back to. */
    private fun assertPlaceholder() {
        composeRule.onNodeWithText("Nothing to show yet").assertIsDisplayed()
    }

    private val empty = UiState()

    /** A scan has completed but no report exists yet — the state a rebuilt screen can meet. */
    private val scannedOnly = UiState(snapshot = TestFixtures.snapshot())

    // ---- Dashboard: no placeholder, it is the entry point -------------------------------------

    @Test
    fun dashboardBeforeFirstScan() {
        render { DashboardScreen(empty, viewModel()) {} }
        composeRule.onNodeWithText("No scan yet").assertIsDisplayed()
        composeRule.onNodeWithText("Run a scan to collect this device's facts.").assertIsDisplayed()
        composeRule.onNodeWithText("Run device scan").assertIsDisplayed()
    }

    @Test
    fun dashboardWhileScanningWithoutProgress() {
        // `progress` is null until the first stage reports, and the progress bar must not divide by
        // or index into a null.
        render { DashboardScreen(UiState(scanning = true, progress = null), viewModel()) {} }
        composeRule.onNodeWithText("Starting").assertIsDisplayed()
    }

    // ---- Snapshot-dependent screens -----------------------------------------------------------

    @Test
    fun overviewBeforeFirstScan() {
        render { OverviewScreen(empty, {}, {}) }
        assertPlaceholder()
    }

    @Test
    fun specsBeforeFirstScan() {
        render { SpecsScreen(empty, {}) }
        assertPlaceholder()
    }

    @Test
    fun hardwareBeforeFirstScan() {
        render { HardwareScreen(empty, {}) }
        assertPlaceholder()
    }

    @Test
    fun batteryBeforeFirstScan() {
        render { BatteryScreen(empty, {}) }
        assertPlaceholder()
    }

    @Test
    fun batteryWithSnapshotButNoReport() {
        render { BatteryScreen(scannedOnly, {}) }
        composeRule.onNodeWithText("No battery score was computed.").assertIsDisplayed()
    }

    // ---- Report-dependent screens -------------------------------------------------------------

    @Test
    fun authenticityBeforeFirstScan() {
        render { AuthenticityScreen(empty, {}) }
        assertPlaceholder()
    }

    @Test
    fun partsBeforeFirstScan() {
        render { PartsScreen(empty, {}) }
        assertPlaceholder()
    }

    @Test
    fun securityBeforeFirstScan() {
        render { SecurityScreen(empty, {}) }
        assertPlaceholder()
    }

    @Test
    fun ageBeforeFirstScan() {
        render { AgeScreen(empty, viewModel(), {}) }
        assertPlaceholder()
    }

    @Test
    fun reportBeforeFirstScan() {
        render { ReportScreen(empty, viewModel(), {}) }
        assertPlaceholder()
    }

    @Test
    fun reportWithSnapshotButNoReport() {
        render { ReportScreen(scannedOnly, viewModel(), {}) }
        assertPlaceholder()
    }

    @Test
    fun authenticityWithSnapshotButNoReport() {
        render { AuthenticityScreen(scannedOnly, {}) }
        assertPlaceholder()
    }

    // ---- Screens with their own empty state ---------------------------------------------------

    @Test
    fun diagnosticsBeforeFirstScan() {
        // Diagnostics is reachable from the dashboard without scanning, so it must render with an
        // empty session and no snapshot.
        render { DiagnosticsScreen(empty, viewModel(), {}, {}) }
        composeRule.onNodeWithText("Run automated checks").assertIsDisplayed()
    }

    @Test
    fun historyWithNothingStored() {
        render { HistoryScreen(empty, viewModel(), {}) }
        composeRule.onNodeWithText(
            "No scans have been stored yet. Each scan is saved locally on this device only."
        ).assertIsDisplayed()
    }

    // ---- Interactive test screens, reachable before any scan ----------------------------------

    @Test
    fun touchTestWithoutSnapshot() {
        render { TouchTestScreen(viewModel(), {}) }
        composeRule.onNodeWithText("Coverage 0%").assertIsDisplayed()
    }

    @Test
    fun displayTestWithoutSnapshot() {
        render { DisplayTestScreen("display_colour", viewModel(), {}) }
        composeRule.onNodeWithText("Colour and uniformity").assertIsDisplayed()
    }

    @Test
    fun cameraTestWithoutSnapshot() {
        // `resolveCameraId` reads the camera service at composition; with no camera (or one that
        // throws while another app holds it) the screen must fall back to its "Not available" card
        // rather than fail to compose.
        render { CameraTestScreen("camera_rear", empty, viewModel(), {}) }
        composeRule.onNodeWithText("Rear camera").assertIsDisplayed()
    }

    @Test
    fun audioTestWithoutSnapshot() {
        render { AudioTestScreen("speaker", empty, viewModel(), {}) }
        composeRule.onNodeWithText("Loudspeaker").assertIsDisplayed()
    }

    @Test
    fun sensorTestWithoutSnapshot() {
        render { SensorTestScreen("sensor_accelerometer", viewModel(), {}) }
        composeRule.onNodeWithText("Accelerometer").assertIsDisplayed()
    }

    @Test
    fun miscTestWithoutSnapshot() {
        // Vibration, radios, GNSS and thermal all read the snapshot defensively; none of them may
        // require one to render.
        render { MiscTestScreen("vibration", empty, viewModel(), {}) }
        composeRule.onNodeWithText("Vibration motor").assertIsDisplayed()
    }
}
