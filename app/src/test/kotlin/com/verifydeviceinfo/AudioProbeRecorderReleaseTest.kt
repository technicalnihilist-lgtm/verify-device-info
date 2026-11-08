package com.verifydeviceinfo

import android.content.Context
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.data.android.diagnostics.AudioProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `AudioProbe.measureMicrophone` holds the `MediaRecorder` in a field from construction so a
 * failure can release it. The `SecurityException` branch — the one a refused `RECORD_AUDIO`
 * permission actually takes, from `setAudioSource` — used to return without releasing, so the
 * microphone stayed claimed by a recorder only the finalizer would free, and the next attempt
 * overwrote the field and orphaned the first one entirely.
 *
 * These tests pin one cleanup for every failure path, driven through the internal overload that
 * takes the recorder from the caller, because a real `MediaRecorder` cannot be made to throw on a
 * JVM. The fake overrides every method the probe calls, so the assertions are about the probe's
 * lifecycle handling and not about Robolectric's shadow state machine.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AudioProbeRecorderReleaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private enum class FailsAt { NEVER, SET_AUDIO_SOURCE, PREPARE }

    private class FakeRecorder(context: Context, private val failsAt: FailsAt) : MediaRecorder(context) {
        var releaseCount = 0
        var started = false
        var stopped = false

        override fun setAudioSource(audioSource: Int) {
            if (failsAt == FailsAt.SET_AUDIO_SOURCE) throw SecurityException("RECORD_AUDIO not granted")
        }

        override fun setOutputFormat(outputFormat: Int) = Unit

        override fun setAudioEncoder(audioEncoder: Int) = Unit

        override fun setOutputFile(path: String) = Unit

        override fun prepare() {
            if (failsAt == FailsAt.PREPARE) throw IllegalStateException("recorder would not prepare")
        }

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        override fun getMaxAmplitude(): Int = 1200

        override fun release() {
            releaseCount++
        }
    }

    @Test
    fun refusedPermissionReleasesTheRecorder() = runBlocking {
        val fake = FakeRecorder(context, FailsAt.SET_AUDIO_SOURCE)
        val result = AudioProbe(context).measureMicrophone(200) { fake }

        assertNull(result)
        assertEquals("a refused permission must release the recorder exactly once", 1, fake.releaseCount)
        assertFalse("the recorder never started", fake.started)
    }

    @Test
    fun recorderThatWillNotPrepareReleasesTheRecorder() = runBlocking {
        val fake = FakeRecorder(context, FailsAt.PREPARE)
        val result = AudioProbe(context).measureMicrophone(200) { fake }

        assertNull(result)
        assertEquals(1, fake.releaseCount)
    }

    @Test
    fun successfulCaptureReleasesTheRecorderOnce() = runBlocking {
        val fake = FakeRecorder(context, FailsAt.NEVER)
        val result = AudioProbe(context).measureMicrophone(200) { fake }

        assertEquals(1200 to 1200, result)
        assertTrue(fake.started)
        assertTrue(fake.stopped)
        assertEquals("the success path releases through the same cleanup", 1, fake.releaseCount)
    }

    @Test
    fun releaseAfterAFailedCaptureDoesNotReleaseAgain() = runBlocking {
        val probe = AudioProbe(context)
        val fake = FakeRecorder(context, FailsAt.SET_AUDIO_SOURCE)

        assertNull(probe.measureMicrophone(200) { fake })
        probe.release()

        assertEquals("the failed recorder was forgotten, so release() has nothing to free", 1, fake.releaseCount)
    }

    @Test
    fun aSecondCaptureDoesNotOrphanTheFirstRecorder() = runBlocking {
        val probe = AudioProbe(context)
        val first = FakeRecorder(context, FailsAt.SET_AUDIO_SOURCE)
        val second = FakeRecorder(context, FailsAt.NEVER)

        assertNull(probe.measureMicrophone(200) { first })
        assertEquals(1200 to 1200, probe.measureMicrophone(200) { second })

        assertEquals(1, first.releaseCount)
        assertEquals(1, second.releaseCount)
    }
}
