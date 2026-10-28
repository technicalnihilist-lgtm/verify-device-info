package com.verifydeviceinfo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification
import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import com.verifydeviceinfo.domain.analysis.DefaultAuthenticityEngine
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.Screen
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.screens.AuthenticityScreen
import com.verifydeviceinfo.ui.screens.BatteryScreen
import com.verifydeviceinfo.ui.screens.DashboardScreen
import com.verifydeviceinfo.ui.screens.DiagnosticsScreen
import com.verifydeviceinfo.ui.screens.PartsScreen
import com.verifydeviceinfo.ui.screens.SecurityScreen
import com.verifydeviceinfo.ui.screens.SpecsScreen
import com.verifydeviceinfo.ui.theme.VerifyDeviceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Compose screens to PNG on the JVM.
 *
 * This exists because the project was built without access to a phone or an emulator: these are
 * genuine renderings of the shipping composables, produced by Robolectric's native graphics
 * pipeline, not hand-drawn mockups.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun fields(): List<SpecField> = listOf(
        field("identity.manufacturer", "Manufacturer", "samsung", SpecGroup.IDENTITY, "Build.MANUFACTURER"),
        field("identity.brand", "Brand", "samsung", SpecGroup.IDENTITY, "Build.BRAND"),
        field("identity.model", "Model", "SM-S911B", SpecGroup.IDENTITY, "Build.MODEL"),
        field("identity.device", "Device codename", "dm1q", SpecGroup.IDENTITY, "Build.DEVICE"),
        field("identity.fingerprint", "Build fingerprint",
            "samsung/dm1qxxx/dm1q:14/UP1A/1234:user/release-keys", SpecGroup.IDENTITY, "Build.FINGERPRINT"),
        field("identity.socModel", "SoC model", "SM8550", SpecGroup.PROCESSOR, "Build.SOC_MODEL"),
        field("cpu.cores", "CPU cores", "8", SpecGroup.PROCESSOR, "Runtime.availableProcessors()"),
        field("cpu.abis", "Supported ABIs", "arm64-v8a, armeabi-v7a", SpecGroup.PROCESSOR, "Build.SUPPORTED_ABIS"),
        field("gpu.glesVersion", "OpenGL ES version", "3.2", SpecGroup.GRAPHICS,
            "deviceConfigurationInfo.glEsVersion"),
        field("memory.totalRam", "Total RAM", "7.45 GB", SpecGroup.MEMORY, "ActivityManager.MemoryInfo"),
        field("storage.total", "Total internal storage", "119.19 GB", SpecGroup.STORAGE, "StatFs"),
        field("display.resolution", "Display resolution", "1080 x 2340 px", SpecGroup.DISPLAY, "DisplayMetrics"),
        field("display.density", "Pixel density", "420 dpi", SpecGroup.DISPLAY, "DisplayMetrics.densityDpi"),
        field("display.refreshRate", "Current refresh rate", "120 Hz", SpecGroup.DISPLAY, "Display.refreshRate"),
        field("battery.health", "Battery health", "Good", SpecGroup.BATTERY, "BatteryManager.EXTRA_HEALTH"),
        field("battery.temperature", "Battery temperature", "31.0 °C", SpecGroup.BATTERY,
            "BatteryManager.EXTRA_TEMPERATURE"),
        field("battery.cycleCount", "Charge cycles", "Not accessible", SpecGroup.BATTERY,
            "BatteryManager.EXTRA_CYCLE_COUNT", DiagnosticStatus.NOT_SUPPORTED,
            "Charge cycle count is only exposed from Android 14 (API 34)."),
        field("camera.count", "Camera modules enumerated", "4 (3 rear, 1 front)", SpecGroup.CAMERA,
            "CameraManager.cameraIdList"),
        field("sensor.count", "Sensors enumerated", "22", SpecGroup.SENSOR,
            "SensorManager.getSensorList(TYPE_ALL)"),
        field("security.rootIndicators", "Root indicators", "None found", SpecGroup.SECURITY,
            "Filesystem and property checks"),
        field("security.verifiedBoot", "Verified Boot state", "green", SpecGroup.SECURITY,
            "ro.boot.verifiedbootstate"),
        field("identity.serial", "Serial number", "Not accessible", SpecGroup.IDENTITY,
            "Build.getSerial()", DiagnosticStatus.NOT_SUPPORTED,
            "Android 10 and newer restrict hardware serial numbers to privileged apps.")
    )

    private fun field(
        key: String,
        label: String,
        value: String,
        group: SpecGroup,
        source: String,
        status: DiagnosticStatus = DiagnosticStatus.VERIFIED,
        note: String? = null
    ) = SpecField(
        key = key,
        label = label,
        value = value,
        group = group,
        status = status,
        confidence = if (status == DiagnosticStatus.NOT_SUPPORTED) Confidence.NONE else Confidence.HIGH,
        verification = if (status == DiagnosticStatus.NOT_SUPPORTED) Verification.INACCESSIBLE
        else Verification.DIRECTLY_VERIFIED,
        source = source,
        note = note
    )

    private fun buildState(): UiState {
        val snapshot = TestFixtures.snapshot(
            software = TestFixtures.software(securityPatch = "2025-05-01")
        ).copy(fields = fields())
        // Real manufacturer source, so published screenshots do not show a placeholder URL.
        val reference = TestFixtures.referenceMatch().let {
            com.verifydeviceinfo.core.model.ReferenceMatch(
                it.device.copy(
                    sourceUrl = "https://www.samsung.com/in/smartphones/galaxy-s23/specs/",
                    sourceName = "Samsung official specifications"
                ),
                it.matchedPattern
            )
        }
        val session = DiagnosticSession(
            listOf(
                DiagnosticResult(
                    testId = "auto_storage", title = "Storage headroom",
                    status = DiagnosticStatus.PASS, measured = "46% free (55.2 GB of 119.2 GB)"
                ),
                DiagnosticResult(
                    testId = "auto_memory", title = "Memory headroom",
                    status = DiagnosticStatus.PASS, measured = "33% free (2.45 GB of 7.45 GB)"
                ),
                DiagnosticResult(
                    testId = "auto_thermal", title = "Thermal state",
                    status = DiagnosticStatus.PASS, measured = "No throttling"
                ),
                DiagnosticResult(
                    testId = "touch", title = "Multi-touch and dead zones",
                    status = DiagnosticStatus.PASS, measured = "100% of cells touched, up to 5 simultaneous touches"
                )
            )
        )
        val parts = DefaultPartsProvider().assess(snapshot, reference, session)
        val age = DefaultAgeEstimator().estimate(snapshot, reference, null)
        val integrity = DefaultIntegrityAnalyzer().analyze(snapshot)
        val scores = DefaultScoreEngine().score(snapshot, parts, integrity, session)
        val report = DeviceReport(
            snapshot = snapshot,
            authenticity = DefaultAuthenticityEngine().analyze(snapshot, reference),
            parts = parts,
            age = age,
            integrity = integrity,
            scores = scores,
            session = session,
            generatedAtMillis = TestFixtures.NOW,
            appVersion = "1.0.0",
            redacted = true
        )
        return UiState(
            scanning = false,
            snapshot = snapshot,
            reference = reference,
            report = report,
            session = session,
            purchaseDateMillis = null,
            redacted = true
        )
    }

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            VerifyDeviceTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { content() }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun dashboard() {
        val state = buildState()
        val viewModel = AppViewModel(ApplicationProvider.getApplicationContext())
        capture("01-dashboard") { DashboardScreen(state, viewModel) {} }
    }

    @Test
    fun authenticity() {
        val state = buildState()
        capture("02-authenticity") { AuthenticityScreen(state) {} }
    }

    @Test
    fun parts() {
        val state = buildState()
        capture("03-parts") { PartsScreen(state) {} }
    }

    @Test
    fun specifications() {
        val state = buildState()
        capture("04-specifications") { SpecsScreen(state) {} }
    }

    @Test
    fun security() {
        val state = buildState()
        capture("05-security") { SecurityScreen(state) {} }
    }

    @Test
    fun battery() {
        val state = buildState()
        capture("06-battery") { BatteryScreen(state) {} }
    }

    @Test
    fun diagnostics() {
        val state = buildState()
        val viewModel = AppViewModel(ApplicationProvider.getApplicationContext())
        capture("07-diagnostics") { DiagnosticsScreen(state, viewModel, {}, {}) }
    }
}
