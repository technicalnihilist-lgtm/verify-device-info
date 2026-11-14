package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.util.StageOutcome
import com.verifydeviceinfo.core.util.StageTimer
import com.verifydeviceinfo.data.android.automatedCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A framework member the running platform does not carry is a failed stage, not a dead app.
 *
 * Both of the app's containment boundaries — `StageTimer.run` around every scan stage and
 * `automatedCheck` around every automated diagnostic — were written for exceptions, and both
 * documented themselves as "a stage that throws costs that stage only". A missing vendor framework
 * member does not throw an exception: `Build.SOC_MANUFACTURER` on Android 8-11 raises
 * `NoSuchFieldError`, an absent class raises `NoClassDefFoundError`, a method that arrived in a
 * later API raises `NoSuchMethodError`, and every one of those is a `LinkageError`, i.e. an `Error`.
 * `catch (e: Exception)` does not see them, and neither caller above the boundary has a catch that
 * does: the error leaves `collect`, leaves `AppViewModel.scan`'s own `catch (e: Exception)` and
 * reaches the process. The app has already been crashed by exactly this once — the
 * `Build.SOC_MANUFACTURER` read in `IdentityCollector` carries the note.
 *
 * These tests pin both halves of the boundary: a `LinkageError` is contained and reported like any
 * other stage failure, and a `VirtualMachineError` is still not contained, because a process that is
 * out of memory or out of stack is not a stage to skip and a report to keep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameworkMemberContainmentTest {

    /**
     * The timer production builds: a supervisor scope, exactly as
     * `AndroidDeviceInformationProvider` creates one, so a stage that fails does not cancel the
     * scope the later stages run on.
     */
    private fun TestScope.timer(): Pair<StageTimer, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val timer = StageTimer(
            scope = scope,
            stageTimeoutMillis = 1_000L,
            totalTimeoutMillis = 10_000L,
            dispatcher = dispatcher,
            clock = { testScheduler.currentTime }
        )
        return timer to scope
    }

    @Test
    fun `a stage that reads a framework field this build does not carry is a stage failure`() = runTest {
        val (timer, scope) = timer()

        val failed = timer.run<String> { throw NoSuchFieldError("SOC_MANUFACTURER") }
        val next = timer.run { "sensors" }
        scope.cancel()

        assertTrue("expected a stage failure, got $failed", failed is StageOutcome.Failed)
        assertEquals("SOC_MANUFACTURER", (failed as StageOutcome.Failed).error.message)
        assertEquals("the stages after it must still run", StageOutcome.Done("sensors"), next)
    }

    @Test
    fun `a stage that touches a framework class this build does not carry is a stage failure`() = runTest {
        val (timer, scope) = timer()

        val failed = timer.run<String> {
            throw NoClassDefFoundError("android/hardware/biometrics/BiometricManager")
        }
        scope.cancel()

        assertTrue("expected a stage failure, got $failed", failed is StageOutcome.Failed)
        assertEquals(
            "android/hardware/biometrics/BiometricManager",
            (failed as StageOutcome.Failed).error.message
        )
    }

    @Test
    fun `a stage that runs out of memory is not turned into a stage failure`() = runTest {
        val (timer, scope) = timer()
        var propagated: Throwable? = null

        try {
            timer.run<String> { throw OutOfMemoryError("heap exhausted") }
        } catch (e: OutOfMemoryError) {
            propagated = e
        } finally {
            scope.cancel()
        }

        assertNotNull("a virtual machine error must reach the caller", propagated)
    }

    @Test
    fun `an automated check that reads a framework member this build does not carry is unverified`() = runTest {
        val result: DiagnosticResult = automatedCheck(
            testId = "auto_thermal",
            title = "Thermal state",
            unreadable = "the thermal state"
        ) {
            throw NoSuchMethodError("android.os.PowerManager.currentThermalStatus()")
        }

        assertEquals(DiagnosticStatus.UNVERIFIED, result.status)
        assertEquals("auto_thermal", result.testId)
        assertEquals(
            "Could not read the thermal state: " +
                "android.os.PowerManager.currentThermalStatus()",
            result.note
        )
    }

    @Test
    fun `an automated check that runs out of memory is not turned into a refusal`() = runTest {
        var propagated: Throwable? = null

        try {
            automatedCheck("auto_thermal", "Thermal state", "the thermal state") {
                throw OutOfMemoryError("heap exhausted")
            }
        } catch (e: OutOfMemoryError) {
            propagated = e
        }

        assertNotNull("a virtual machine error must reach the caller", propagated)
    }
}
