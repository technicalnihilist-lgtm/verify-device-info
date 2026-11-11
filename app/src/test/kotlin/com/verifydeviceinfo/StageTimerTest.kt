package com.verifydeviceinfo

import com.verifydeviceinfo.core.util.StageOutcome
import com.verifydeviceinfo.core.util.StageTimer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan must always finish. These tests pin the deadline behaviour of [StageTimer]: a stage
 * that hangs is abandoned and reported as timed out, the stages after it still run, and the
 * whole-scan budget stops a run of slow stages from adding up to a hang.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StageTimerTest {

    /** A timer whose clock and dispatcher are the test scheduler's, so the delays are virtual. */
    private fun TestScope.timer(
        stageMillis: Long = 1_000L,
        totalMillis: Long = 10_000L
    ): Pair<StageTimer, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val timer = StageTimer(
            scope = scope,
            stageTimeoutMillis = stageMillis,
            totalTimeoutMillis = totalMillis,
            dispatcher = dispatcher,
            clock = { testScheduler.currentTime }
        )
        return timer to scope
    }

    @Test
    fun `a stage that finishes returns its value`() = runTest {
        val (timer, scope) = timer()
        val outcome = timer.run { "cameras" }
        scope.cancel()
        assertEquals(StageOutcome.Done("cameras"), outcome)
    }

    @Test
    fun `a stage that returns null is done, not timed out`() = runTest {
        val (timer, scope) = timer()
        val outcome = timer.run<String?> { null }
        scope.cancel()
        assertEquals(StageOutcome.Done(null), outcome)
    }

    @Test
    fun `a stage that throws is reported as failed`() = runTest {
        val (timer, scope) = timer()
        val outcome = timer.run<String> { throw IllegalStateException("binder died") }
        scope.cancel()
        assertTrue(outcome is StageOutcome.Failed)
        assertEquals("binder died", (outcome as StageOutcome.Failed).error.message)
    }

    @Test
    fun `a stage that cancels itself is a stage failure, not a cancelled scan`() = runTest {
        // A collector that gives up on its own — a `withTimeout` around a probe, a helper whose
        // job is cancelled by the framework — raises a CancellationException that has nothing to
        // do with the scan being superseded. It must be recorded against that stage, and the
        // stages after it must still run.
        val (timer, scope) = timer()
        val failed = timer.run<String> { throw CancellationException("probe gave up") }
        val next = timer.run { "sensors" }
        scope.cancel()

        assertTrue("expected a stage failure, got $failed", failed is StageOutcome.Failed)
        assertEquals("probe gave up", (failed as StageOutcome.Failed).error.message)
        assertEquals(StageOutcome.Done("sensors"), next)
    }

    @Test
    fun `a cancelled caller still unwinds instead of recording a stage failure`() = runTest {
        // The other half of the same decision: when the scan itself is superseded, the
        // CancellationException must reach the caller, because that is how it stops.
        val (timer, scope) = timer(stageMillis = 10_000L, totalMillis = 10_000L)
        var unwound = false

        val scan = launch {
            try {
                timer.run { delay(5_000L) }
            } catch (e: CancellationException) {
                unwound = true
                throw e
            }
        }
        testScheduler.runCurrent()
        scan.cancel()
        testScheduler.advanceUntilIdle()
        scope.cancel()

        assertTrue("a superseded scan must unwind, not be reported as a stage failure", unwound)
    }

    @Test
    fun `a stage that exceeds its deadline is abandoned and the next stage still runs`() = runTest {
        val (timer, scope) = timer(stageMillis = 1_000L, totalMillis = 10_000L)

        val stuck = timer.run {
            delay(60_000L)
            "never delivered"
        }
        val next = timer.run { "sensors" }
        scope.cancel()

        assertEquals(StageOutcome.TimedOut, stuck)
        assertEquals(StageOutcome.Done("sensors"), next)
    }

    @Test
    fun `an abandoned stage is cancelled rather than left running`() = runTest {
        val (timer, scope) = timer(stageMillis = 500L, totalMillis = 10_000L)
        var finished = false

        val outcome = timer.run {
            delay(5_000L)
            finished = true
        }
        testScheduler.advanceUntilIdle()
        scope.cancel()

        assertEquals(StageOutcome.TimedOut, outcome)
        assertTrue("the abandoned stage should not have run to completion", !finished)
    }

    @Test
    fun `the whole-scan budget caps every later stage`() = runTest {
        val (timer, scope) = timer(stageMillis = 1_000L, totalMillis = 1_000L)

        val first = timer.run { delay(400L); 1 }
        val second = timer.run { delay(400L); 2 }
        val third = timer.run { delay(400L); 3 }
        scope.cancel()

        assertEquals(StageOutcome.Done(1), first)
        assertEquals(StageOutcome.Done(2), second)
        assertEquals(StageOutcome.TimedOut, third)
    }

    @Test
    fun `a stage is not even started once the budget is spent`() = runTest {
        val (timer, scope) = timer(stageMillis = 1_000L, totalMillis = 1_000L)
        var started = false

        timer.run { delay(1_000L); 1 }
        val outcome = timer.run { started = true; 2 }
        scope.cancel()

        assertEquals(StageOutcome.TimedOut, outcome)
        assertTrue("no budget left, so the block must not run", !started)
    }

    @Test
    fun `the stage limit is capped by what is left of the budget`() = runTest {
        val (timer, scope) = timer(stageMillis = 5_000L, totalMillis = 1_200L)
        // The stage limit alone would be 5 s, but only 1.2 s of the scan budget is left.
        assertEquals(1_200L, timer.nextStageTimeoutMillis())

        timer.run { delay(1_000L); 1 }
        val capped = timer.nextStageTimeoutMillis()
        scope.cancel()

        assertEquals(200L, capped)
        assertEquals(StageTimer.DEFAULT_STAGE_TIMEOUT_MILLIS, 20_000L)
        assertEquals(StageTimer.DEFAULT_TOTAL_TIMEOUT_MILLIS, 90_000L)
    }
}
