package com.verifydeviceinfo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.data.android.collect.AudioCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the audio section reports when the audio service refuses to list its devices.
 *
 * `AudioCollector` read the output and input lists in two `try`/`catch` blocks that both collapsed
 * to `emptyList()`, and the three presence rows then printed `"Not reported"` for the built-in
 * speaker, the earpiece and the microphone — a claim of absence drawn from a read that threw, and
 * one the section contradicted two rows above it, where the list rows fell to `"Unavailable"`.
 * This is the camera defect (Z1) and the sensor defect (Z2) in the last of the three collectors.
 *
 * Both reads are now [AudioCollector] constructor parameters so the failure can be driven here:
 * production passes nothing and keeps using `AudioManager.getDevices(...)`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AudioCollectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun row(fields: List<SpecField>, key: String): SpecField = fields.first { it.key == key }

    private fun collector(
        outputs: (AudioManager) -> List<AudioDeviceInfo> = { emptyList() },
        inputs: (AudioManager) -> List<AudioDeviceInfo> = { emptyList() }
    ) = AudioCollector(context, outputs, inputs)

    @Test
    fun `a refused output list is not reported as a device with no speaker`() {
        val facts = collector(outputs = { throw IllegalStateException("audio service refused") }).collect()

        val speaker = row(facts.fields, "audio.speaker")
        assertEquals(DiagnosticStatus.UNVERIFIED, speaker.status)
        assertEquals(Confidence.NONE, speaker.confidence)
        assertEquals("Unavailable", speaker.value)
        assertFalse(speaker.value == "Not reported")
        assertEquals("Unreadable: audio output device list failed: audio service refused", speaker.note)

        val earpiece = row(facts.fields, "audio.earpiece")
        assertEquals(DiagnosticStatus.UNVERIFIED, earpiece.status)
        assertEquals("Unavailable", earpiece.value)
        assertEquals("Unreadable: audio output device list failed: audio service refused", earpiece.note)

        val outputs = row(facts.fields, "audio.outputDevices")
        assertEquals("Unavailable", outputs.value)
        assertEquals("Unreadable: audio output device list failed: audio service refused", outputs.note)
    }

    @Test
    fun `a refused input list is not reported as a device with no microphone`() {
        val facts = collector(inputs = { throw IllegalStateException("audio service refused") }).collect()

        val microphone = row(facts.fields, "audio.microphone")
        assertEquals(DiagnosticStatus.UNVERIFIED, microphone.status)
        assertEquals(Confidence.NONE, microphone.confidence)
        assertEquals("Unavailable", microphone.value)
        assertFalse(microphone.value == "Not reported")
        assertEquals("Unreadable: audio input device list failed: audio service refused", microphone.note)

        val inputs = row(facts.fields, "audio.inputDevices")
        assertEquals("Unavailable", inputs.value)
        assertEquals("Unreadable: audio input device list failed: audio service refused", inputs.note)
    }

    @Test
    fun `one refused list leaves the rows that depend on the other list alone`() {
        val facts = collector(outputs = { throw IllegalStateException("outputs refused") }).collect()

        // The input list answered, so the microphone row still makes its honest claim.
        val microphone = row(facts.fields, "audio.microphone")
        assertEquals("Not reported", microphone.value)
        assertEquals(DiagnosticStatus.VERIFIED, microphone.status)
        assertNull(microphone.note)

        // The input list row carries no note either, since nothing failed there.
        assertNull(row(facts.fields, "audio.inputDevices").note)
    }

    @Test
    fun `an exception with no message still names what failed`() {
        val facts = collector(inputs = { throw IllegalStateException() }).collect()

        assertEquals(
            "Unreadable: audio input device list failed: IllegalStateException",
            row(facts.fields, "audio.microphone").note
        )
    }

    @Test
    fun `a read that answers with no devices still says Not reported`() {
        // The unchanged path: an empty list that was actually read is a measurement, and every row
        // renders exactly what it rendered before this change.
        val facts = collector().collect()

        listOf("audio.speaker", "audio.earpiece", "audio.microphone").forEach { key ->
            val presence = row(facts.fields, key)
            assertEquals("Not reported", presence.value)
            assertEquals(DiagnosticStatus.VERIFIED, presence.status)
            assertNull(presence.note)
        }
        listOf("audio.outputDevices", "audio.inputDevices").forEach { key ->
            val listRow = row(facts.fields, key)
            assertEquals("Unavailable", listRow.value)
            assertNull(listRow.note)
        }
        assertFalse(facts.speakerAvailable)
        assertFalse(facts.microphoneAvailable)
    }

    @Test
    fun `a failed read still leaves the presence flags false`() {
        // The score engine reads these flags, and this item deliberately does not move a score:
        // a device whose list could not be read scores what it scored before.
        val facts = collector(
            outputs = { throw IllegalStateException("outputs refused") },
            inputs = { throw IllegalStateException("inputs refused") }
        ).collect()

        assertFalse(facts.speakerAvailable)
        assertFalse(facts.microphoneAvailable)
        assertTrue(facts.fields.isNotEmpty())
    }
}
