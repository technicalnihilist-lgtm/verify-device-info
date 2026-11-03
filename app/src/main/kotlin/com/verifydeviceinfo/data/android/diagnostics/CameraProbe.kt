package com.verifydeviceinfo.data.android.diagnostics

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * Live camera preview and torch control for the interactive camera test.
 * Requires the camera permission; failures are surfaced to the caller rather than hidden.
 *
 * Opening a camera and configuring a session are both asynchronous, so the screen can be left
 * before either callback runs. Every callback therefore carries the token of the open it belongs
 * to, and anything that arrives after [close] is closed immediately instead of being kept — see
 * [StaleCloseGuard].
 */
class CameraProbe(private val context: Context) {

    private val manager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val handler = Handler(Looper.getMainLooper())

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private val devices = StaleCloseGuard<CameraDevice>()
    private val sessions = StaleCloseGuard<CameraCaptureSession>()

    fun hasFlash(cameraId: String): Boolean = try {
        manager?.getCameraCharacteristics(cameraId)
            ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } catch (e: Exception) {
        false
    }

    fun setTorch(cameraId: String, on: Boolean): Boolean = try {
        manager?.setTorchMode(cameraId, on) ?: false
        true
    } catch (e: Exception) {
        false
    }

    fun open(cameraId: String, surface: Surface, onError: (String) -> Unit) {
        close()
        // Taken after close(): a callback belonging to this open is current until the next close().
        val deviceToken = devices.token()
        val sessionToken = sessions.token()
        try {
            manager?.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!devices.admit(deviceToken, camera)) return
                        device = camera
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                            }
                            camera.createCaptureSession(
                                listOf(surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(configured: CameraCaptureSession) {
                                        if (!sessions.admit(sessionToken, configured)) return
                                        session = configured
                                        try {
                                            configured.setRepeatingRequest(
                                                request.build(), null, handler
                                            )
                                        } catch (e: Exception) {
                                            onError("Preview request failed: ${e.message}")
                                        }
                                    }

                                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                                        // A session that failed to configure still has to be closed.
                                        closeQuietly(failed)
                                        if (sessions.isCurrent(sessionToken)) {
                                            onError("Camera session configuration failed.")
                                        }
                                    }
                                },
                                handler
                            )
                        } catch (e: Exception) {
                            onError("Could not start preview: ${e.message}")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        // Only the camera this probe is holding may clear the field: a late
                        // callback for an earlier open must not drop the current one.
                        if (device === camera) device = null
                        if (devices.isCurrent(deviceToken)) onError("Camera disconnected.")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (device === camera) device = null
                        if (devices.isCurrent(deviceToken)) onError("Camera error code $error.")
                    }
                },
                handler
            )
        } catch (security: SecurityException) {
            onError("Camera permission is required to run this test.")
        } catch (e: Exception) {
            onError("Could not open the camera: ${e.message}")
        }
    }

    fun close() {
        // Invalidate first: a device or session that is still in flight will be closed on arrival.
        devices.invalidate()
        sessions.invalidate()
        val heldSession = session
        session = null
        closeQuietly(heldSession)
        val heldDevice = device
        device = null
        closeQuietly(heldDevice)
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (ignored: Exception) {
        }
    }
}
