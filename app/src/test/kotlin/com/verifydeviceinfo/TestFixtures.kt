package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.BatteryFacts
import com.verifydeviceinfo.core.model.CameraFacts
import com.verifydeviceinfo.core.model.DeviceIdentity
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.DisplayFacts
import com.verifydeviceinfo.core.model.HardwareFacts
import com.verifydeviceinfo.core.model.Platform
import com.verifydeviceinfo.core.model.ReferenceDevice
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.core.model.SecurityFacts
import com.verifydeviceinfo.core.model.SensorFacts
import com.verifydeviceinfo.core.model.SoftwareFacts

/** Deterministic fixtures for the unit tests. Never used by the app at runtime. */
object TestFixtures {

    const val NOW = 1_750_000_000_000L // fixed clock so age maths is reproducible

    fun reference() = ReferenceDevice(
        modelPatterns = listOf("SM-S911"),
        brand = "Samsung",
        marketingName = "Galaxy S23",
        soc = "Snapdragon 8 Gen 2 SM8550",
        socManufacturer = "Qualcomm",
        releaseDate = "2023-02-17",
        ramOptionsGb = listOf(8),
        storageOptionsGb = listOf(128, 256, 512),
        displayWidthPx = 1080,
        displayHeightPx = 2340,
        densityDpi = 420,
        refreshRateHz = 120f,
        rearCameraCount = 3,
        frontCameraCount = 1,
        mainCameraMegapixels = 50.0,
        batteryMah = 3900,
        expectedSensors = listOf("accelerometer", "gyroscope", "proximity", "light"),
        sourceUrl = "https://example.invalid/specs",
        sourceDate = "2026-10-28",
        sourceName = "Test reference"
    )

    fun referenceMatch() = ReferenceMatch(reference(), "SM-S911")

    fun identity(
        manufacturer: String = "samsung",
        brand: String = "samsung",
        model: String = "SM-S911B",
        device: String = "dm1q",
        product: String = "dm1qxxx",
        fingerprint: String = "samsung/dm1qxxx/dm1q:14/UP1A/1234:user/release-keys",
        tags: String = "release-keys",
        type: String = "user",
        socModel: String? = "SM8550",
        buildTimeMillis: Long = NOW - 30L * 86_400_000L
    ) = DeviceIdentity(
        manufacturer = manufacturer,
        brand = brand,
        model = model,
        marketingName = "Galaxy S23",
        device = device,
        product = product,
        hardware = "qcom",
        board = "kalama",
        fingerprint = fingerprint,
        buildId = "UP1A",
        buildDisplay = "UP1A",
        bootloader = "S911BXXU3",
        buildTags = tags,
        buildType = type,
        buildHost = "host",
        buildUser = "user",
        buildTimeMillis = buildTimeMillis,
        serial = null,
        socManufacturer = "Qualcomm",
        socModel = socModel
    )

    fun display(width: Int = 1080, height: Int = 2340, density: Int = 420) = DisplayFacts(
        widthPx = width,
        heightPx = height,
        densityDpi = density,
        refreshRateHz = 120f,
        supportedRefreshRates = listOf(60f, 120f),
        hdrTypes = listOf("HDR10"),
        xdpi = 420f,
        ydpi = 420f,
        diagonalInches = 6.1f,
        wideColorGamut = true
    )

    fun camera(id: String, facing: String, mp: Double) = CameraFacts(
        id = id,
        lensFacing = facing,
        sensorWidthPx = 4000,
        sensorHeightPx = 3000,
        maxJpegWidth = (mp * 1000).toInt(),
        maxJpegHeight = 1000,
        megapixels = mp,
        flashAvailable = facing == "BACK",
        hardwareLevel = "Limited",
        apertures = listOf(1.8f),
        focalLengthsMm = listOf(5.4f),
        physicalCameraIds = emptyList(),
        pixelSizeMicrons = 1.0
    )

    fun sensor(category: String, name: String = "Test $category") = SensorFacts(
        name = name,
        vendor = "Test Vendor",
        version = 1,
        type = 1,
        stringType = "android.sensor.${category.lowercase()}",
        category = category,
        maxRange = 19.6f,
        resolution = 0.001f,
        powerMilliAmp = 0.15f,
        wakeUpSensor = false,
        reportingMode = "Continuous"
    )

