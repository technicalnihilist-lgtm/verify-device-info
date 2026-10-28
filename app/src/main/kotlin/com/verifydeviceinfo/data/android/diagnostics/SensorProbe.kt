package com.verifydeviceinfo.data.android.diagnostics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Registers a sensor listener for interactive tests and for the automated liveness probe. */
class SensorProbe(private val context: Context) {

    private val manager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var listener: SensorEventListener? = null

    fun sensor(type: Int): Sensor? = try {
        manager?.getDefaultSensor(type)
    } catch (e: Exception) {
        null
    }

    fun hasSensor(type: Int): Boolean = sensor(type) != null

    /** Starts streaming readings. Returns false when the sensor does not exist. */
    fun start(type: Int, onReading: (FloatArray) -> Unit): Boolean {
        val sensor = sensor(type) ?: return false
        stop()
        val newListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                onReading(event.values.copyOf())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = newListener
        return try {
            manager?.registerListener(newListener, sensor, SensorManager.SENSOR_DELAY_GAME) == true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        listener?.let {
            try {
                manager?.unregisterListener(it)
            } catch (ignored: Exception) {
            }
        }
        listener = null
    }

    /**
     * Confirms a sensor actually produces data. Used by the automated pass — a sensor that is
     * listed but never reports is not working.
     */
    suspend fun probeLiveness(type: Int, timeoutMillis: Long = 1500): FloatArray? =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<FloatArray?> { continuation ->
                val sensor = sensor(type)
                if (sensor == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                val probeListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        try {
                            manager?.unregisterListener(this)
                        } catch (ignored: Exception) {
                        }
                        if (continuation.isActive) continuation.resume(event.values.copyOf())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                val registered = try {
                    manager?.registerListener(probeListener, sensor, SensorManager.SENSOR_DELAY_UI) == true
                } catch (e: Exception) {
                    false
                }
                if (!registered) {
                    continuation.resume(null)
                }
                continuation.invokeOnCancellation {
                    try {
                        manager?.unregisterListener(probeListener)
                    } catch (ignored: Exception) {
                    }
                }
            }
        }
}
