package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.diagnostics.StaleCloseGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `openCamera` and `createCaptureSession` call back later than they are called, so the camera test
 * screen can be left while an open is still in flight. These pin the rule that fixes it: a resource
 * arriving under a stale token is closed on the spot, and a token taken after the release is
 * current again so the probe can be reopened.
 */
class StaleCloseGuardTest {

    private class FakeResource(private val throwOnClose: Boolean = false) : AutoCloseable {
        var closed = false
            private set

        override fun close() {
            closed = true
            if (throwOnClose) throw IllegalStateException("already gone")
        }
    }

    @Test
    fun aResourceThatArrivesWhileTheOwnerIsOpenIsKept() {
        val guard = StaleCloseGuard<FakeResource>()
        val resource = FakeResource()

        assertTrue(guard.admit(guard.token(), resource))
        assertFalse("an admitted resource must not be closed", resource.closed)
    }

    @Test
    fun aDeviceThatArrivesAfterCloseIsClosedOnTheSpot() {
        val guard = StaleCloseGuard<FakeResource>()
        val inFlight = guard.token()

        guard.invalidate() // the screen was left while openCamera was still running
        val lateCamera = FakeResource()

        assertFalse(guard.admit(inFlight, lateCamera))
        assertTrue("the late camera must be closed, not leaked", lateCamera.closed)
    }

    @Test
    fun aTokenTakenAfterCloseIsCurrentAgain() {
        val guard = StaleCloseGuard<FakeResource>()
        val first = guard.token()
        guard.invalidate()
        val second = guard.token()
        val resource = FakeResource()

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
        assertTrue(guard.admit(second, resource))
        assertFalse(resource.closed)
    }

    @Test
    fun reopeningAfterSeveralClosesStillAdmitsTheNewSession() {
        val guard = StaleCloseGuard<FakeResource>()
        val stale = guard.token()
        repeat(3) { guard.invalidate() }

        assertFalse(guard.admit(stale, FakeResource()))
        assertTrue(guard.admit(guard.token(), FakeResource()))
    }

    @Test
    fun aResourceThatThrowsWhileBeingClosedIsStillRejected() {
        val guard = StaleCloseGuard<FakeResource>()
        val inFlight = guard.token()
        guard.invalidate()
        val hostile = FakeResource(throwOnClose = true)

        assertFalse(guard.admit(inFlight, hostile))
        assertTrue(hostile.closed)
    }

    @Test
    fun tokensAreStableWhileNothingIsClosed() {
        val guard = StaleCloseGuard<FakeResource>()

        assertEquals(guard.token(), guard.token())
    }
}