    fun hardware(
        ramBytes: Long = 8_000_000_000L,
        storageBytes: Long = 128_000_000_000L,
        display: DisplayFacts? = display(),
        cameras: List<CameraFacts> = listOf(
            camera("0", "BACK", 50.0), camera("1", "FRONT", 12.0), camera("2", "BACK", 10.0)
        ),
        sensors: List<SensorFacts> = listOf(
            sensor("Accelerometer"), sensor("Gyroscope"), sensor("Proximity"), sensor("Light")
        )
    ) = HardwareFacts(
        socManufacturer = "Qualcomm",
        socModel = "SM8550",
        cpuAbis = listOf("arm64-v8a"),
        cpuCores = 8,
        cpuMaxFreqKhz = 3_360_000L,
        cpuHardware = "qcom",
        gpuRenderer = "Adreno 740",
        gpuVendor = "Qualcomm",
        glesVersion = "3.2",
        vulkanSupported = true,
        ramTotalBytes = ramBytes,
        ramAvailableBytes = ramBytes / 3,
        isLowRamDevice = false,
        memoryClassMb = 512,
        storageTotalBytes = storageBytes,
        storageAvailableBytes = storageBytes / 3,
        display = display,
        cameras = cameras,
        sensors = sensors,
        biometricStrongAvailable = true,
        biometricAnyAvailable = true,
        strongBoxAvailable = true,
        secureLockScreen = true,
        vibratorAvailable = true,
        speakerAvailable = true,
        microphoneAvailable = true,
        nfcAvailable = true,
        bluetoothAvailable = true,
        wifiAvailable = true,
        gnssAvailable = true,
        telephonyAvailable = true,
        usbHostAvailable = true
    )

    fun battery(
        health: String = "Good",
        healthCode: Int = 2,
        healthPercent: Int? = 95,
        cycleCount: Int? = 120
    ) = BatteryFacts(
        percent = 100,
        health = health,
        healthCode = healthCode,
        temperatureCelsius = 31.0f,
        voltageMillivolts = 4300,
        technology = "Li-ion",
        plugged = "Not charging",
        status = "Full",
        designCapacityMah = 3900,
        chargeCounterMicroAmpHours = 3_700_000,
        currentNowMicroAmps = 0,
        cycleCount = cycleCount,
        powerProfileDesignCapacityMah = 3900,
        healthPercent = healthPercent
    )

    fun security(
        rootIndicators: List<String> = emptyList(),
        bootloaderUnlocked: Boolean? = false,
        verifiedBootState: String? = "green",
        customRomSuspected: Boolean = false
    ) = SecurityFacts(
        rootIndicators = rootIndicators,
        bootloaderUnlocked = bootloaderUnlocked,
        verifiedBootState = verifiedBootState,
        debuggable = false,
        developerOptionsEnabled = false,
        adbEnabled = false,
        selinuxMode = "Enforcing",
        encryptionState = "encrypted",
        hardwareKeystoreAvailable = true,
        attestationSupported = true,
        playIntegrityStatus = "Not configured",
        customRomSuspected = customRomSuspected,
        installedFromTrustedStore = true,
        installerPackage = "com.android.vending",
        signatureDigestSha256 = "AA:BB"
    )

    fun software(securityPatch: String = "2026-06-01") = SoftwareFacts(
        androidRelease = "14",
        apiLevel = 34,
        securityPatch = securityPatch,
        buildIncremental = "1234",
        kernelVersion = "5.15.94",
        installedPackageCount = 200,
        systemPackageCount = 150,
        userPackageCount = 50,
        packageVisibilityRestricted = true,
        uptimeMillis = 3_600_000L,
        bootTimeMillis = NOW - 3_600_000L,
        thermalStatus = "None",
        thermalStatusSupported = true,
        locale = "en-IN",
        timeZone = "Asia/Kolkata"
    )

    fun snapshot(
        identity: DeviceIdentity = identity(),
        hardware: HardwareFacts = hardware(),
        battery: BatteryFacts = battery(),
        security: SecurityFacts = security(),
        software: SoftwareFacts = software(),
        isMock: Boolean = false
    ) = DeviceSnapshot(
        platform = Platform.ANDROID,
        fields = emptyList(),
        identity = identity,
        hardware = hardware,
        battery = battery,
        security = security,
        software = software,
        collectedAtMillis = NOW,
        appVersion = "test",
        collectionErrors = emptyList(),
        isMock = isMock
    )
}
