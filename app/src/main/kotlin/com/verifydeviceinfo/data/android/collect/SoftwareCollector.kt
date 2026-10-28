package com.verifydeviceinfo.data.android.collect

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.SoftwareFacts
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification
import com.verifydeviceinfo.core.util.Formatters
import java.util.Locale

/** Installed software inventory, uptime and thermal state. */
class SoftwareCollector(private val context: Context) {

    fun collect(): Pair<SoftwareFacts, List<SpecField>> {
        val fields = mutableListOf<SpecField>()
        val pm = context.packageManager

        var systemCount = 0
        var userCount = 0
        var visible = true
        try {
            val packages = pm.getInstalledPackages(0)
            packages.forEach { info ->
                val isSystem = (info.applicationInfo?.flags ?: 0) and
                    ApplicationInfo.FLAG_SYSTEM != 0
                if (isSystem) systemCount++ else userCount++
            }
        } catch (e: Exception) {
            visible = false
        }

        val visibilityRestricted = Build.VERSION.SDK_INT >= 30

        fields += FieldBuilder.value(
            "software.packageCount", "Installed apps visible to this app",
            (systemCount + userCount).toString(), SpecGroup.SOFTWARE_INVENTORY,
            "PackageManager.getInstalledPackages()",
            note = if (visibilityRestricted) {
                "Android 11+ hides other apps unless they are declared in the manifest queries element " +
                    "or the app holds QUERY_ALL_PACKAGES. This count is therefore a lower bound."
            } else null
        )
        fields += FieldBuilder.value(
            "software.systemPackages", "System apps", systemCount.toString(),
            SpecGroup.SOFTWARE_INVENTORY, "PackageManager application flags"
        )
        fields += FieldBuilder.value(
            "software.userPackages", "User-installed apps", userCount.toString(),
            SpecGroup.SOFTWARE_INVENTORY, "PackageManager application flags"
        )

        val uptime = SystemClock.elapsedRealtime()
        val bootTime = System.currentTimeMillis() - uptime
        fields += FieldBuilder.value(
            "uptime.sinceBoot", "Time since boot", Formatters.uptime(uptime), SpecGroup.UPTIME,
            "SystemClock.elapsedRealtime()"
        )
        fields += FieldBuilder.value(
            "uptime.bootTime", "Boot timestamp", Formatters.date(bootTime), SpecGroup.UPTIME,
            "Derived from elapsed realtime", Confidence.MEDIUM, Verification.INFERRED
        )

        val thermalSupported = Build.VERSION.SDK_INT >= 29
        val thermalStatus = if (thermalSupported) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            try {
                when (powerManager?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "None"
                    PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                    PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown imminent"
                    else -> "Unknown"
                }
            } catch (e: Exception) {
                "Unavailable"
            }
        } else "Requires Android 10+"
        fields += FieldBuilder.value(
            "thermal.status", "Thermal status", thermalStatus, SpecGroup.THERMAL,
            "PowerManager.getCurrentThermalStatus()", Confidence.MEDIUM,
            note = if (!thermalSupported) "Thermal status API requires Android 10 (API 29)." else null
        )
        fields += FieldBuilder.value(
            "thermal.batteryTemp", "Battery temperature",
            null, SpecGroup.THERMAL, "BatteryManager",
            note = "Reported on the Battery screen, which reads it from the battery service."
        )

        // First install of this app is not the device's age — label it clearly.
        try {
            val info = pm.getPackageInfo(context.packageName, 0)
            fields += FieldBuilder.value(
                "software.appFirstInstall", "This app first installed",
                Formatters.date(info.firstInstallTime), SpecGroup.SOFTWARE_INVENTORY,
                "PackageInfo.firstInstallTime",
                note = "This is when this app was installed — not when the device was made or bought."
            )
            fields += FieldBuilder.value(
                "software.appLastUpdate", "This app last updated",
                Formatters.date(info.lastUpdateTime), SpecGroup.SOFTWARE_INVENTORY,
                "PackageInfo.lastUpdateTime"
            )
        } catch (e: Exception) {
            fields += FieldBuilder.value(
                "software.appFirstInstall", "This app first installed", null,
                SpecGroup.SOFTWARE_INVENTORY, "PackageInfo", note = "Unreadable: ${e.message}"
            )
        }

        // System update history is not exposed to apps; say so instead of inventing entries.
        fields += FieldBuilder.unsupported(
            "software.updateHistory", "System update history", SpecGroup.SOFTWARE_INVENTORY,
            "No public API",
            "Android exposes no API for a device's update history. Only the current build is readable."
        )
        fields += FieldBuilder.unsupported(
            "software.serviceHistory", "Service and repair history", SpecGroup.SOFTWARE_INVENTORY,
            "No public API",
            "Repair records live with the manufacturer or service partner and are not available to apps."
        )

        val locale = Locale.getDefault()
        val facts = SoftwareFacts(
            androidRelease = Build.VERSION.RELEASE ?: "Unknown",
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            buildIncremental = Build.VERSION.INCREMENTAL,
            kernelVersion = System.getProperty("os.version"),
            installedPackageCount = systemCount + userCount,
            systemPackageCount = systemCount,
            userPackageCount = userCount,
            packageVisibilityRestricted = visibilityRestricted && visible,
            uptimeMillis = uptime,
            bootTimeMillis = bootTime,
            thermalStatus = thermalStatus,
            thermalStatusSupported = thermalSupported,
            locale = "${locale.language}-${locale.country}",
            timeZone = java.util.TimeZone.getDefault().id
        )
        return facts to fields
    }
}
