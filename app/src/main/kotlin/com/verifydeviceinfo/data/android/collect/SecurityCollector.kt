package com.verifydeviceinfo.data.android.collect

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.SecurityFacts
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification
import com.verifydeviceinfo.data.android.SystemPropertyReader
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

data class SecurityResult(
    val facts: SecurityFacts,
    val fields: List<SpecField>,
    val biometricFields: List<SpecField>,
    val biometricStrongAvailable: Boolean,
    val biometricAnyAvailable: Boolean,
    val secureLockScreen: Boolean
)

/**
 * Read-only integrity indicators. No security control is bypassed, no vulnerability is
 * exploited and no restricted data is obtained — only what the platform offers to any app.
 */
class SecurityCollector(
    private val context: Context,
    private val props: SystemPropertyReader
) {

    fun collect(): SecurityResult {
        val fields = mutableListOf<SpecField>()
        val biometricFields = mutableListOf<SpecField>()
        val pm = context.packageManager

        // ---- Root indicators ----
        val rootIndicators = mutableListOf<String>()
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sbin/su",
            "/vendor/bin/su", "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk", "/data/adb/magisk",
            "/sbin/.magisk", "/data/adb/ksu", "/data/adb/modules"
        )
        suPaths.forEach { path ->
            try {
                if (File(path).exists()) rootIndicators += "Found $path"
            } catch (ignored: Exception) {
            }
        }
        val rootPackages = listOf(
            "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser",
            "com.noshufou.android.su", "me.weishu.kernelsu", "com.kingroot.kinguser",
            "com.zachspong.temprootremovejb"
        )
        rootPackages.forEach { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                rootIndicators += "Installed package $pkg"
            } catch (ignored: PackageManager.NameNotFoundException) {
            } catch (ignored: Exception) {
            }
        }
        if (props.getBooleanFlag("ro.debuggable") == true) rootIndicators += "ro.debuggable=1"
        if (props.get("ro.build.tags")?.contains("test-keys") == true) {
            rootIndicators += "Build signed with test-keys"
        }
        if (props.getBooleanFlag("ro.secure") == false) rootIndicators += "ro.secure=0"
        if (props.get("ro.boot.verifiedbootstate")?.lowercase(Locale.US) == "orange") {
            rootIndicators += "Verified boot state is orange (unlocked with custom keys)"
        }

        fields += FieldBuilder.value(
            "security.rootIndicators", "Root indicators",
            if (rootIndicators.isEmpty()) "None found" else rootIndicators.joinToString("; "),
            SpecGroup.SECURITY, "Filesystem, package and property checks", Confidence.MEDIUM,
            Verification.DIRECTLY_VERIFIED,
            note = "A hidden root cannot be detected by any user-space check."
        )

        // ---- Bootloader / verified boot ----
        val flashLocked = props.getBooleanFlag("ro.boot.flash.locked")
        val oemUnlockAllowed = props.getBooleanFlag("sys.oem_unlock_allowed")
        val bootloaderUnlocked: Boolean? = when {
            flashLocked != null -> !flashLocked
            oemUnlockAllowed != null -> oemUnlockAllowed
            else -> null
        }
        val verifiedBootState = props.get("ro.boot.verifiedbootstate")
        fields += FieldBuilder.value(
            "security.bootloaderState", "Bootloader lock",
            when (bootloaderUnlocked) {
                true -> "Unlocked"
                false -> "Locked"
                null -> null
            },
            SpecGroup.SECURITY, "ro.boot.flash.locked / sys.oem_unlock_allowed", Confidence.MEDIUM,
            note = if (bootloaderUnlocked == null) "Not exposed on this device." else null
        )
        fields += FieldBuilder.value(
            "security.verifiedBoot", "Verified Boot state", verifiedBootState, SpecGroup.SECURITY,
            "ro.boot.verifiedbootstate", Confidence.MEDIUM,
            note = when (verifiedBootState?.lowercase(Locale.US)) {
                "green" -> "Green: verified boot chain intact."
                "yellow" -> "Yellow: bootloader locked with a custom key."
                "orange" -> "Orange: bootloader unlocked."
                "red" -> "Red: verification failed."
                null -> "Not exposed on this device."
                else -> null
            }
        )
        fields += FieldBuilder.value(
            "security.oemUnlock", "OEM unlocking allowed",
            when (oemUnlockAllowed) {
                true -> "Yes"
                false -> "No"
                null -> null
            }, SpecGroup.SECURITY, "sys.oem_unlock_allowed", Confidence.MEDIUM
        )

        // ---- SELinux ----
        val selinux = try {
            File("/sys/fs/selinux/enforce").readText().trim().let {
                if (it == "1") "Enforcing" else "Permissive"
            }
        } catch (e: Exception) {
            props.get("ro.boot.selinux")
        }
        fields += FieldBuilder.value(
            "security.selinux", "SELinux mode", selinux, SpecGroup.SECURITY,
            "/sys/fs/selinux/enforce", Confidence.MEDIUM
        )

        // ---- Debug settings ----
        val debuggable = props.getBooleanFlag("ro.debuggable") == true
        val developerOptions = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (e: Exception) {
            null
        }
        val adbEnabled = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) {
            null
        }
        fields += FieldBuilder.value(
            "security.developerOptions", "Developer options",
            when (developerOptions) {
                true -> "Enabled"
                false -> "Disabled"
                null -> null
            }, SpecGroup.SECURITY, "Settings.Global.DEVELOPMENT_SETTINGS_ENABLED", Confidence.MEDIUM
        )
        fields += FieldBuilder.value(
            "security.adb", "ADB debugging",
            when (adbEnabled) {
                true -> "Enabled"
                false -> "Disabled"
                null -> null
            }, SpecGroup.SECURITY, "Settings.Global.ADB_ENABLED", Confidence.MEDIUM
        )

        // ---- Encryption ----
        val encryption = props.get("ro.crypto.state") ?: "unknown"
        fields += FieldBuilder.value(
            "security.encryption", "Storage encryption",
            if (encryption.equals("encrypted", true)) "Encrypted" else encryption,
            SpecGroup.SECURITY, "ro.crypto.state", Confidence.MEDIUM
        )

        // ---- Hardware keystore ----
        val keystoreProbe = probeKeystore()
        fields += FieldBuilder.value(
            "security.hardwareKeystore", "Hardware-backed keystore",
            when (keystoreProbe.first) {
                true -> "Available"
                false -> "Software only"
                null -> null
            },
            SpecGroup.SECURITY, "AndroidKeyStore KeyInfo.isInsideSecureHardware", Confidence.MEDIUM,
            note = "Verified by generating a throw-away AES key in the Android keystore and asking the " +
                "platform where it is stored. The key is deleted immediately."
        )
        val strongBox = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        fields += FieldBuilder.value(
            "security.strongBox", "StrongBox keystore",
            if (strongBox) "Supported" else "Not reported", SpecGroup.SECURITY,
            "PackageManager.FEATURE_STRONGBOX_KEYSTORE"
        )

        // ---- Biometrics ----
        val biometricManager = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(BiometricManager::class.java)
        } else null
        val strongResult = biometricManager?.canAuthenticate(AUTH_STRONG) ?: -1
        val anyResult = biometricManager?.canAuthenticate(AUTH_WEAK) ?: -1
        val biometricStrong = strongResult == BiometricManager.BIOMETRIC_SUCCESS
        val biometricAny = anyResult == BiometricManager.BIOMETRIC_SUCCESS ||
            strongResult == BiometricManager.BIOMETRIC_SUCCESS

        val strongText = when (strongResult) {
            BiometricManager.BIOMETRIC_SUCCESS -> "Available"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No hardware"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware unavailable"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Hardware present, nothing enrolled"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security update required"
            else -> if (Build.VERSION.SDK_INT < 29) "Requires Android 10+" else "Unknown"
        }
        biometricFields += FieldBuilder.value(
            "biometric.strong", "Strong biometrics (class 3)", strongText, SpecGroup.BIOMETRIC,
            "BiometricManager.canAuthenticate(BIOMETRIC_STRONG)", Confidence.HIGH,
            note = "No biometric template, image or identifier is read by this app."
        )
        biometricFields += FieldBuilder.value(
            "biometric.weak", "Weak biometrics (class 2)",
            when (anyResult) {
                BiometricManager.BIOMETRIC_SUCCESS -> "Available"
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No hardware"
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Hardware present, nothing enrolled"
                else -> if (Build.VERSION.SDK_INT < 29) "Requires Android 10+" else "Unavailable"
            },
            SpecGroup.BIOMETRIC, "BiometricManager.canAuthenticate(BIOMETRIC_WEAK)", Confidence.HIGH
        )
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val secureLock = keyguard?.isDeviceSecure == true
        biometricFields += FieldBuilder.value(
            "biometric.deviceSecure", "Secure lock screen",
            if (secureLock) "Configured" else "Not configured", SpecGroup.BIOMETRIC,
            "KeyguardManager.isDeviceSecure"
        )
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        biometricFields += FieldBuilder.value(
            "biometric.deviceOwner", "Device administration",
            if (dpm?.isDeviceOwnerApp(context.packageName) == true) "This app" else "Not this app",
            SpecGroup.BIOMETRIC, "DevicePolicyManager", Confidence.LOW,
            note = "Work-profile and MDM policies can change the biometric behaviour reported above."
        )

        // ---- Installer and signature ----
        var installer: String? = null
        var trusted = false
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val source = pm.getInstallSourceInfo(context.packageName)
                installer = source.installingPackageName
            } else {
                @Suppress("DEPRECATION")
                installer = pm.getInstallerPackageName(context.packageName)
            }
            trusted = installer in listOf(
                "com.android.vending", "com.google.android.packageinstaller",
                "com.android.packageinstaller", "com.samsung.android.app.updatecenter"
            )
        } catch (e: Exception) {
            installer = null
        }
        val signature = readSignatureDigest()

        val customRomSuspected =
            (props.get("ro.build.tags")?.contains("test-keys") == true) ||
                (props.get("ro.build.type") !in listOf("user", null)) ||
                verifiedBootState?.lowercase(Locale.US) == "orange" ||
                selinux == "Permissive"

        val facts = SecurityFacts(
            rootIndicators = rootIndicators,
            bootloaderUnlocked = bootloaderUnlocked,
            verifiedBootState = verifiedBootState,
            debuggable = debuggable,
            developerOptionsEnabled = developerOptions,
            adbEnabled = adbEnabled,
            selinuxMode = selinux,
            encryptionState = encryption,
            hardwareKeystoreAvailable = keystoreProbe.first == true,
            attestationSupported = keystoreProbe.second,
            playIntegrityStatus = "Not configured",
            customRomSuspected = customRomSuspected,
            installedFromTrustedStore = trusted,
            installerPackage = installer,
            signatureDigestSha256 = signature
        )

        fields += FieldBuilder.value(
            "security.installer", "Installing app", installer ?: "Unknown", SpecGroup.SECURITY,
            "PackageManager.getInstallSourceInfo()", Confidence.MEDIUM
        )
        fields += FieldBuilder.value(
            "security.signature", "APK signing certificate (SHA-256)", signature, SpecGroup.SECURITY,
            "PackageManager signing info", Confidence.HIGH, sensitive = true
        )
        fields += FieldBuilder.value(
            "security.attestation", "Hardware attestation",
            if (keystoreProbe.second) "Key attestation available" else "Not available",
            SpecGroup.SECURITY, "AndroidKeyStore attestation", Confidence.MEDIUM
        )

        return SecurityResult(
            facts = facts,
            fields = fields,
            biometricFields = biometricFields,
            biometricStrongAvailable = biometricStrong,
            biometricAnyAvailable = biometricAny,
            secureLockScreen = secureLock
        )
    }

    private companion object {
        // Documented authenticator bit masks; identical on API 29 and 30+.
        const val AUTH_STRONG = 0x000F
        const val AUTH_WEAK = 0x00FF
    }

    /** Generates and deletes a throw-away key to ask the platform where keys are stored. */
    private fun probeKeystore(): Pair<Boolean?, Boolean> {
        val alias = "verify_device_info_probe"
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            val key: SecretKey = generator.generateKey()
            val factory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val insideHardware = info.isInsideSecureHardware
            val attestation = keyStore.getCertificateChain(alias)?.isNotEmpty() == true
            try {
                keyStore.deleteEntry(alias)
            } catch (ignored: Exception) {
            }
            insideHardware to attestation
        } catch (e: Exception) {
            null to false
        }
    }

    private fun readSignatureDigest(): String? = try {
        val info = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signature = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        }
        signature?.toByteArray()?.let {
            MessageDigest.getInstance("SHA-256").digest(it)
                .joinToString(":") { byte -> "%02X".format(byte) }
        }
    } catch (e: Exception) {
        null
    }
}
