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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val timerFactory: (CoroutineScope) -> StageTimer = { StageTimer(it) },
    /**
     * The StrongBox feature query, as a parameter only so a test can make it throw or block without
     * a device whose package manager does; production always asks the platform. See the call site
     * for why it runs under the same deadline as the collector stages.
     */
    private val strongBoxFeature: () -> Boolean = {
        context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE
        )
    }
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

            // Every stage below reads a different system service and produces its own values; not
            // one of them is an input to another. Run one after the other, a scan therefore cost
            // the *sum* of ten stages whose time is almost entirely spent waiting for a Binder call
            // to answer rather than computing — the property service, the package manager, the
            // camera and sensor services, the audio service. They are all started here and awaited
            // in the order below, so a scan costs its slowest stage instead of the sum of them.
            //
            // Nothing the user sees moves. The same eight progress steps are emitted, with the same
            // labels, in the same order, and each is still emitted only after the work it labels has
            // actually completed — a stage that finishes early is simply awaited earlier than the
            // old code would have reached it. `fields` and `collectionErrors` are folded in in the
            // same order as before, so the snapshot, and every report built from it, is identical.
            // Each stage keeps its own deadline; what the whole scan can now cost is one stage
            // deadline instead of the sum of them, which is exactly what the old whole-scan ceiling
            // existed to bound.
            coroutineScope {
                val identityStage = async { timer.run { IdentityCollector(props).collect() } }
                val hardwareStage = async { timer.run { HardwareCollector(context, props).collect() } }
                val batteryStage = async { timer.run { BatteryCollector(context).collect() } }
                val cameraStage = async { timer.run { CameraCollector(context).collect() } }
                val sensorStage = async { timer.run { SensorCollector(context).collect() } }
                val connectivityStage = async { timer.run { ConnectivityCollector(context).collect() } }
                val audioStage = async { timer.run { AudioCollector(context).collect() } }
                val securityStage = async { timer.run { SecurityCollector(context, props).collect() } }
                val softwareStage = async { timer.run { SoftwareCollector(context).collect() } }
                val strongBoxStage = async { timer.run { strongBoxFeature() } }

                // 1. Identity and build properties
                val identityResult =
                    foldOutcome(errors, "Identity collection", identityStage.await(), timer)
                identityResult?.let {
                    fields += it.fields
                    fields += it.softwareFields
                }
                stage("Device identity and build properties")

                // 2. Processor, memory, storage, display
                val hardwareResult =
                    foldOutcome(errors, "Hardware collection", hardwareStage.await(), timer)
                hardwareResult?.let {
                    fields += it.fields
                    errors += it.errors
                }
                stage("Processor, memory and storage")

                // 3. Battery
                val batteryPair =
                    foldOutcome(errors, "Battery collection", batteryStage.await(), timer)
                batteryPair?.let { fields += it.second }
                stage("Battery and power")

                // 4. Cameras
                val cameraPair =
                    foldOutcome(errors, "Camera collection", cameraStage.await(), timer)
                cameraPair?.let { fields += it.second }
                stage("Camera modules")

                // 5. Sensors
                val sensorPair =
                    foldOutcome(errors, "Sensor collection", sensorStage.await(), timer)
                sensorPair?.let { fields += it.second }
                stage("Sensors")

                // 6. Connectivity
                val connectivity =
                    foldOutcome(errors, "Connectivity collection", connectivityStage.await(), timer)
                connectivity?.let { fields += it.fields }

                // 7. Audio and haptics
                val audio = foldOutcome(errors, "Audio collection", audioStage.await(), timer)
                audio?.let { fields += it.fields }
                stage("Connectivity and audio")

                // 8. Security, integrity and software inventory
                val securityResult =
                    foldOutcome(errors, "Security collection", securityStage.await(), timer)
                securityResult?.let {
                    fields += it.fields
                    fields += it.biometricFields
                }
                val softwarePair =
                    foldOutcome(errors, "Software inventory", softwareStage.await(), timer)
                softwarePair?.let { fields += it.second }
                stage("Security and installed software")

                // The one framework query in this method that is not a collector stage. `PackageManager`
                // is a Binder call into the system service, so it can block like any other — and on a
                // device whose package service has died it throws instead, which used to unwind the
                // whole scan: every field the nine stages above had already collected was thrown away
                // and the user was told "Scan failed" because one feature flag could not be read. Run
                // it as a stage, like the collectors, so it gets the same deadline and a failure costs
                // one `collectionErrors` line rather than the entire report.
                val strongBoxAvailable = foldOutcome(
                    errors, "StrongBox feature check", strongBoxStage.await(), timer
                ) ?: false

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
                    strongBoxAvailable = strongBoxAvailable,
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
        }

    /**
     * Folds one finished stage's outcome into [errors]. A failure keeps the message the app has
     * always shown; a timeout says so instead of leaving the stage silently missing.
     *
     * This used to be `collectStage`, which also *ran* the stage. The stages are now started
     * together and awaited one at a time, so running and folding are two steps: the outcome is
     * folded in the same declaration order the stages used to run in, which is what keeps the
     * `collectionErrors` list in the order every test and every report expects.
     */
    private fun <T> foldOutcome(
        errors: MutableList<String>,
        label: String,
        outcome: StageOutcome<T>,
        timer: StageTimer
    ): T? = when (outcome) {
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
