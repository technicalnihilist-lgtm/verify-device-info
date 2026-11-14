package com.verifydeviceinfo

import com.verifydeviceinfo.core.util.ScanCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a scan is allowed to publish once a newer one has started, and from which thread it asks.
 *
 * `ScanCoordinator.start` is called from the view model's main-thread scope, but `isCurrent` is not
 * asked from that thread: `AppViewModel.scan` publishes the progress its provider hands back, and
 * `AndroidDeviceInformationProvider.collect` calls that callback inside
 * `withContext(Dispatchers.IO)`. `generation` was a plain field, so the read and the write had no
 * happens-before edge between them and the reader could see the previous number — which reads as
 * "my own scan was superseded", dropping the progress of the scan that is actually running, and in
 * the other direction lets a superseded scan publish over its replacement.
 *
 * These tests drive the generation gate itself rather than the memory model: they pin that the
 * number a caller is given is the one that is current, that a newer request retires it, and that the
 * answer is the same when the question is asked from a worker thread rather than from the thread
 * that started the request. A visibility bug cannot be provoked on demand, so what is asserted here
 * is the contract the `@Volatile` fields exist to keep.
 */
class ScanCoordinatorGenerationTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** The generation a request was handed, read out of the block that received it. */
    private suspend fun generationOf(coordinator: ScanCoordinator): Long {
        val received = CompletableDeferred<Long>()
        coordinator.start { generation -> received.complete(generation) }
        return received.await()
    }

    /** Asks [ScanCoordinator.isCurrent] from a thread that is not the one calling `start`. */
    private fun askFromAnotherThread(
        coordinator: ScanCoordinator,
        vararg generations: Long
    ): List<Boolean> {
        val answers = CompletableDeferred<List<Boolean>>()
        val thread = Thread {
            answers.complete(generations.map { coordinator.isCurrent(it) })
        }
        thread.start()
        thread.join()
        return runBlocking { answers.await() }
    }

    @Test
    fun `the newest request is current and the one it replaced is not`() = runBlocking {
        val coordinator = ScanCoordinator(scope)

        val first = generationOf(coordinator)
        assertTrue("the only request is current", coordinator.isCurrent(first))

        val second = generationOf(coordinator)
        assertFalse("a superseded request must not publish", coordinator.isCurrent(first))
        assertTrue("the replacement is current", coordinator.isCurrent(second))
    }

    @Test
    fun `the gate answers the same from a worker thread as from the caller`() = runBlocking {
        val coordinator = ScanCoordinator(scope)

        val first = generationOf(coordinator)
        val second = generationOf(coordinator)

        val answers = askFromAnotherThread(coordinator, first, second)

        assertEquals(listOf(false, true), answers)
        // The same two questions on the calling thread, for the comparison to mean anything.
        assertEquals(listOf(false, true), listOf(coordinator.isCurrent(first), coordinator.isCurrent(second)))
    }

    @Test
    fun `a number nobody was given is never current`() = runBlocking {
        val coordinator = ScanCoordinator(scope)
        val current = generationOf(coordinator)

        assertTrue(coordinator.isCurrent(current))
        assertFalse("a stale or invented number must not publish", coordinator.isCurrent(current - 1))
        assertFalse("a number from the future must not publish", coordinator.isCurrent(current + 1))
    }
}
