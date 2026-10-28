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
class AudioCollector(private val context: Context) {

    fun collect(): AudioFacts {
        val fields = mutableListOf<SpecField>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val outputs = try {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val inputs = try {
            audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        val speaker = outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val earpiece = outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        val microphone = inputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        fields += FieldBuilder.value(
            "audio.outputDevices", "Audio outputs",
            outputs.joinToString(", ") { deviceName(it) }.ifBlank { null },
            SpecGroup.AUDIO, "AudioManager.getDevices(GET_DEVICES_OUTPUTS)"
        )
        fields += FieldBuilder.value(
            "audio.inputDevices", "Audio inputs",
            inputs.joinToString(", ") { deviceName(it) }.ifBlank { null },
            SpecGroup.AUDIO, "AudioManager.getDevices(GET_DEVICES_INPUTS)"
        )
        fields += FieldBuilder.value(
            "audio.speaker", "Built-in speaker", if (speaker) "Present" else "Not reported",
            SpecGroup.AUDIO, "AudioDeviceInfo.TYPE_BUILTIN_SPEAKER"
        )
        fields += FieldBuilder.value(
            "audio.earpiece", "Earpiece", if (earpiece) "Present" else "Not reported",
            SpecGroup.AUDIO, "AudioDeviceInfo.TYPE_BUILTIN_EARPIECE"
        )
        fields += FieldBuilder.value(
            "audio.microphone", "Built-in microphone", if (microphone) "Present" else "Not reported",
            SpecGroup.AUDIO, "AudioDeviceInfo.TYPE_BUILTIN_MIC"
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

        val vibratorAvailable = try {
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator?.hasVibrator() == true
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true
            }
        } catch (e: Exception) {
            false
        }
        fields += FieldBuilder.value(
            "audio.vibrator", "Vibration motor", if (vibratorAvailable) "Present" else "Not reported",
            SpecGroup.AUDIO, "Vibrator.hasVibrator()"
        )
        fields += FieldBuilder.value(
            "audio.hapticFeedback", "Haptic feedback setting",
            try {
                if (android.provider.Settings.System.getInt(
                        context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1
                    ) == 1
                ) "Enabled" else "Disabled"
            } catch (e: Exception) {
                null
            },
            SpecGroup.AUDIO, "Settings.System.HAPTIC_FEEDBACK_ENABLED", Confidence.MEDIUM
        )

        return AudioFacts(speaker, microphone, vibratorAvailable, fields)
    }

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
