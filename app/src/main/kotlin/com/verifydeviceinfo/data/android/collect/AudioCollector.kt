package com.verifydeviceinfo.data.android.collect

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup

data class AudioFacts(
    val speakerAvailable: Boolean,
    val microphoneAvailable: Boolean,
    val vibratorAvailable: Boolean,
    val fields: List<SpecField>
)

/** Audio endpoints and haptics, from AudioManager and the vibrator service. */
class AudioCollector(
    private val context: Context,
    /**
     * Reads the output device list from the audio service.
     *
     * Injectable only so the read-failure path can be driven by a test without a device:
     * production always uses `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`. It is an
     * `AudioManager ->` lambda rather than a `() ->` one so the manager lookup stays in [collect],
     * where it already was.
     */
    private val outputDevices: (AudioManager) -> List<AudioDeviceInfo> =
        { it.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() },
    /** The input list, read the same way; see [outputDevices]. */
    private val inputDevices: (AudioManager) -> List<AudioDeviceInfo> =
        { it.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() },
    /**
     * Reads whether the device has a vibration motor.
     *
     * Injectable for the same reason as [outputDevices]: production always goes through the
     * vibrator service, and only a test can make that read throw. It takes the `Context` rather
     * than closing over it so the service lookup stays in the lambda, where it already was.
     */
    private val hasVibrator: (Context) -> Boolean = { context ->
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator?.hasVibrator() == true
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true
        }
    }
) {

    fun collect(): AudioFacts {
        val fields = mutableListOf<SpecField>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // A refused list is not an empty one. Both reads used to collapse to `emptyList()` in their
        // own `catch`, which turned a throwing `getDevices` into three "Not reported" rows — a claim
        // of absence drawn from a read that never answered, contradicted by the two list rows below
        // it, which fell to "Unavailable". Reading through `attempt` keeps the reason so the rows
        // that depend on the list can say "Unreadable" instead.
        val outputs = audioManager
            ?.let { manager -> attempt { outputDevices(manager) } }
            ?: Reading(emptyList<AudioDeviceInfo>(), null)
        val inputs = audioManager
            ?.let { manager -> attempt { inputDevices(manager) } }
            ?: Reading(emptyList<AudioDeviceInfo>(), null)

        val outputList = outputs.value.orEmpty()
        val inputList = inputs.value.orEmpty()

        val speaker = outputList.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val earpiece = outputList.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        val microphone = inputList.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        fields += FieldBuilder.value(
            "audio.outputDevices", "Audio outputs",
            outputList.joinToString(", ") { deviceName(it) }.ifBlank { null },
            SpecGroup.AUDIO, "AudioManager.getDevices(GET_DEVICES_OUTPUTS)",
            note = outputs.failure?.let { readFailureNote("output", it) }
        )
        fields += FieldBuilder.value(
            "audio.inputDevices", "Audio inputs",
            inputList.joinToString(", ") { deviceName(it) }.ifBlank { null },
            SpecGroup.AUDIO, "AudioManager.getDevices(GET_DEVICES_INPUTS)",
            note = inputs.failure?.let { readFailureNote("input", it) }
        )
        fields += presence(
            "audio.speaker", "Built-in speaker", speaker, outputs.failure,
            "output", "AudioDeviceInfo.TYPE_BUILTIN_SPEAKER"
        )
        fields += presence(
            "audio.earpiece", "Earpiece", earpiece, outputs.failure,
            "output", "AudioDeviceInfo.TYPE_BUILTIN_EARPIECE"
        )
        fields += presence(
            "audio.microphone", "Built-in microphone", microphone, inputs.failure,
            "input", "AudioDeviceInfo.TYPE_BUILTIN_MIC"
        )
        fields += FieldBuilder.value(
            "audio.sampleRate", "Native output sample rate",
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.let { "$it Hz" },
            SpecGroup.AUDIO, "AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE", Confidence.MEDIUM
        )
        fields += FieldBuilder.value(
            "audio.framesPerBuffer", "Output frames per buffer",
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER),
            SpecGroup.AUDIO, "AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER", Confidence.MEDIUM
        )
        fields += FieldBuilder.value(
            "audio.volumeMusic", "Music volume",
            audioManager?.let { "${it.getStreamVolume(AudioManager.STREAM_MUSIC)} / ${it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" },
            SpecGroup.AUDIO, "AudioManager.getStreamVolume()"
        )

        // A refused haptics read is not a device without a vibration motor. The read used to
        // collapse to `false` in its own `catch`, which printed "Not reported" for a service that
        // never answered — the same claim of absence Z3 removed from the three presence rows above.
        val vibration = attempt { hasVibrator(context) }
        val vibratorAvailable = vibration.value == true
        fields += if (vibration.failure != null) {
            FieldBuilder.value(
                "audio.vibrator", "Vibration motor", null, SpecGroup.AUDIO, "Vibrator.hasVibrator()",
                note = "Unreadable: haptics read failed: " + failureName(vibration.failure)
            )
        } else {
            FieldBuilder.value(
                "audio.vibrator", "Vibration motor",
                if (vibratorAvailable) "Present" else "Not reported",
                SpecGroup.AUDIO, "Vibrator.hasVibrator()"
            )
        }
        fields += FieldBuilder.value(
            "audio.hapticFeedback", "Haptic feedback setting",
            try {
                // The setting key is deprecated without a replacement; the row still reports the
                // same stored value, and the key name is part of the row's own label.
                @Suppress("DEPRECATION")
                val hapticFeedback = android.provider.Settings.System.getInt(
                    context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1
                )
                if (hapticFeedback == 1) "Enabled" else "Disabled"
            } catch (e: Exception) {
                null
            },
            SpecGroup.AUDIO, "Settings.System.HAPTIC_FEEDBACK_ENABLED", Confidence.MEDIUM
        )

        return AudioFacts(speaker, microphone, vibratorAvailable, fields)
    }

    /**
     * A presence row: "Present" or "Not reported" from a list that answered, and a read failure
     * instead of "Not reported" from a list that did not.
     */
    private fun presence(
        key: String,
        label: String,
        present: Boolean,
        failure: Exception?,
        direction: String,
        source: String
    ): SpecField = if (failure != null) {
        FieldBuilder.value(
            key, label, null, SpecGroup.AUDIO, source,
            note = readFailureNote(direction, failure)
        )
    } else {
        FieldBuilder.value(key, label, if (present) "Present" else "Not reported", SpecGroup.AUDIO, source)
    }

    /** The note a row carries when the device list it was derived from could not be read. */
    private fun readFailureNote(direction: String, failure: Exception): String =
        "Unreadable: audio $direction device list failed: " + failureName(failure)

    /** An exception's message, or its class name when it has none, so a note never says "null". */
    private fun failureName(failure: Exception): String =
        failure.message ?: failure.javaClass.simpleName

    private fun deviceName(device: AudioDeviceInfo): String {
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Type ${device.type}"
        }
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
        return if (name != null) "$type ($name)" else type
    }
}
