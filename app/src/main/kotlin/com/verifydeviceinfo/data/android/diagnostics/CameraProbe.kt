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
 */
class CameraProbe(private val context: Context) {

    private val manager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val handler = Handler(Looper.getMainLooper())

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

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
        try {
            manager?.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                            }
                            camera.createCaptureSession(
                                listOf(surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(configured: CameraCaptureSession) {
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
                                        onError("Camera session configuration failed.")
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
                        device = null
                        onError("Camera disconnected.")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        device = null
                        onError("Camera error code $error.")
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
        try {
            session?.close()
        } catch (ignored: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (ignored: Exception) {
        }
        device = null
    }
}
