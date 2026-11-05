package com.verifydeviceinfo

import com.verifydeviceinfo.core.util.ScanCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan used to be guarded by `if (scanning) return`, so a scan that never returned locked the
 * app out of scanning for the rest of the process. These pin the replacement: newest request wins,
 * the older one is cancelled, and a superseded scan is not allowed to publish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanCoordinatorTest {

    @Test
    fun aSecondStartCancelsTheScanThatIsStillRunning() = runTest {
        val coordinator = ScanCoordinator(this)
        var finishedNormally = false
        var sawCancellation = false

        coordinator.start {
            try {
                awaitCancellation()
                finishedNormally = true
            } catch (e: CancellationException) {
                sawCancellation = true
                throw e
            }
        }
        runCurrent()

        coordinator.start { }
        runCurrent()

        assertTrue("the superseded scan should have been cancelled", sawCancellation)
        assertFalse(finishedNormally)
    }

    @Test
    fun aSupersededScanStopsDoingWork() = runTest {
        val coordinator = ScanCoordinator(this)
        var ticks = 0

        coordinator.start {
            while (true) {
                ticks++
                delay(10)
            }
        }
        runCurrent()
        advanceTimeBy(100)
        val ticksBeforeSupersede = ticks
        assertTrue(ticksBeforeSupersede > 0)

        coordinator.start { }
        runCurrent()
        advanceTimeBy(1_000)

        assertEquals(ticksBeforeSupersede, ticks)
    }

    @Test
    fun onlyTheNewestGenerationMayPublish() = runTest {
        val coordinator = ScanCoordinator(this)
        var firstGeneration = -1L
        var secondGeneration = -1L

        coordinator.start { firstGeneration = it }
        runCurrent()
        coordinator.start { secondGeneration = it }
        runCurrent()

        assertTrue(secondGeneration > firstGeneration)
        assertFalse(coordinator.isCurrent(firstGeneration))
        assertTrue(coordinator.isCurrent(secondGeneration))
    }

    @Test
    fun aBlockStartsAsTheCurrentGeneration() = runTest {
        val coordinator = ScanCoordinator(this)
        var wasCurrent = false

        coordinator.start { generation -> wasCurrent = coordinator.isCurrent(generation) }
        runCurrent()

        assertTrue(wasCurrent)
    }

    @Test
    fun aScanCanBeStartedAgainAfterThePreviousOneFinishes() = runTest {
        val coordinator = ScanCoordinator(this)
        var runs = 0

        repeat(3) {
            coordinator.start { runs++ }
            runCurrent()
        }

        assertEquals("a finished scan must not lock out the next one", 3, runs)
    }
}
