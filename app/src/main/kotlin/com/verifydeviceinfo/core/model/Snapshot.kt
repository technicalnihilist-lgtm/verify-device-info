package com.verifydeviceinfo.core.model

/**
 * Structured facts collected from the device. The flat [fields] list powers the A-to-Z
 * specification browser; the typed groups below feed the analysis engines.
 */
data class DeviceSnapshot(
    val platform: Platform,
    val fields: List<SpecField>,
    val identity: DeviceIdentity,
    val hardware: HardwareFacts,
    val battery: BatteryFacts,
    val security: SecurityFacts,
    val software: SoftwareFacts,
    val collectedAtMillis: Long,
    val appVersion: String,
    val collectionErrors: List<String> = emptyList(),
    val isMock: Boolean = false
) {
    fun field(key: String): SpecField? = fields.firstOrNull { it.key == key }
    fun valueOf(key: String): String? = field(key)?.value
    fun fieldsIn(group: SpecGroup): List<SpecField> = fields.filter { it.group == group }
}

data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val marketingName: String?,
    val device: String,
    val product: String,
    val hardware: String,
    val board: String,
    val fingerprint: String,
    val buildId: String,
    val buildDisplay: String,
    val bootloader: String?,
    val buildTags: String,
    val buildType: String,
    val buildHost: String?,
    val buildUser: String?,
    val buildTimeMillis: Long?,
    val serial: String?,
    val socManufacturer: String?,
    val socModel: String?
)

data class DisplayFacts(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val supportedRefreshRates: List<Float>,
    val hdrTypes: List<String>,
    val xdpi: Float,
    val ydpi: Float,
    val diagonalInches: Float,
    val wideColorGamut: Boolean
)

data class CameraFacts(
    val id: String,
    val lensFacing: String,
    val sensorWidthPx: Int,
    val sensorHeightPx: Int,
    val maxJpegWidth: Int,
    val maxJpegHeight: Int,
    val megapixels: Double,
    val flashAvailable: Boolean,
    val hardwareLevel: String,
    val apertures: List<Float>,
    val focalLengthsMm: List<Float>,
    val physicalCameraIds: List<String>,
    val pixelSizeMicrons: Double?
)

data class SensorFacts(
    val name: String,
    val vendor: String,
    val version: Int,
    val type: Int,
    val stringType: String,
    val category: String,
    val maxRange: Float,
    val resolution: Float,
    val powerMilliAmp: Float,
    val wakeUpSensor: Boolean,
    val reportingMode: String
)

data class HardwareFacts(
    val socManufacturer: String?,
    val socModel: String?,
    val cpuAbis: List<String>,
    val cpuCores: Int,
    val cpuMaxFreqKhz: Long?,
    val cpuHardware: String,
    val gpuRenderer: String?,
    val gpuVendor: String?,
    val glesVersion: String?,
    val vulkanSupported: Boolean?,
    val ramTotalBytes: Long,
    val ramAvailableBytes: Long,
    val isLowRamDevice: Boolean,
    val memoryClassMb: Int,
    val storageTotalBytes: Long,
    val storageAvailableBytes: Long,
    val display: DisplayFacts?,
    val cameras: List<CameraFacts>,
    val sensors: List<SensorFacts>,
    val biometricStrongAvailable: Boolean,
    val biometricAnyAvailable: Boolean,
    val strongBoxAvailable: Boolean,
    val secureLockScreen: Boolean,
    val vibratorAvailable: Boolean,
    val speakerAvailable: Boolean,
    val microphoneAvailable: Boolean,
    val nfcAvailable: Boolean,
    val bluetoothAvailable: Boolean,
    val wifiAvailable: Boolean,
    val gnssAvailable: Boolean,
    val telephonyAvailable: Boolean,
    val usbHostAvailable: Boolean
) {
    val rearCameraMegapixels: Double
        get() = cameras.filter { it.lensFacing == "BACK" }.maxOfOrNull { it.megapixels } ?: 0.0
    val frontCameraMegapixels: Double
        get() = cameras.filter { it.lensFacing == "FRONT" }.maxOfOrNull { it.megapixels } ?: 0.0
    val rearCameraCount: Int get() = cameras.count { it.lensFacing == "BACK" }
    val frontCameraCount: Int get() = cameras.count { it.lensFacing == "FRONT" }
}

data class BatteryFacts(
    val percent: Int?,
    val health: String,
    val healthCode: Int,
    val temperatureCelsius: Float?,
    val voltageMillivolts: Int?,
    val technology: String?,
    val plugged: String,
    val status: String,
    val designCapacityMah: Int?,
    val chargeCounterMicroAmpHours: Long?,
    val currentNowMicroAmps: Long?,
    val cycleCount: Int?,
    val powerProfileDesignCapacityMah: Int?,
    val healthPercent: Int?
)

data class SecurityFacts(
    val rootIndicators: List<String>,
    val bootloaderUnlocked: Boolean?,
    val verifiedBootState: String?,
    val debuggable: Boolean,
    val developerOptionsEnabled: Boolean?,
    val adbEnabled: Boolean?,
    val selinuxMode: String?,
    val encryptionState: String,
    val hardwareKeystoreAvailable: Boolean,
    val attestationSupported: Boolean,
    val playIntegrityStatus: String,
    val customRomSuspected: Boolean,
    val installedFromTrustedStore: Boolean,
    val installerPackage: String?,
    val signatureDigestSha256: String?
)

data class SoftwareFacts(
    val androidRelease: String,
    val apiLevel: Int,
    val securityPatch: String?,
    val buildIncremental: String?,
    val kernelVersion: String?,
    val installedPackageCount: Int,
    val systemPackageCount: Int,
    val userPackageCount: Int,
    val packageVisibilityRestricted: Boolean,
    val uptimeMillis: Long,
    val bootTimeMillis: Long?,
    val thermalStatus: String,
    val thermalStatusSupported: Boolean,
    val locale: String,
    val timeZone: String
)
