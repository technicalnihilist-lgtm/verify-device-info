package com.verifydeviceinfo

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.data.android.diagnostics.AudioProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * `AudioProbe.playTone` built an `AudioTrack`, wrote the tone into it and started it, and only then
 * put it in the field the cleanup path releases. Both `write` and `play` throw `IllegalStateException`
 * on a track the output device left uninitialised — no usable route, an unplugged sink — so a failed
 * attempt ran the catch with the field still empty: the built track, its static buffer (about 176 KB
 * for the two-second mono tone) and its AudioFlinger client were never released, once per tap of the
 * play button, and only the finalizer eventually gave them back.
 *
 * These tests pin one release for every failure path and exactly one for the success path, driven
 * through the internal overload that takes the track from the caller, because a real `AudioTrack`
 * cannot be made to throw on a JVM. The fake overrides every method the probe calls, so the
 * assertions are about the probe's lifecycle handling and not about Robolectric's shadow state.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AudioProbeToneReleaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private enum class FailsAt { NEVER, WRITE, PLAY }

    @Suppress("DEPRECATION")
    private class FakeTrack(private val failsAt: FailsAt) : AudioTrack(
        AudioManager.STREAM_MUSIC,
        44100,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        4096,
        AudioTrack.MODE_STATIC
    ) {
        var releaseCount = 0
        var stopCount = 0
        var playCount = 0
        var writtenSamples = -1

        override fun write(audioData: ShortArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            if (failsAt == FailsAt.WRITE) throw IllegalStateException("track is not initialised")
            writtenSamples = sizeInBytes
            return sizeInBytes / 2
        }

        override fun play() {
            if (failsAt == FailsAt.PLAY) throw IllegalStateException("track is not initialised")
            playCount++
        }

        override fun stop() {
            stopCount++
        }

        override fun release() {
            releaseCount++
        }
    }

    private fun probe() = AudioProbe(context)

    @Test
    fun aWriteThatThrowsReleasesTheTrack() {
        val fake = FakeTrack(FailsAt.WRITE)

        assertFalse("a track that cannot be written reports failure", probe().playTone(440.0, 2000) { fake })

        assertEquals("the failed track must be released exactly once", 1, fake.releaseCount)
        assertEquals("a track that never played must not be started", 0, fake.playCount)
    }

    @Test
    fun aPlayThatThrowsReleasesTheTrack() {
        val fake = FakeTrack(FailsAt.PLAY)

        assertFalse(probe().playTone(440.0, 2000) { fake })

        assertEquals(1, fake.releaseCount)
    }

    @Test
    fun aSuccessfulToneHoldsTheTrackUntilItIsStopped() {
        val fake = FakeTrack(FailsAt.NEVER)
        val probe = probe()

        assertTrue(probe.playTone(440.0, 2000) { fake })

        assertEquals("a playing track is kept, not released", 0, fake.releaseCount)
        assertEquals(1, fake.playCount)

        probe.stopTone()

        assertEquals("stopping releases it once", 1, fake.releaseCount)
        assertEquals(1, fake.stopCount)
    }

    @Test
    fun aSecondToneReleasesTheFirstTrackOnce() {
        val probe = probe()
        val first = FakeTrack(FailsAt.NEVER)
        val second = FakeTrack(FailsAt.NEVER)

        assertTrue(probe.playTone(440.0, 1000) { first })
        assertTrue(probe.playTone(440.0, 1000) { second })

        assertEquals("the superseded track is released, once", 1, first.releaseCount)
        assertEquals("the new track is the one playing", 0, second.releaseCount)
        assertEquals(1, second.playCount)
    }

    @Test
    fun aFailedToneLeavesNothingForReleaseToFree() {
        val probe = probe()
        val fake = FakeTrack(FailsAt.WRITE)

        assertFalse(probe.playTone(440.0, 2000) { fake })
        probe.release()

        assertEquals("the failed track was already forgotten, so release() frees nothing", 1, fake.releaseCount)
    }

    @Test
    fun theToneStillHasTheSameSamples() {
        var samples: ShortArray? = null
        val probe = probe()

        assertTrue(probe.playTone(440.0, 2000) { buffer -> samples = buffer; FakeTrack(FailsAt.NEVER) })

        val tone = samples ?: throw AssertionError("no samples were generated")
        assertEquals("two seconds at 44.1 kHz", 88_200, tone.size)
        assertEquals("the sine starts at zero", 0, tone[0].toInt())
        val peak = tone.maxOf { abs(it.toInt()) }
        assertTrue(
            "a full-scale sine at 60% amplitude, but the peak was $peak",
            peak in 19_650..19_661
        )
    }
}
