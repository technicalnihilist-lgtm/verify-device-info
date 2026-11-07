package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.PermissionState
import com.verifydeviceinfo.data.android.PendingPermissionRequests
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The bookkeeping behind [com.verifydeviceinfo.data.android.AndroidPermissionManager.request].
 *
 * Every request has to end with its entry gone: answered, abandoned because its caller was
 * cancelled, or abandoned because it was never raised. These tests pin all three, because an entry
 * that survives is a request that will never be answered and an answer that completes a deferred
 * nobody is waiting on.
 */
class PendingPermissionRequestsTest {

    @Test
    fun `codes are handed out in order and counted`() {
        val pending = PendingPermissionRequests()

        val first = pending.begin()
        val second = pending.begin()
        val third = pending.begin()

        assertEquals(PendingPermissionRequests.FIRST_REQUEST_CODE, first.code)
        assertEquals(first.code + 1, second.code)
        assertEquals(first.code + 2, third.code)
        assertEquals(3, pending.size())
    }

    @Test
    fun `an answer resumes the waiting caller and forgets the code`() = runBlocking {
        val pending = PendingPermissionRequests()
        val request = pending.begin()

        assertTrue(pending.complete(request.code, PermissionState.GRANTED))

        assertEquals(PermissionState.GRANTED, request.deferred.await())
        assertEquals(0, pending.size())
    }

    @Test
    fun `a refusal is delivered as a refusal`() = runBlocking {
        val pending = PendingPermissionRequests()
        val request = pending.begin()

        assertTrue(pending.complete(request.code, PermissionState.DENIED))

        assertEquals(PermissionState.DENIED, request.deferred.await())
    }

    @Test
    fun `an answer for a code nothing is waiting on is ignored`() {
        val pending = PendingPermissionRequests()
        pending.begin()

        assertFalse(pending.complete(9_999, PermissionState.GRANTED))
        assertEquals(1, pending.size())
    }

    @Test
    fun `a code is answered once and the second answer is ignored`() = runBlocking {
        val pending = PendingPermissionRequests()
        val request = pending.begin()

        assertTrue(pending.complete(request.code, PermissionState.GRANTED))
        assertFalse(pending.complete(request.code, PermissionState.DENIED))

        // The first answer is the one the caller sees; the second cannot overwrite it.
        assertEquals(PermissionState.GRANTED, request.deferred.await())
    }

    @Test
    fun `an abandoned request is forgotten and never answered`() {
        val pending = PendingPermissionRequests()
        val request = pending.begin()

        pending.abandon(request.code)

        assertEquals(0, pending.size())
        assertFalse("the code is gone, so a late answer has nothing to complete", pending.complete(request.code, PermissionState.GRANTED))
        // Deliberately not completed: nobody is waiting, and inventing an answer would report a
        // decision the user never made.
        assertFalse(request.deferred.isCompleted)
    }

    @Test
    fun `abandoning a code twice is harmless`() {
        val pending = PendingPermissionRequests()
        val request = pending.begin()

        pending.abandon(request.code)
        pending.abandon(request.code)

        assertEquals(0, pending.size())
    }

    @Test
    fun `two outstanding requests are answered independently`() = runBlocking {
        val pending = PendingPermissionRequests()
        val first = pending.begin()
        val second = pending.begin()

        // Answers do not arrive in the order the requests were made.
        assertTrue(pending.complete(second.code, PermissionState.DENIED))
        assertEquals(PermissionState.DENIED, second.deferred.await())

        assertTrue(pending.complete(first.code, PermissionState.GRANTED))
        assertEquals(PermissionState.GRANTED, first.deferred.await())

        assertEquals(0, pending.size())
    }

    @Test
    fun `codes allocated concurrently are all distinct`() {
        val pending = PendingPermissionRequests()
        val threads = 8
        val perThread = 500
        val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    repeat(perThread) { codes.add(pending.begin().code) }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue("workers did not finish", pool.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, codes.size)
        assertEquals("no code may be handed out twice", codes.size, codes.toSet().size)
        assertEquals(threads * perThread, pending.size())
    }
}
