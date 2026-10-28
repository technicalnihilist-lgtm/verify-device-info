package com.verifydeviceinfo.data.android

import com.verifydeviceinfo.core.model.BatteryFacts
import com.verifydeviceinfo.core.model.DeviceIdentity
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.DisplayFacts
import com.verifydeviceinfo.core.model.HardwareFacts
import com.verifydeviceinfo.core.model.Platform
import com.verifydeviceinfo.core.model.ScanProgress
import com.verifydeviceinfo.core.model.SecurityFacts
import com.verifydeviceinfo.core.model.SoftwareFacts
import com.verifydeviceinfo.domain.DeviceInformationProvider
import kotlinx.coroutines.delay

/**
 * Development fixture. It is only reachable from debug builds, its snapshots are flagged
 * isMock=true, and the UI and every export label them as MOCK DATA. Mock results are never
 * merged into a real scan.
 */
class MockDeviceProvider : DeviceInformationProvider {

    override val platform: Platform = Platform.ANDROID

    override suspend fun collect(onProgress: (ScanProgress) -> Unit): DeviceSnapshot {
        val total = 8
        for (stage in 1..total) {
            delay(120)
            onProgress(ScanProgress(stage, total, "Mock stage $stage", finished = stage == total))
        }
        val now = System.currentTimeMillis()
        return DeviceSnapshot(
            platform = Platform.ANDROID,
            fields = emptyList(),
            identity = DeviceIdentity(
                manufacturer = "Samsung", brand = "samsung", model = "SM-S911B",
                marketingName = "Galaxy S23", device = "dm1q", product = "dm1qxxx",
                hardware = "qcom", board = "kalama", fingerprint = "samsung/dm1qxxx/dm1q:14/UP1A/1234:user/release-keys",
                buildId = "UP1A.231005.007", buildDisplay = "UP1A.231005.007", bootloader = "S911BXXU3",
                buildTags = "release-keys", buildType = "user", buildHost = "build-host",
                buildUser = "build-user", buildTimeMillis = now - 90L * 86_400_000L,
                serial = null, socManufacturer = "Qualcomm", socModel = "SM8550"
            ),
            hardware = HardwareFacts(
                socManufacturer = "Qualcomm", socModel = "SM8550",
                cpuAbis = listOf("arm64-v8a", "armeabi-v7a"), cpuCores = 8,
                cpuMaxFreqKhz = 3_360_000L, cpuHardware = "qcom",
                gpuRenderer = "Adreno 740", gpuVendor = "Qualcomm", glesVersion = "3.2",
                vulkanSupported = true, ramTotalBytes = 8_000_000_000L, ramAvailableBytes = 3_000_000_000L,
                isLowRamDevice = false, memoryClassMb = 512,
                storageTotalBytes = 128_000_000_000L, storageAvailableBytes = 60_000_000_000L,
                display = DisplayFacts(
                    widthPx = 1080, heightPx = 2340, densityDpi = 425, refreshRateHz = 120f,
                    supportedRefreshRates = listOf(24f, 60f, 120f), hdrTypes = listOf("HDR10", "HDR10+"),
                    xdpi = 420f, ydpi = 420f, diagonalInches = 6.1f, wideColorGamut = true
                ),
                cameras = emptyList(), sensors = emptyList(),
                biometricStrongAvailable = true, biometricAnyAvailable = true, strongBoxAvailable = true,
                secureLockScreen = true, vibratorAvailable = true, speakerAvailable = true,
                microphoneAvailable = true, nfcAvailable = true, bluetoothAvailable = true,
                wifiAvailable = true, gnssAvailable = true, telephonyAvailable = true,
                usbHostAvailable = true
            ),
            battery = BatteryFacts(
                percent = 78, health = "Good", healthCode = 2, temperatureCelsius = 31.5f,
                voltageMillivolts = 4200, technology = "Li-ion", plugged = "Not charging",
                status = "Discharging", designCapacityMah = 3900, chargeCounterMicroAmpHours = 3_000_000,
                currentNowMicroAmps = -450_000, cycleCount = 120, powerProfileDesignCapacityMah = 3900,
                healthPercent = 96
            ),
            security = SecurityFacts(
                rootIndicators = emptyList(), bootloaderUnlocked = false, verifiedBootState = "green",
                debuggable = false, developerOptionsEnabled = false, adbEnabled = false,
                selinuxMode = "Enforcing", encryptionState = "encrypted",
                hardwareKeystoreAvailable = true, attestationSupported = true,
                playIntegrityStatus = "Not configured", customRomSuspected = false,
                installedFromTrustedStore = true, installerPackage = "com.android.vending",
                signatureDigestSha256 = "MOCK"
            ),
            software = SoftwareFacts(
                androidRelease = "14", apiLevel = 34, securityPatch = "2024-01-01",
                buildIncremental = "S911BXXU3", kernelVersion = "5.15.94",
                installedPackageCount = 210, systemPackageCount = 160, userPackageCount = 50,
                packageVisibilityRestricted = true, uptimeMillis = 86_400_000L,
                bootTimeMillis = now - 86_400_000L, thermalStatus = "None",
                thermalStatusSupported = true, locale = "en-IN", timeZone = "Asia/Kolkata"
            ),
            collectedAtMillis = now,
            appVersion = "1.0.0-mock",
            collectionErrors = listOf("MOCK DATA: this snapshot is a development fixture."),
            isMock = true
        )
    }
}
