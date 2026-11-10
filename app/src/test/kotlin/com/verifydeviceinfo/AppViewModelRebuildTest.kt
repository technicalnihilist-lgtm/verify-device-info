package com.verifydeviceinfo

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.ui.AppViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * What a state change is allowed to re-derive.
 *
 * `rebuildReport` used to recompute all five analyses on the main thread whatever had changed. Only
 * `parts` and `scores` read the diagnostic session; `integrity` reads the snapshot alone and `age`
 * reads the snapshot, the reference and the purchase date, so both are reused unless the input they
 * read has changed — `authenticity` was already reused that way. The redaction switch re-ran the
 * whole set to change a boolean that no analyzer reads.
 *
 * These tests pin the reuse with identity (`assertSame`): a re-derived analysis is a new object, a
 * reused one is the very object the previous report held. The mock scan is the fixture, so the
 * values are the fixture's, not a handset's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppViewModelRebuildTest {

    private fun viewModel(): AppViewModel =
        AppViewModel(ApplicationProvider.getApplicationContext())

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
    fun theRedactionSwitchReDerivesNothing() {
        val vm = viewModel()
        val before = scannedReport(vm)

        vm.setRedacted(false)

        val after = vm.state.value.report
        assertFalse("the switch must still reach the report", after!!.redacted)
        assertFalse("and the state the switch reads", vm.state.value.redacted)
        assertSame(before.parts, after.parts)
        assertSame(before.age, after.age)
        assertSame(before.integrity, after.integrity)
        assertSame(before.scores, after.scores)
        assertSame(before.authenticity, after.authenticity)
    }

    @Test
    fun theRedactionSwitchStillReStampsTheReport() {
        val vm = viewModel()
        val before = scannedReport(vm)
        // The Report screen renders "Generated <date>" from this field, so the switch keeps
        // refreshing it exactly as the full rebuild did.
        vm.setRedacted(false)
        val after = vm.state.value.report
        assertNotSame(before, after)
        assertTrue(
            "the timestamp must still be refreshed",
            after!!.generatedAtMillis >= before.generatedAtMillis
        )
    }

    @Test
    fun recordingADiagnosticResultReDerivesOnlyTheSessionDependentWork() {
        val vm = viewModel()
        val before = scannedReport(vm)

        vm.recordResult(
            DiagnosticResult(
                testId = "touch",
                title = "Multi-touch and dead zones",
                status = DiagnosticStatus.PASS,
                measured = "4 pointers"
            )
        )

        val after = vm.state.value.report
        assertSame("integrity reads the snapshot alone", before.integrity, after!!.integrity)
        assertSame("age reads the snapshot, reference and purchase date", before.age, after.age)
        assertSame(before.authenticity, after.authenticity)
        assertNotSame("parts reads the session", before.parts, after.parts)
        assertNotSame("and the scores are computed from the parts", before.scores, after.scores)
        assertTrue(after.session.resultFor("touch") != null)
    }

    @Test
    fun aNewPurchaseDateStillReDerivesTheAge() {
        val vm = viewModel()
        val before = scannedReport(vm)

        vm.setPurchaseDate(TestFixtures.NOW - 400L * 86_400_000L)

        val after = vm.state.value.report
        assertNotSame("the purchase date is an age input", before.age, after!!.age)
        assertSame(before.integrity, after.integrity)
    }
}
