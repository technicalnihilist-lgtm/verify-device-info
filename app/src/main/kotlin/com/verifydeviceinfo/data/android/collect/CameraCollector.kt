package com.verifydeviceinfo.data.android.collect

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.verifydeviceinfo.core.model.CameraFacts
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import java.util.Locale

/**
 * Enumerates cameras through Camera2. Reading characteristics does not require the camera
 * permission; opening a camera for the interactive test does.
 */
class CameraCollector(private val context: Context) {

    fun collect(): Pair<List<CameraFacts>, List<SpecField>> {
        val fields = mutableListOf<SpecField>()
        val cameras = mutableListOf<CameraFacts>()

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList<CameraFacts>() to listOf(
                FieldBuilder.unsupported(
                    "camera.list", "Camera modules", SpecGroup.CAMERA, "CameraManager",
                    "The camera service is not available on this device."
                )
            )

        try {
            val ids = manager.cameraIdList
            ids.forEach { id ->
                try {
                    val characteristics = manager.getCameraCharacteristics(id)
                    val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN"
                    }
                    val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val jpegSizes = streamMap?.getOutputSizes(ImageFormat.JPEG).orEmpty()
                    val largest = jpegSizes.maxByOrNull { it.width.toLong() * it.height } ?: pixelArray
                    val megapixels = if (largest != null) {
                        (largest.width.toDouble() * largest.height.toDouble()) / 1_000_000.0
                    } else 0.0
                    val pixelSize = if (physicalSize != null && pixelArray != null && pixelArray.width > 0) {
                        (physicalSize.width / pixelArray.width * 1000.0)
                    } else null
                    val hardwareLevel = when (characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
                        else -> "Unknown"
                    }
                    val physicalIds = if (Build.VERSION.SDK_INT >= 28) {
                        try {
                            characteristics.physicalCameraIds.toList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    } else emptyList()

                    cameras += CameraFacts(
                        id = id,
                        lensFacing = facing,
                        sensorWidthPx = pixelArray?.width ?: 0,
                        sensorHeightPx = pixelArray?.height ?: 0,
                        maxJpegWidth = largest?.width ?: 0,
                        maxJpegHeight = largest?.height ?: 0,
                        megapixels = megapixels,
                        flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                        hardwareLevel = hardwareLevel,
                        apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
                        focalLengthsMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
                        physicalCameraIds = physicalIds,
                        pixelSizeMicrons = pixelSize
                    )
                } catch (e: Exception) {
                    fields += FieldBuilder.value(
                        "camera.$id.error", "Camera $id", null, SpecGroup.CAMERA, "CameraManager",
                        note = "Could not read characteristics: ${e.message}"
                    )
                }
            }
        } catch (e: Exception) {
            fields += FieldBuilder.unsupported(
                "camera.list", "Camera modules", SpecGroup.CAMERA, "CameraManager",
                "Camera enumeration failed: ${e.message}"
            )
        }

        fields += FieldBuilder.value(
            "camera.count", "Camera modules enumerated",
            "${cameras.size} (${cameras.count { it.lensFacing == "BACK" }} rear, " +
                "${cameras.count { it.lensFacing == "FRONT" }} front)",
            SpecGroup.CAMERA, "CameraManager.cameraIdList"
        )
        cameras.forEach { camera ->
            val label = "${camera.lensFacing.lowercase(Locale.US).replaceFirstChar { it.uppercase() }} camera ${camera.id}"
            fields += FieldBuilder.value(
                "camera.${camera.id}.megapixels", "$label resolution",
                if (camera.megapixels > 0) String.format(Locale.US, "%.1f MP", camera.megapixels) else null,
                SpecGroup.CAMERA, "CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP",
                note = "Maximum JPEG output size, which can differ from the sensor's native resolution " +
                    "when the vendor exposes a reduced stream configuration."
            )
            fields += FieldBuilder.value(
                "camera.${camera.id}.sensor", "$label sensor array",
                if (camera.sensorWidthPx > 0) "${camera.sensorWidthPx} x ${camera.sensorHeightPx} px" else null,
                SpecGroup.CAMERA, "CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE"
            )
            fields += FieldBuilder.value(
                "camera.${camera.id}.aperture", "$label aperture",
                camera.apertures.joinToString(", ") { String.format(Locale.US, "f/%.1f", it) }
                    .ifBlank { null },
                SpecGroup.CAMERA, "CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES"
            )
            fields += FieldBuilder.value(
                "camera.${camera.id}.focal", "$label focal length",
                camera.focalLengthsMm.joinToString(", ") { String.format(Locale.US, "%.2f mm", it) }
                    .ifBlank { null },
                SpecGroup.CAMERA, "CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS"
            )
            fields += FieldBuilder.value(
                "camera.${camera.id}.flash", "$label flash",
                if (camera.flashAvailable) "Available" else "Not available", SpecGroup.CAMERA,
                "CameraCharacteristics.FLASH_INFO_AVAILABLE"
            )
            fields += FieldBuilder.value(
                "camera.${camera.id}.level", "$label hardware level", camera.hardwareLevel, SpecGroup.CAMERA,
                "CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL",
                Confidence.MEDIUM
            )
            fields += FieldBuilder.value(
                "camera.${camera.id}.pixelSize", "$label pixel size",
                camera.pixelSizeMicrons?.let { String.format(Locale.US, "%.2f µm", it) }, SpecGroup.CAMERA,
                "Derived from SENSOR_INFO_PHYSICAL_SIZE", Confidence.LOW,
                com.verifydeviceinfo.core.model.Verification.INFERRED
            )
            if (camera.physicalCameraIds.isNotEmpty()) {
                fields += FieldBuilder.value(
                    "camera.${camera.id}.physical", "$label physical sub-cameras",
                    camera.physicalCameraIds.joinToString(", "), SpecGroup.CAMERA,
                    "CameraCharacteristics.getPhysicalCameraIds()"
                )
            }
        }

        return cameras to fields
    }
}
