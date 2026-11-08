package com.verifydeviceinfo.data.android

import android.content.Context
import com.verifydeviceinfo.BuildConfig
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.HardwareFacts
import com.verifydeviceinfo.core.model.Platform
import com.verifydeviceinfo.core.model.ScanProgress
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.util.StageOutcome
import com.verifydeviceinfo.core.util.StageTimer
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Collects a complete snapshot from Android. Every stage reports progress only when it has
 * actually completed, so the scanning animation cannot outrun the real work.
 *
 * Each stage runs under a [StageTimer] deadline. A collector blocked in a Binder call that never
 * returns — another app holding the camera, a property service that stopped answering — is
 * abandoned instead of hanging the scan: it is recorded in [DeviceSnapshot.collectionErrors] and
 * the remaining stages still run, so the scan always finishes with whatever it could read.
 */
class AndroidDeviceInformationProvider(
    private val context: Context,
    private val props: SystemPropertyReader = SystemPropertyReader(),
    /**
     * Builds the timer that runs the stages. A parameter only so a test can drive the deadline
     * wiring — the promise that a scan whose stages never answer still finishes with a snapshot —
     * without a device that hangs; production always takes the default.
     */
    private val timerFactory: (CoroutineScope) -> StageTimer = { StageTimer(it) }
) : DeviceInformationProvider {

    override val platform: Platform = Platform.ANDROID

    /**
     * Holds abandoned stages. Deliberately not a child of the scan coroutine: a coroutine blocked
     * inside a Java call ignores cancellation, so a timed-out stage must not be waited for.
     */
    private val stageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun collect(onProgress: (ScanProgress) -> Unit): DeviceSnapshot =
        withContext(Dispatchers.IO) {
            val total = 8
            var done = 0
            val errors = mutableListOf<String>()
            val fields = mutableListOf<SpecField>()
            val timer = timerFactory(stageScope)

            fun stage(label: String) {
                done++
                onProgress(ScanProgress(done, total, label, finished = done == total))
            }

            // 1. Identity and build properties
            val identityResult = collectStage(timer, errors, "Identity collection") {
                IdentityCollector(props).collect()
            }
            identityResult?.let {
                fields += it.fields
                fields += it.softwareFields
            }
            stage("Device identity and build properties")

            // 2. Processor, memory, storage, display
            val hardwareResult = collectStage(timer, errors, "Hardware collection") {
                HardwareCollector(context, props).collect()
            }
            hardwareResult?.let {
                fields += it.fields
                errors += it.errors
            }
            stage("Processor, memory and storage")

            // 3. Battery
            val batteryPair = collectStage(timer, errors, "Battery collection") {
                BatteryCollector(context).collect()
            }
            batteryPair?.let { fields += it.second }
            stage("Battery and power")

            // 4. Cameras
            val cameraPair = collectStage(timer, errors, "Camera collection") {
                CameraCollector(context).collect()
            }
            cameraPair?.let { fields += it.second }
            stage("Camera modules")

            // 5. Sensors
            val sensorPair = collectStage(timer, errors, "Sensor collection") {
                SensorCollector(context).collect()
            }
            sensorPair?.let { fields += it.second }
            stage("Sensors")

            // 6. Connectivity
            val connectivity = collectStage(timer, errors, "Connectivity collection") {
                ConnectivityCollector(context).collect()
            }
            connectivity?.let { fields += it.fields }

            // 7. Audio and haptics
            val audio = collectStage(timer, errors, "Audio collection") {
                AudioCollector(context).collect()
            }
            audio?.let { fields += it.fields }
            stage("Connectivity and audio")

            // 8. Security, integrity and software inventory
            val securityResult = collectStage(timer, errors, "Security collection") {
                SecurityCollector(context, props).collect()
            }
            securityResult?.let {
                fields += it.fields
                fields += it.biometricFields
            }
            val softwarePair = collectStage(timer, errors, "Software inventory") {
                SoftwareCollector(context).collect()
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

    /**
     * Runs one stage and folds its outcome into [errors]. A failure keeps the message the app has
     * always shown; a timeout says so instead of leaving the stage silently missing.
     */
    private suspend fun <T> collectStage(
        timer: StageTimer,
        errors: MutableList<String>,
        label: String,
        block: suspend () -> T
    ): T? = when (val outcome = timer.run(block)) {
        is StageOutcome.Done -> outcome.value
        is StageOutcome.Failed -> {
            errors += "$label failed: ${outcome.error.message}"
            null
        }
        StageOutcome.TimedOut -> {
            errors += "$label timed out after ${timer.stageTimeoutMillis / 1000} s"
            null
        }
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
