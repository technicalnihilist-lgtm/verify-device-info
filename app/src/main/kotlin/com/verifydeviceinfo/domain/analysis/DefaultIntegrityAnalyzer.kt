package com.verifydeviceinfo.domain.analysis

import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.Evidence
import com.verifydeviceinfo.core.model.EvidenceSource
import com.verifydeviceinfo.core.model.IntegrityCheck
import com.verifydeviceinfo.core.model.IntegrityReport
import com.verifydeviceinfo.core.util.Formatters
import com.verifydeviceinfo.domain.SystemIntegrityAnalyzer
import java.util.Locale

/**
 * System integrity assessment. Everything here is a read-only observation of what the
 * platform is willing to expose. Nothing is bypassed, exploited or worked around.
 */
class DefaultIntegrityAnalyzer : SystemIntegrityAnalyzer {

    override fun analyze(snapshot: DeviceSnapshot): IntegrityReport {
        val checks = mutableListOf<IntegrityCheck>()
        val security = snapshot.security
        val software = snapshot.software

        // Root
        checks += if (security.rootIndicators.isEmpty()) {
            IntegrityCheck(
                id = "root",
                title = "Root access",
                status = DiagnosticStatus.PASS,
                explanation = "No common root indicators were found. This checks for su binaries on the " +
                    "usual paths, Magisk and KernelSU artefacts, and debuggable build flags. A determined " +
                    "root can hide from any user-space check.",
                evidence = listOf(
                    Evidence("Searched common su paths and manager packages", EvidenceSource.HEURISTIC)
                )
            )
        } else {
            IntegrityCheck(
                id = "root",
                title = "Root access",
                status = DiagnosticStatus.WARNING,
                explanation = "Indicators found: " + security.rootIndicators.joinToString("; "),
                evidence = security.rootIndicators.map { Evidence(it, EvidenceSource.HEURISTIC) }
            )
        }

        // Bootloader
        checks += when (security.bootloaderUnlocked) {
            true -> IntegrityCheck(
                id = "bootloader",
                title = "Bootloader lock state",
                status = DiagnosticStatus.WARNING,
                explanation = "The bootloader reports itself unlocked. An unlocked bootloader allows " +
                    "unsigned system images to run, so the software on the device cannot be assumed to be " +
                    "the manufacturer's.",
                evidence = listOf(
                    Evidence("ro.boot.flash.locked / oem_unlock_allowed reports unlocked",
                        EvidenceSource.SYSTEM_PROPERTY)
                )
            )
            false -> IntegrityCheck(
                id = "bootloader",
                title = "Bootloader lock state",
                status = DiagnosticStatus.VERIFIED,
                explanation = "The bootloader reports itself locked.",
                evidence = listOf(
                    Evidence("ro.boot.flash.locked=1", EvidenceSource.SYSTEM_PROPERTY)
                )
            )
            null -> IntegrityCheck(
                id = "bootloader",
                title = "Bootloader lock state",
                status = DiagnosticStatus.UNVERIFIED,
                explanation = "The lock state is not exposed on this device or Android version.",
                evidence = listOf(
                    Evidence("No boot lock property readable", EvidenceSource.SYSTEM_PROPERTY)
                )
            )
        }

        // Verified boot
        checks += if (security.verifiedBootState != null) {
            val green = security.verifiedBootState.lowercase(Locale.US) == "green"
            IntegrityCheck(
                id = "verified_boot",
                title = "Verified Boot state",
                status = if (green) DiagnosticStatus.VERIFIED else DiagnosticStatus.WARNING,
                explanation = if (green) {
                    "ro.boot.verifiedbootstate is green, meaning the device booted a verified image."
                } else {
                    "ro.boot.verifiedbootstate is '${security.verifiedBootState}'. Only 'green' indicates " +
                        "a fully verified boot chain; orange and yellow indicate unlocked or custom keys."
                },
                evidence = listOf(
                    Evidence("ro.boot.verifiedbootstate=${security.verifiedBootState}",
                        EvidenceSource.SYSTEM_PROPERTY)
                )
            )
        } else {
            IntegrityCheck(
                id = "verified_boot",
                title = "Verified Boot state",
                status = DiagnosticStatus.NOT_SUPPORTED,
                explanation = "This device does not expose a verified boot state property.",
                evidence = listOf(
                    Evidence("ro.boot.verifiedbootstate absent", EvidenceSource.SYSTEM_PROPERTY)
                )
            )
        }

        // Debug settings
        val devOptions = security.developerOptionsEnabled
        checks += IntegrityCheck(
            id = "debug_settings",
            title = "Developer options and ADB",
            status = when {
                security.debuggable -> DiagnosticStatus.WARNING
                devOptions == true || security.adbEnabled == true -> DiagnosticStatus.WARNING
                devOptions == false -> DiagnosticStatus.PASS
                else -> DiagnosticStatus.UNVERIFIED
            },
            explanation = when {
                security.debuggable -> "ro.debuggable=1: this is a debuggable system build."
                devOptions == true || security.adbEnabled == true ->
                    "Developer options or wireless debugging are enabled. This is a user setting and is not " +
                        "a security flaw by itself, but it widens the attack surface."
                devOptions == false -> "Developer options are disabled."
                else -> "The developer-options state could not be read without a settings permission."
            },
            evidence = listOf(
                Evidence("ro.debuggable=${security.debuggable}", EvidenceSource.SYSTEM_PROPERTY),
                Evidence("developer options=${devOptions ?: "unreadable"}", EvidenceSource.ANDROID_API)
            )
        )

        // SELinux
        checks += if (security.selinuxMode != null) {
            val enforcing = security.selinuxMode.contains("Enforcing", ignoreCase = true)
            IntegrityCheck(
                id = "selinux",
                title = "SELinux mode",
                status = if (enforcing) DiagnosticStatus.VERIFIED else DiagnosticStatus.WARNING,
                explanation = if (enforcing) "SELinux is enforcing."
                else "SELinux is '${security.selinuxMode}'. Permissive SELinux is a strong sign of a " +
                    "modified system image.",
                evidence = listOf(
                    Evidence("getenforce=${security.selinuxMode}", EvidenceSource.PROC_FILESYSTEM)
                )
            )
        } else {
            IntegrityCheck(
                id = "selinux",
                title = "SELinux mode",
                status = DiagnosticStatus.NOT_SUPPORTED,
                explanation = "The SELinux mode could not be read on this device.",
                evidence = listOf(Evidence("getenforce unavailable", EvidenceSource.PROC_FILESYSTEM))
            )
        }

        // Patch level
        val patchDays = parsePatchDays(snapshot)
        checks += IntegrityCheck(
            id = "patch_level",
            title = "Security patch level",
            status = when {
                patchDays == null -> DiagnosticStatus.UNVERIFIED
                patchDays > 730 -> DiagnosticStatus.FAILED
                patchDays > 365 -> DiagnosticStatus.WARNING
                else -> DiagnosticStatus.PASS
            },
            explanation = when {
                patchDays == null -> "No security patch date was reported by the build."
                patchDays > 730 -> "The last security patch is ${Formatters.duration(patchDays)} old. " +
                    "Known vulnerabilities from that period are almost certainly unpatched."
                patchDays > 365 -> "The last security patch is ${Formatters.duration(patchDays)} old. " +
                    "The device is missing more than a year of platform fixes."
                else -> "Security patch ${software.securityPatch} is ${Formatters.duration(patchDays)} old."
            },
            evidence = listOf(
                Evidence("ro.build.version.security_patch=${software.securityPatch ?: "absent"}",
                    EvidenceSource.BUILD_FIELDS)
            )
        )

        // Encryption
        checks += IntegrityCheck(
            id = "encryption",
            title = "Storage encryption",
            status = if (security.encryptionState.contains("encrypted", true)) DiagnosticStatus.VERIFIED
            else DiagnosticStatus.WARNING,
            explanation = "Storage reports as '${security.encryptionState}'. Android requires " +
                "file-based encryption on modern devices; an unencrypted userdata partition indicates a " +
                "modified or very old build.",
            evidence = listOf(
                Evidence("vold.decrypt / ro.crypto.state=${security.encryptionState}",
                    EvidenceSource.SYSTEM_PROPERTY)
            )
        )

        // Hardware keystore
        checks += IntegrityCheck(
            id = "hardware_keystore",
            title = "Hardware-backed keystore",
            status = if (security.hardwareKeystoreAvailable) DiagnosticStatus.VERIFIED
            else DiagnosticStatus.UNVERIFIED,
            explanation = if (security.hardwareKeystoreAvailable) {
                "A hardware-backed keystore is available" +
                    (if (snapshot.hardware.strongBoxAvailable) " and StrongBox is present" else "") +
                    ". Keys can be bound to secure hardware."
            } else {
                "The app could not confirm a hardware-backed keystore on this device."
            },
            evidence = listOf(
                Evidence("KeyInfo.isInsideSecureHardware reported by the Android keystore",
                    EvidenceSource.ANDROID_API)
            )
        )

        // Play Integrity
        checks += IntegrityCheck(
            id = "play_integrity",
            title = "Play Integrity attestation",
            status = DiagnosticStatus.NOT_SUPPORTED,
            explanation = "Play Integrity verdicts require a Google Cloud project, a configured API key and " +
                "server-side verification of a signed token. This build ships without those credentials, so " +
                "no verdict is produced and none is simulated. Note that a passing verdict would not prove " +
                "that physical components are genuine.",
            evidence = listOf(
                Evidence("Attestation not configured in this build", EvidenceSource.OFFICIAL_DOCUMENTATION)
            )
        )

        // Installer / distribution
        checks += IntegrityCheck(
            id = "installer",
            title = "App distribution source",
            status = if (security.installedFromTrustedStore) DiagnosticStatus.PASS
            else DiagnosticStatus.UNVERIFIED,
            explanation = if (security.installedFromTrustedStore) {
                "This app was installed by a system installer or a known app store."
            } else {
                "This app was installed from '${security.installerPackage ?: "an unknown source"}'. " +
                    "Sideloading is not inherently unsafe, but builds distributed outside a store are not " +
                    "vetted."
            },
            evidence = listOf(
                Evidence("installer=${security.installerPackage ?: "unknown"}", EvidenceSource.PACKAGE_MANAGER)
            )
        )

        val concerning = checks.count { it.status == DiagnosticStatus.FAILED || it.status == DiagnosticStatus.WARNING }
        val overall = when {
            checks.any { it.status == DiagnosticStatus.FAILED } -> DiagnosticStatus.FAILED
            concerning > 0 -> DiagnosticStatus.WARNING
            checks.any { it.status == DiagnosticStatus.VERIFIED || it.status == DiagnosticStatus.PASS } ->
                DiagnosticStatus.PASS
            else -> DiagnosticStatus.UNVERIFIED
        }
        val summary = when {
            overall == DiagnosticStatus.FAILED -> "The system software has serious integrity problems."
            overall == DiagnosticStatus.WARNING -> "$concerning integrity check(s) need attention."
            overall == DiagnosticStatus.PASS -> "No integrity problems were detected in the checks this app " +
                "can perform."
            else -> "Integrity could not be assessed from the available data."
        }

        return IntegrityReport(checks = checks, overallStatus = overall, summary = summary)
    }

    private fun parsePatchDays(snapshot: DeviceSnapshot): Long? {
        val value = snapshot.software.securityPatch ?: return null
        return try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(value.substring(0, 4).toInt(), value.substring(5, 7).toInt() - 1, 1)
            Formatters.daysBetween(cal.timeInMillis, snapshot.collectedAtMillis)
        } catch (e: Exception) {
            null
        }
    }
}
