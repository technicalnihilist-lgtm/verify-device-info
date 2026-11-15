package com.verifydeviceinfo

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.AuthenticityReport
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.domain.AuthenticityAnalysisEngine
import com.verifydeviceinfo.domain.analysis.DefaultAuthenticityEngine
import com.verifydeviceinfo.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the analysis of a scan runs.
 *
 * `ScanCoordinator.start` runs the scan block on the view model's main-thread scope. The reference
 * lookup was moved off it first (see `ReferenceLoadThreadTest`); the five analyzers that follow it —
 * authenticity, parts, age, integrity, scores — stayed on the UI thread, where they cost the frame
 * that draws the scan's last progress step. Measured on the JVM fixture the five together are
 * ~0.65 ms per report, so a mid-range phone spends several milliseconds of a 16.6 ms frame on them.
 * They are pure functions of the snapshot, the reference, the session and the purchase date, so
 * moving them to `Dispatchers.Default` changes no value: the same report is published from the same
 * state, just computed off the thread that draws it.
 *
 * These tests drive the real `AppViewModel.scan` with an authenticity engine that records the thread
 * it is asked on, and check that the answer it gives still reaches the report.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScanAnalysisThreadTest {

    /**
     * Records the thread of every `analyze` call and answers with the real engine's analysis, so
     * the report keeps the values it would have carried without the recording.
     */
    private class RecordingAuthenticityEngine : AuthenticityAnalysisEngine {

        val threads: MutableList<Thread> = CopyOnWriteArrayList()
        val snapshots: MutableList<DeviceSnapshot> = CopyOnWriteArrayList()
        val references: MutableList<ReferenceMatch?> = CopyOnWriteArrayList()

        private val delegate = DefaultAuthenticityEngine()

        override fun analyze(snapshot: DeviceSnapshot, reference: ReferenceMatch?): AuthenticityReport {
            threads += Thread.currentThread()
            snapshots += snapshot
            references += reference
            return delegate.analyze(snapshot, reference)
        }
    }

    private fun viewModel(engine: AuthenticityAnalysisEngine): AppViewModel {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        return AppViewModel(
            application,
            AppContainer(application, authenticityEngineOverride = engine)
        )
    }

    /** Runs the debug-only mock scan and returns the report it published. */
    private fun scannedReport(vm: AppViewModel): DeviceReport {
        vm.scan(useMockData = true)
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            main.idleFor(Duration.ofMillis(250))
            vm.state.value.report?.let { return it }
            if (vm.state.value.error != null) fail("mock scan failed: ${vm.state.value.error}")
            Thread.sleep(5)
        }
        fail("the mock scan produced no report")
        error("unreachable")
    }

    @Test
    fun theAnalysisDoesNotRunOnTheMainThread() {
        val engine = RecordingAuthenticityEngine()
        val report = scannedReport(viewModel(engine))

        assertNotNull("the scan must still produce a report", report)
        assertEquals("the analysis must run exactly once per scan", 1, engine.threads.size)
        val mainThread = Looper.getMainLooper().thread
        engine.threads.forEach { thread ->
            assertTrue(
                "the post-scan analysis ran on the main thread ($mainThread); it is the last CPU " +
                    "work of a scan and must not be spent inside a frame",
                thread !== mainThread
            )
        }
    }

    @Test
    fun theAnalysisStillSeesTheSnapshotAndItsReference() {
        val engine = RecordingAuthenticityEngine()
        val vm = viewModel(engine)
        val report = scannedReport(vm)

        assertSame(
            "the analysis must be handed the snapshot the report is built from",
            report.snapshot,
            engine.snapshots.single()
        )
        assertSame(
            "the analysis must be handed the reference the scan resolved",
            vm.state.value.reference,
            engine.references.single()
        )
    }

    @Test
    fun theAnalysisStillReachesTheReport() {
        val engine = RecordingAuthenticityEngine()
        val vm = viewModel(engine)
        val report = scannedReport(vm)

        // The mock fixture's analysis is deterministic, so the published report must carry exactly
        // what the real engine produces for the same snapshot and reference.
        assertEquals(
            DefaultAuthenticityEngine().analyze(report.snapshot, vm.state.value.reference),
            report.authenticity
        )
        assertSame(report.authenticity, vm.state.value.report?.authenticity)
    }
}
