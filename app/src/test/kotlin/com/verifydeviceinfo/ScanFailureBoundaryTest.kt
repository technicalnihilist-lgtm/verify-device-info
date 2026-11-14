package com.verifydeviceinfo

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticTestDescriptor
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.Platform
import com.verifydeviceinfo.core.model.ScanProgress
import com.verifydeviceinfo.domain.DeviceInformationProvider
import com.verifydeviceinfo.domain.HardwareDiagnosticsEngine
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The last boundary before the process.
 *
 * `AndroidDeviceInformationProvider.collect` and the diagnostics engine contain their own failures
 * and report them, but the two view-model calls around them used to catch `Exception` only. A
 * `LinkageError` — a class or method the runtime cannot resolve, which is what a device whose
 * framework lacks a symbol raises — is an `Error`, so it left the coroutine and crashed the app
 * instead of showing "Scan failed: …" the way every other failure on that path does.
 *
 * These tests drive the real view model with a collaborator that throws on purpose, which is what
 * the two override parameters on `AppContainer` are for.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScanFailureBoundaryTest {

    private class ThrowingProvider(private val failure: Throwable) : DeviceInformationProvider {
        override val platform: Platform = Platform.ANDROID
        override suspend fun collect(onProgress: (ScanProgress) -> Unit): DeviceSnapshot = throw failure
    }

    private class ThrowingDiagnostics(private val failure: Throwable) : HardwareDiagnosticsEngine {
        override fun availableTests(): List<DiagnosticTestDescriptor> = emptyList()
        override suspend fun runAutomatedChecks(): List<DiagnosticResult> = throw failure
    }

    private fun viewModel(
        provider: DeviceInformationProvider? = null,
        diagnostics: HardwareDiagnosticsEngine? = null
    ): AppViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return AppViewModel(application, AppContainer(application, provider, diagnostics))
    }

    /** Idles the main looper until [condition] holds, so a published state is not read too early. */
    private fun await(vm: AppViewModel, condition: (UiState) -> Boolean): UiState {
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            main.idleFor(Duration.ofMillis(250))
            val state = vm.state.value
            if (condition(state)) return state
            Thread.sleep(5)
        }
        throw AssertionError("the view model never reached the expected state: ${vm.state.value}")
    }

    /**
     * Lets the work [AppViewModel] queues in its constructor — the stored-history refresh — finish
     * before the test publishes anything.
     *
     * `refreshHistory` reads the state it will write back *before* it suspends on the disk, so a
     * refresh still in flight when a failure is published writes its older copy of the state over
     * it. That is a pre-existing lost update on the history path, recorded in the backlog, and it is
     * not what these tests are about: draining it first keeps them about the failure boundary.
     */
    private fun settle(vm: AppViewModel) {
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5_000L
        var last = vm.state.value
        var unchanged = 0
        while (System.currentTimeMillis() < deadline && unchanged < 5) {
            main.idleFor(Duration.ofMillis(100))
            val now = vm.state.value
            if (now == last) unchanged++ else unchanged = 0
            last = now
            Thread.sleep(10)
        }
    }

    @Test
    fun aScanThatHitsALinkageErrorReportsAFailedScan() {
        val vm = viewModel(provider = ThrowingProvider(NoClassDefFoundError("android/os/ServiceManager")))

        vm.scan()

        val state = await(vm) { it.error != null }
        assertEquals("Scan failed: android/os/ServiceManager", state.error)
        assertFalse("a failed scan is not still running", state.scanning)
    }

    @Test
    fun aDiagnosticsRunThatHitsALinkageErrorReportsAFailedRun() {
        val engine = ThrowingDiagnostics(NoClassDefFoundError("android/os/ServiceManager"))
        val vm = viewModel(diagnostics = engine)
        assertSame("the fixture must be the engine the view model runs", engine, vm.container.diagnosticsEngine)
        settle(vm)

        vm.runAutomatedDiagnostics()

        val state = await(vm) { it.error != null }
        assertEquals("Diagnostics failed: android/os/ServiceManager", state.error)
    }

    @Test
    fun anOrdinaryScanFailureIsStillReportedTheSameWay() {
        val vm = viewModel(provider = ThrowingProvider(IllegalStateException("boom")))

        vm.scan()

        val state = await(vm) { it.error != null }
        assertEquals("Scan failed: boom", state.error)
        assertFalse(state.scanning)
    }
}
