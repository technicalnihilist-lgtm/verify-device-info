package com.verifydeviceinfo.data.android.diagnostics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sin
import kotlin.math.max

/** Speaker tone generation and microphone level measurement for the interactive tests. */
class AudioProbe(private val context: Context) {

    private var toneGenerator: ToneGenerator? = null
    private var audioTrack: AudioTrack? = null
    private var recorder: MediaRecorder? = null

    /** Plays a clean sine tone; returns false if audio output could not be started. */
    fun playTone(frequencyHz: Double = 440.0, durationMs: Int = 2000): Boolean = try {
        stopTone()
        val sampleRate = 44100
        val count = (sampleRate * durationMs / 1000)
        val samples = ShortArray(count)
        for (i in 0 until count) {
            samples[i] = (sin(2.0 * Math.PI * i * frequencyHz / sampleRate) * Short.MAX_VALUE * 0.6).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        audioTrack = track
        true
    } catch (e: Exception) {
        stopTone()
        false
    }

    fun stopTone() {
        try {
            audioTrack?.stop()
        } catch (ignored: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (ignored: Exception) {
        }
        audioTrack = null
    }

    /** Plays the platform's built-in DTMF tone as an alternative to the generated sine. */
    fun playBeep(durationMs: Int = 1000): Boolean = try {
        val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        generator.startTone(ToneGenerator.TONE_DTMF_5, durationMs)
        toneGenerator = generator
        true
    } catch (e: Exception) {
        false
    }

    fun stopBeep() {
        try {
            toneGenerator?.release()
        } catch (ignored: Exception) {
        }
        toneGenerator = null
    }

    /**
     * Records a short sample and returns the peak amplitude (0-32767) together with the average.
     * Requires the microphone permission; callers must handle a SecurityException result.
     */
    suspend fun measureMicrophone(durationMs: Int = 3000): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val output = File(context.cacheDir, "mic-probe.3gp")
        try {
            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            mediaRecorder.setOutputFile(output.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder

            var peak = 0
            var sum = 0L
            var samples = 0
            val steps = max(1, durationMs / 200)
            repeat(steps) {
                delay(200)
                val amplitude = try {
                    mediaRecorder.maxAmplitude
                } catch (e: Exception) {
                    0
                }
                peak = max(peak, amplitude)
                sum += amplitude
                samples++
            }
            mediaRecorder.stop()
            mediaRecorder.release()
            recorder = null
            val average = if (samples == 0) 0 else (sum / samples).toInt()
            peak to average
        } catch (security: SecurityException) {
            null
        } catch (e: Exception) {
            try {
                recorder?.release()
            } catch (ignored: Exception) {
            }
            recorder = null
            null
        } finally {
            output.delete()
        }
    }

    fun release() {
        stopTone()
        stopBeep()
        try {
            recorder?.release()
        } catch (ignored: Exception) {
        }
        recorder = null
    }
}
