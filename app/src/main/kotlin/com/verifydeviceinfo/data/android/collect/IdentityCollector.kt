package com.verifydeviceinfo.data.android.collect

import android.os.Build
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DeviceIdentity
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification
import com.verifydeviceinfo.data.android.SystemPropertyReader

data class IdentityResult(
    val identity: DeviceIdentity,
    val fields: List<SpecField>,
    val softwareFields: List<SpecField>
)

/**
 * Collects device identity from android.os.Build and read-only system properties.
 * Nothing here is guessed: a value that the platform withholds is reported as such.
 */
class IdentityCollector(private val props: SystemPropertyReader) {

    fun collect(): IdentityResult {
        val fields = mutableListOf<SpecField>()
        val softwareFields = mutableListOf<SpecField>()

        val marketingName = props.get("ro.product.marketname")
            ?: props.get("ro.product.odm.marketname")
            ?: props.get("ro.vendor.product.marketname")

        val serial = readSerial()

        fields += FieldBuilder.value(
            "identity.manufacturer", "Manufacturer", Build.MANUFACTURER, SpecGroup.IDENTITY,
            "android.os.Build.MANUFACTURER"
        )
        fields += FieldBuilder.value(
            "identity.brand", "Brand", Build.BRAND, SpecGroup.IDENTITY, "android.os.Build.BRAND"
        )
        fields += FieldBuilder.value(
            "identity.model", "Model", Build.MODEL, SpecGroup.IDENTITY, "android.os.Build.MODEL"
        )
        fields += FieldBuilder.value(
            "identity.marketingName", "Marketing name", marketingName, SpecGroup.IDENTITY,
            "ro.product.marketname", Confidence.MEDIUM, Verification.DIRECTLY_VERIFIED,
            note = if (marketingName == null) "Not published by this build; the model code is shown instead." else null
        )
        fields += FieldBuilder.value(
            "identity.device", "Device codename", Build.DEVICE, SpecGroup.IDENTITY, "android.os.Build.DEVICE"
        )
        fields += FieldBuilder.value(
            "identity.product", "Product", Build.PRODUCT, SpecGroup.IDENTITY, "android.os.Build.PRODUCT"
        )
        fields += FieldBuilder.value(
            "identity.hardware", "Hardware platform", Build.HARDWARE, SpecGroup.IDENTITY,
            "android.os.Build.HARDWARE"
        )
        fields += FieldBuilder.value(
            "identity.board", "Board", Build.BOARD, SpecGroup.IDENTITY, "android.os.Build.BOARD"
        )
        fields += FieldBuilder.value(
            "identity.bootloader", "Bootloader", Build.BOOTLOADER, SpecGroup.IDENTITY,
            "android.os.Build.BOOTLOADER"
        )
        fields += FieldBuilder.value(
            "identity.fingerprint", "Build fingerprint", Build.FINGERPRINT, SpecGroup.IDENTITY,
            "android.os.Build.FINGERPRINT", Confidence.HIGH
        )
        fields += FieldBuilder.value(
            "identity.socManufacturer", "SoC manufacturer", Build.SOC_MANUFACTURER.takeIf { Build.VERSION.SDK_INT >= 31 },
            SpecGroup.PROCESSOR, "android.os.Build.SOC_MANUFACTURER",
            note = if (Build.VERSION.SDK_INT < 31) "Requires Android 12 (API 31) or newer." else null
        )
        fields += FieldBuilder.value(
            "identity.socModel", "SoC model", Build.SOC_MODEL.takeIf { Build.VERSION.SDK_INT >= 31 },
            SpecGroup.PROCESSOR, "android.os.Build.SOC_MODEL",
            note = if (Build.VERSION.SDK_INT < 31) "Requires Android 12 (API 31) or newer." else null
        )
        fields += if (serial != null) {
            FieldBuilder.value(
                "identity.serial", "Serial number", serial, SpecGroup.IDENTITY,
                "android.os.Build.getSerial()", Confidence.HIGH, sensitive = true
            )
        } else {
            FieldBuilder.unsupported(
                "identity.serial", "Serial number", SpecGroup.IDENTITY, "android.os.Build.getSerial()",
                "Android 10 and newer restrict hardware serial numbers to privileged apps. This app cannot " +
                    "read it, and does not guess it."
            )
        }

        softwareFields += FieldBuilder.value(
            "software.androidRelease", "Android version", Build.VERSION.RELEASE, SpecGroup.SOFTWARE,
            "android.os.Build.VERSION.RELEASE"
        )
        softwareFields += FieldBuilder.value(
            "software.apiLevel", "API level", Build.VERSION.SDK_INT.toString(), SpecGroup.SOFTWARE,
            "android.os.Build.VERSION.SDK_INT"
        )
        softwareFields += FieldBuilder.value(
            "software.securityPatch", "Security patch level", Build.VERSION.SECURITY_PATCH, SpecGroup.SOFTWARE,
            "android.os.Build.VERSION.SECURITY_PATCH"
        )
        softwareFields += FieldBuilder.value(
            "software.buildId", "Build ID", Build.ID, SpecGroup.SOFTWARE, "android.os.Build.ID"
        )
        softwareFields += FieldBuilder.value(
            "software.buildDisplay", "Build display string", Build.DISPLAY, SpecGroup.SOFTWARE,
            "android.os.Build.DISPLAY"
        )
        softwareFields += FieldBuilder.value(
            "software.incremental", "Build incremental", Build.VERSION.INCREMENTAL, SpecGroup.SOFTWARE,
            "android.os.Build.VERSION.INCREMENTAL"
        )
        softwareFields += FieldBuilder.value(
            "software.buildType", "Build type", Build.TYPE, SpecGroup.SOFTWARE, "android.os.Build.TYPE"
        )
        softwareFields += FieldBuilder.value(
            "software.buildTags", "Build tags", Build.TAGS, SpecGroup.SOFTWARE, "android.os.Build.TAGS"
        )
        softwareFields += FieldBuilder.value(
            "software.buildTime", "Firmware build date",
            java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.US)
                .format(java.util.Date(Build.TIME)),
            SpecGroup.SOFTWARE, "android.os.Build.TIME",
            note = "Compilation date of the installed system image — not a manufacturing date."
        )
        softwareFields += FieldBuilder.value(
            "software.buildHost", "Build host", Build.HOST, SpecGroup.SOFTWARE, "android.os.Build.HOST"
        )
        softwareFields += FieldBuilder.value(
            "software.buildUser", "Build user", Build.USER, SpecGroup.SOFTWARE, "android.os.Build.USER"
        )
        softwareFields += FieldBuilder.value(
            "software.kernel", "Kernel version", System.getProperty("os.version"), SpecGroup.SOFTWARE,
            "System.getProperty(\"os.version\")"
        )
        softwareFields += FieldBuilder.value(
            "software.codename", "Platform codename", Build.VERSION.CODENAME, SpecGroup.SOFTWARE,
            "android.os.Build.VERSION.CODENAME"
        )
        softwareFields += FieldBuilder.value(
            "software.radio", "Baseband / radio", Build.getRadioVersion(), SpecGroup.SOFTWARE,
            "android.os.Build.getRadioVersion()", Confidence.MEDIUM
        )

        val identity = DeviceIdentity(
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            brand = Build.BRAND ?: "Unknown",
            model = Build.MODEL ?: "Unknown",
            marketingName = marketingName,
            device = Build.DEVICE ?: "Unknown",
            product = Build.PRODUCT ?: "Unknown",
            hardware = Build.HARDWARE ?: "Unknown",
            board = Build.BOARD ?: "Unknown",
            fingerprint = Build.FINGERPRINT ?: "Unknown",
            buildId = Build.ID ?: "Unknown",
            buildDisplay = Build.DISPLAY ?: "Unknown",
            bootloader = Build.BOOTLOADER,
            buildTags = Build.TAGS ?: "",
            buildType = Build.TYPE ?: "",
            buildHost = Build.HOST,
            buildUser = Build.USER,
            buildTimeMillis = Build.TIME,
            serial = serial,
            socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null,
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        )

        return IdentityResult(identity, fields, softwareFields)
    }

    private fun readSerial(): String? = try {
        if (Build.VERSION.SDK_INT >= 26) Build.getSerial() else @Suppress("DEPRECATION") Build.SERIAL
    } catch (security: SecurityException) {
        null
    } catch (other: Throwable) {
        null
    }
}
