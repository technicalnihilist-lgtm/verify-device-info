package com.verifydeviceinfo.data.android

import android.content.Context
import com.verifydeviceinfo.BuildConfig
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.HardwareFacts
import com.verifydeviceinfo.core.model.Platform
import com.verifydeviceinfo.core.model.ScanProgress
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.data.android.collect.AudioCollector
import com.verifydeviceinfo.data.android.collect.BatteryCollector
import com.verifydeviceinfo.data.android.collect.CameraCollector
import com.verifydeviceinfo.data.android.collect.ConnectivityCollector
import com.verifydeviceinfo.data.android.collect.HardwareCollector
import com.verifydeviceinfo.data.android.collect.IdentityCollector
import com.verifydeviceinfo.data.android.collect.SecurityCollector
import com.verifydeviceinfo.data.android.collect.SensorCollector
import com.verifydeviceinfo.data.android.collect.SoftwareCollector
import com.verifydeviceinfo.domain.DeviceInformationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects a complete snapshot from Android. Every stage reports progress only when it has
 * actually completed, so the scanning animation cannot outrun the real work.
 */
class AndroidDeviceInformationProvider(
    private val context: Context
) : DeviceInformationProvider {

    override val platform: Platform = Platform.ANDROID

    private val props = SystemPropertyReader()

    override suspend fun collect(onProgress: (ScanProgress) -> Unit): DeviceSnapshot =
        withContext(Dispatchers.IO) {
            val total = 8
            var done = 0
            val errors = mutableListOf<String>()
            val fields = mutableListOf<SpecField>()

            fun stage(label: String) {
                done++
                onProgress(ScanProgress(done, total, label, finished = done == total))
            }

            // 1. Identity and build properties
            val identityResult = try {
                IdentityCollector(props).collect()
            } catch (e: Exception) {
                errors += "Identity collection failed: ${e.message}"
                null
            }
            identityResult?.let {
                fields += it.fields
                fields += it.softwareFields
            }
            stage("Device identity and build properties")

            // 2. Processor, memory, storage, display
            val hardwareResult = try {
                HardwareCollector(context).collect()
            } catch (e: Exception) {
                errors += "Hardware collection failed: ${e.message}"
                null
            }
            hardwareResult?.let {
                fields += it.fields
                errors += it.errors
            }
            stage("Processor, memory and storage")

            // 3. Battery
            val batteryPair = try {
                BatteryCollector(context).collect()
            } catch (e: Exception) {
                errors += "Battery collection failed: ${e.message}"
                null
            }
            batteryPair?.let { fields += it.second }
            stage("Battery and power")

            // 4. Cameras
            val cameraPair = try {
                CameraCollector(context).collect()
            } catch (e: Exception) {
                errors += "Camera collection failed: ${e.message}"
                null
            }
            cameraPair?.let { fields += it.second }
            stage("Camera modules")

            // 5. Sensors
            val sensorPair = try {
                SensorCollector(context).collect()
            } catch (e: Exception) {
                errors += "Sensor collection failed: ${e.message}"
                null
            }
            sensorPair?.let { fields += it.second }
            stage("Sensors")

            // 6. Connectivity
            val connectivity = try {
                ConnectivityCollector(context).collect()
            } catch (e: Exception) {
                errors += "Connectivity collection failed: ${e.message}"
                null
            }
            connectivity?.let { fields += it.fields }

            // 7. Audio and haptics
            val audio = try {
                AudioCollector(context).collect()
            } catch (e: Exception) {
                errors += "Audio collection failed: ${e.message}"
                null
            }
            audio?.let { fields += it.fields }
            stage("Connectivity and audio")

            // 8. Security, integrity and software inventory
            val securityResult = try {
                SecurityCollector(context, props).collect()
            } catch (e: Exception) {
                errors += "Security collection failed: ${e.message}"
                null
            }
            securityResult?.let {
                fields += it.fields
                fields += it.biometricFields
            }
            val softwarePair = try {
                SoftwareCollector(context).collect()
            } catch (e: Exception) {
                errors += "Software inventory failed: ${e.message}"
                null
            }
            softwarePair?.let { fields += it.second }
            stage("Security and installed software")

            val hardware = HardwareFacts(
                socManufacturer = identityResult?.identity?.socManufacturer,
                socModel = identityResult?.identity?.socModel,
                cpuAbis = hardwareResult?.cpuAbis.orEmpty(),
                cpuCores = hardwareResult?.cpuCores ?: 0,
                cpuMaxFreqKhz = hardwareResult?.cpuMaxFreqKhz,
                cpuHardware = identityResult?.identity?.hardware ?: "Unknown",
                gpuRenderer = hardwareResult?.gpuRenderer,
                gpuVendor = hardwareResult?.gpuVendor,
                glesVersion = hardwareResult?.glesVersion,
                vulkanSupported = hardwareResult?.vulkanSupported,
                ramTotalBytes = hardwareResult?.ramTotalBytes ?: 0L,
                ramAvailableBytes = hardwareResult?.ramAvailableBytes ?: 0L,
                isLowRamDevice = hardwareResult?.isLowRamDevice ?: false,
                memoryClassMb = hardwareResult?.memoryClassMb ?: 0,
                storageTotalBytes = hardwareResult?.storageTotalBytes ?: 0L,
                storageAvailableBytes = hardwareResult?.storageAvailableBytes ?: 0L,
                display = hardwareResult?.display,
                cameras = cameraPair?.first.orEmpty(),
                sensors = sensorPair?.first.orEmpty(),
                biometricStrongAvailable = securityResult?.biometricStrongAvailable ?: false,
                biometricAnyAvailable = securityResult?.biometricAnyAvailable ?: false,
                strongBoxAvailable = context.packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE
                ),
                secureLockScreen = securityResult?.secureLockScreen ?: false,
                vibratorAvailable = audio?.vibratorAvailable ?: false,
                speakerAvailable = audio?.speakerAvailable ?: false,
                microphoneAvailable = audio?.microphoneAvailable ?: false,
                nfcAvailable = connectivity?.nfcAvailable ?: false,
                bluetoothAvailable = connectivity?.bluetoothAvailable ?: false,
                wifiAvailable = connectivity?.wifiAvailable ?: false,
                gnssAvailable = connectivity?.gnssAvailable ?: false,
                telephonyAvailable = connectivity?.telephonyAvailable ?: false,
                usbHostAvailable = connectivity?.usbHostAvailable ?: false
            )

            DeviceSnapshot(
                platform = Platform.ANDROID,
                fields = fields,
                identity = identityResult?.identity ?: fallbackIdentity(),
                hardware = hardware,
                battery = batteryPair?.first ?: fallbackBattery(),
                security = securityResult?.facts ?: fallbackSecurity(),
                software = softwarePair?.first ?: fallbackSoftware(),
                collectedAtMillis = System.currentTimeMillis(),
                appVersion = BuildConfig.VERSION_NAME,
                collectionErrors = errors,
                isMock = false
            )
        }

    private fun fallbackIdentity() = com.verifydeviceinfo.core.model.DeviceIdentity(
        manufacturer = android.os.Build.MANUFACTURER ?: "Unknown",
        brand = android.os.Build.BRAND ?: "Unknown",
        model = android.os.Build.MODEL ?: "Unknown",
        marketingName = null,
        device = android.os.Build.DEVICE ?: "Unknown",
        product = android.os.Build.PRODUCT ?: "Unknown",
        hardware = android.os.Build.HARDWARE ?: "Unknown",
        board = android.os.Build.BOARD ?: "Unknown",
        fingerprint = android.os.Build.FINGERPRINT ?: "Unknown",
        buildId = android.os.Build.ID ?: "Unknown",
        buildDisplay = android.os.Build.DISPLAY ?: "Unknown",
        bootloader = null,
        buildTags = "",
        buildType = "",
        buildHost = null,
        buildUser = null,
        buildTimeMillis = null,
        serial = null,
        socManufacturer = null,
        socModel = null
    )

    private fun fallbackBattery() = com.verifydeviceinfo.core.model.BatteryFacts(
        percent = null, health = "Unknown", healthCode = 0, temperatureCelsius = null,
        voltageMillivolts = null, technology = null, plugged = "Unknown", status = "Unknown",
        designCapacityMah = null, chargeCounterMicroAmpHours = null, currentNowMicroAmps = null,
        cycleCount = null, powerProfileDesignCapacityMah = null, healthPercent = null
    )

    private fun fallbackSecurity() = com.verifydeviceinfo.core.model.SecurityFacts(
        rootIndicators = emptyList(), bootloaderUnlocked = null, verifiedBootState = null,
        debuggable = false, developerOptionsEnabled = null, adbEnabled = null, selinuxMode = null,
        encryptionState = "unknown", hardwareKeystoreAvailable = false, attestationSupported = false,
        playIntegrityStatus = "Not configured", customRomSuspected = false,
        installedFromTrustedStore = false, installerPackage = null, signatureDigestSha256 = null
    )

    private fun fallbackSoftware() = com.verifydeviceinfo.core.model.SoftwareFacts(
        androidRelease = android.os.Build.VERSION.RELEASE ?: "Unknown",
        apiLevel = android.os.Build.VERSION.SDK_INT,
        securityPatch = null, buildIncremental = null, kernelVersion = null,
        installedPackageCount = 0, systemPackageCount = 0, userPackageCount = 0,
        packageVisibilityRestricted = false, uptimeMillis = 0L, bootTimeMillis = null,
        thermalStatus = "Unknown", thermalStatusSupported = false,
        locale = "unknown", timeZone = "unknown"
    )
}
