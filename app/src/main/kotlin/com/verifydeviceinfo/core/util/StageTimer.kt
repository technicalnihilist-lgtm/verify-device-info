package com.verifydeviceinfo.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** What one collection stage produced. */
sealed interface StageOutcome<out T> {
    /** The stage finished; [value] is whatever it returned, which may be null. */
    data class Done<T>(val value: T) : StageOutcome<T>

    /** The stage threw. The scan continues without it, exactly as before. */
    data class Failed(val error: Throwable) : StageOutcome<Nothing>

    /** The stage did not finish inside its slice of the scan's time budget. */
    data object TimedOut : StageOutcome<Nothing>
}

/**
 * Runs collection stages under a hard time limit so a stuck Binder call cannot hang the scan.
 *
 * Every collector stage is a blocking Java call — `getprop`, `PackageManager`, the camera service,
 * the sensor list. Those calls have no timeout of their own: when another app holds the camera, or
 * a vendor's property service stops answering, the call never returns and the scan never finishes,
 * leaving the screen on "Scanning" with no way out. [run] gives each stage a deadline and, when the
 * deadline passes, hands control back to the caller so the remaining stages still run and the scan
 * completes with the facts it could gather.
 *
 * The work runs on [scope], which is deliberately *not* a child of the caller: a coroutine blocked
 * inside a Java call does not observe cancellation, so a stage abandoned as timed out would keep
 * the caller's structured scope waiting forever. It is abandoned instead, and cancelled so that a
 * cooperative stage stops at its next suspension point.
 *
 * Two limits apply: [stageTimeoutMillis] per stage, and [totalTimeoutMillis] for the whole scan,
 * which caps every later stage at whatever is left of the budget.
 */
class StageTimer(
    private val scope: CoroutineScope,
    val stageTimeoutMillis: Long = DEFAULT_STAGE_TIMEOUT_MILLIS,
    private val totalTimeoutMillis: Long = DEFAULT_TOTAL_TIMEOUT_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    private val startedAtMillis = clock()

    /** Milliseconds left of the whole-scan budget; never negative. */
    fun remainingMillis(): Long =
        (totalTimeoutMillis - (clock() - startedAtMillis)).coerceAtLeast(0L)

    /** The limit that applies to the next stage: its own limit, capped by the budget left. */
    fun nextStageTimeoutMillis(): Long = minOf(stageTimeoutMillis, remainingMillis())

    /**
     * Runs [block] and reports what happened. Never throws for a stage failure or a timeout; only
     * cancellation of the calling coroutine propagates, so a superseded scan still unwinds.
     *
     * "A stage failure" includes a [LinkageError]. Every stage reads a vendor framework —
     * `Build.SOC_MANUFACTURER`, `PowerManager.currentThermalStatus`, a `CameraCharacteristics` key,
     * a `SensorManager` method — and a member that the running platform does not carry raises
     * `NoSuchFieldError`, `NoSuchMethodError`, `NoClassDefFoundError` or
     * `ExceptionInInitializerError`, all of which are `Error`s and not `Exception`s. This app has
     * already been crashed by exactly that once (`IdentityCollector` reads `Build.SOC_MANUFACTURER`
     * behind a level check for that reason), and `catch (e: Exception)` does not contain any of
     * them: the error left this method, left `collect`, missed `AppViewModel.scan`'s own
     * `catch (e: Exception)` and took the process down. A framework read that does not exist on
     * this build is a stage that failed, not a scan that must crash.
     *
     * [LinkageError] is the boundary and it is deliberate: it covers exactly the class of failure a
     * missing framework member raises. [VirtualMachineError] (`OutOfMemoryError`,
     * `StackOverflowError`) still propagates — a process that is out of memory or out of stack is
     * not a stage to skip and a report to keep, and swallowing it would turn a fatal state into a
     * plausible-looking partial scan.
     */
    suspend fun <T> run(block: suspend () -> T): StageOutcome<T> {
        val budget = nextStageTimeoutMillis()
        if (budget <= 0L) return StageOutcome.TimedOut

        val work = scope.async(dispatcher) { Box(block()) }
        val boxed = try {
            withTimeoutOrNull(budget) { work.await() }
        } catch (e: CancellationException) {
            // Two different things arrive here and only one of them may unwind the scan.
            //
            // The caller was cancelled — a newer scan superseded this one — so the stage is
            // stopped and the exception is rethrown: that is how the superseded scan unwinds.
            //
            // But a stage can raise a `CancellationException` of its own: a collector that wraps
            // a probe in `withTimeout` (as `SensorProbe` already does), a Binder helper that
            // gives up, a `suspendCancellableCoroutine` whose job is cancelled by the framework.
            // That is a stage failure like any other — the contract above says so — and treating
            // it as caller cancellation used to abandon the remaining stages and report the whole
            // scan as failed. The caller's own job state is what tells the two apart.
            work.cancel()
            if (!currentCoroutineContext().isActive) throw e
            return StageOutcome.Failed(e)
        } catch (e: LinkageError) {
            // A framework member this platform does not carry. See the contract above.
            work.cancel()
            return StageOutcome.Failed(e)
        } catch (e: Exception) {
            work.cancel()
            return StageOutcome.Failed(e)
        }
        if (boxed == null) {
            work.cancel()
            return StageOutcome.TimedOut
        }
        return StageOutcome.Done(boxed.value)
    }

    /** Distinguishes "the stage returned null" from "the stage timed out". */
    private class Box<T>(val value: T)

    companion object {
        /** A single stage longer than this is not collecting anything, it is stuck. */
        const val DEFAULT_STAGE_TIMEOUT_MILLIS: Long = 20_000L

        /** Ceiling for a whole scan, so eight stuck stages cannot add up to a hang. */
        const val DEFAULT_TOTAL_TIMEOUT_MILLIS: Long = 90_000L
    }
}
