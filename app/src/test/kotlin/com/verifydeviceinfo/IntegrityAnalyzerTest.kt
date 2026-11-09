package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.EvidenceSource
import com.verifydeviceinfo.core.model.HardwareFacts
import com.verifydeviceinfo.core.model.IntegrityCheck
import com.verifydeviceinfo.core.model.IntegrityReport
import com.verifydeviceinfo.core.model.SecurityFacts
import com.verifydeviceinfo.core.model.SoftwareFacts
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * `DefaultIntegrityAnalyzer` turns the security facts of a snapshot into the ten checks the
 * Security screen lists and into the verdict the integrity score reads, and until now no test
 * touched it: every existing reference to it in this suite builds a report *around* an integrity
 * result rather than checking one. These tests pin each rule and the aggregate, so a change to a
 * threshold or a status cannot silently move what the screen and the score report.
 *
 * The fixtures are the shared ones; each case varies exactly one fact through `copy`, so a failure
 * names the rule that moved.
 */
class IntegrityAnalyzerTest {

    private fun report(
        security: SecurityFacts = TestFixtures.security(),
        software: SoftwareFacts = TestFixtures.software(),
        hardware: HardwareFacts = TestFixtures.hardware()
    ): IntegrityReport = DefaultIntegrityAnalyzer().analyze(
        TestFixtures.snapshot(security = security, software = software, hardware = hardware)
    )

    private fun IntegrityReport.check(id: String): IntegrityCheck =
        checks.firstOrNull { it.id == id } ?: error("no check with id '$id' in $checks")

    /** A `yyyy-MM-dd` patch date that many whole days before the fixture clock. */
    private fun patchDaysAgo(days: Long): String =
        Instant.ofEpochMilli(TestFixtures.NOW - days * 86_400_000L)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toString()

    @Test
    fun everyCheckIsReportedInOrder() {
        val ids = report().checks.map { it.id }

        assertEquals(
            "the Security screen lists these in this order; a new check is a UI change",
            listOf(
                "root", "bootloader", "verified_boot", "debug_settings", "selinux",
                "patch_level", "encryption", "hardware_keystore", "play_integrity", "installer"
            ),
            ids
        )
        assertTrue("every check carries a title", report().checks.all { it.title.isNotBlank() })
        assertTrue("every check carries evidence", report().checks.all { it.evidence.isNotEmpty() })
    }

    @Test
    fun aCleanDeviceHasNoRootIndicatorsAndPasses() {
        val check = report().check("root")

        assertEquals(DiagnosticStatus.PASS, check.status)
        assertEquals(1, check.evidence.size)
        assertEquals(EvidenceSource.HEURISTIC, check.evidence.single().source)
        assertTrue(
            "the pass is qualified: a hidden root is not excluded",
            check.explanation.contains("A determined root can hide")
        )
    }

    @Test
    fun everyRootIndicatorIsListedOnceInTheWarning() {
        val check = report(
            security = TestFixtures.security(rootIndicators = listOf("su binary on /system/xbin", "Magisk"))
        ).check("root")

        assertEquals(DiagnosticStatus.WARNING, check.status)
        assertTrue(check.explanation.contains("su binary on /system/xbin"))
        assertTrue(check.explanation.contains("Magisk"))
        assertEquals("one evidence line per indicator", 2, check.evidence.size)
        assertEquals(
            listOf("su binary on /system/xbin", "Magisk"),
            check.evidence.map { it.description }
        )
    }

    @Test
    fun bootloaderLockStateHasThreeOutcomes() {
        assertEquals(
            DiagnosticStatus.VERIFIED,
            report(security = TestFixtures.security(bootloaderUnlocked = false)).check("bootloader").status
        )
        assertEquals(
            DiagnosticStatus.WARNING,
            report(security = TestFixtures.security(bootloaderUnlocked = true)).check("bootloader").status
        )
        val unreadable = report(security = TestFixtures.security(bootloaderUnlocked = null)).check("bootloader")
        assertEquals(
            "an unreadable lock state is unverified, never a pass",
            DiagnosticStatus.UNVERIFIED,
            unreadable.status
        )
    }

    @Test
    fun onlyGreenVerifiedBootIsVerified() {
        fun status(state: String?) =
            report(security = TestFixtures.security(verifiedBootState = state)).check("verified_boot")

        assertEquals(DiagnosticStatus.VERIFIED, status("green").status)
        assertEquals("the comparison ignores case", DiagnosticStatus.VERIFIED, status("GREEN").status)
        assertEquals(DiagnosticStatus.WARNING, status("orange").status)
        assertEquals(DiagnosticStatus.WARNING, status("yellow").status)
        assertTrue(
            "the warning quotes the state it read",
            status("orange").explanation.contains("'orange'")
        )
        assertEquals(
            "a device that does not publish the property is not a warning",
            DiagnosticStatus.NOT_SUPPORTED,
            status(null).status
        )
    }

    @Test
    fun aDebuggableBuildOutranksTheDeveloperOptionsSetting() {
        val check = report(
            security = TestFixtures.security().copy(debuggable = true, developerOptionsEnabled = false)
        ).check("debug_settings")

        assertEquals(DiagnosticStatus.WARNING, check.status)
        assertTrue(
            "the debuggable build is the reason given, not the user setting",
            check.explanation.startsWith("ro.debuggable=1")
        )
    }

    @Test
    fun developerOptionsAndAdbEachRaiseAWarning() {
        val devOptions = report(
            security = TestFixtures.security().copy(developerOptionsEnabled = true)
        ).check("debug_settings")
        assertEquals(DiagnosticStatus.WARNING, devOptions.status)
        assertTrue(devOptions.explanation.contains("Developer options or wireless debugging"))

        val adbOnly = report(
            security = TestFixtures.security().copy(developerOptionsEnabled = false, adbEnabled = true)
        ).check("debug_settings")
        assertEquals("ADB alone is enough", DiagnosticStatus.WARNING, adbOnly.status)
    }

    @Test
    fun developerOptionsOffPassesAndUnreadableIsUnverified() {
        val off = report().check("debug_settings")
        assertEquals(DiagnosticStatus.PASS, off.status)
        assertTrue(off.explanation.contains("Developer options are disabled"))
        assertEquals(
            "two evidence lines: the build flag and the setting",
            listOf(EvidenceSource.SYSTEM_PROPERTY, EvidenceSource.ANDROID_API),
            off.evidence.map { it.source }
        )

        val unreadable = report(
            security = TestFixtures.security().copy(developerOptionsEnabled = null, adbEnabled = null)
        ).check("debug_settings")
        assertEquals(
            "a setting that could not be read is unverified, not a pass",
            DiagnosticStatus.UNVERIFIED,
            unreadable.status
        )
    }

    @Test
    fun onlyEnforcingSelinuxIsVerified() {
        fun status(mode: String?) =
            report(security = TestFixtures.security().copy(selinuxMode = mode)).check("selinux")

        assertEquals(DiagnosticStatus.VERIFIED, status("Enforcing").status)
        assertEquals("the comparison ignores case", DiagnosticStatus.VERIFIED, status("enforcing").status)
        assertEquals(DiagnosticStatus.WARNING, status("Permissive").status)
        assertTrue(status("Permissive").explanation.contains("strong sign of a modified system image"))
        assertEquals(DiagnosticStatus.NOT_SUPPORTED, status(null).status)
    }

    @Test
    fun patchAgeThresholdsAreOneYearAndTwo() {
        fun status(patch: String?) =
            report(software = TestFixtures.software(securityPatch = patch)).check("patch_level")

        assertEquals(
            "a patch from last month is current",
            DiagnosticStatus.PASS,
            status(patchDaysAgo(30)).status
        )
        assertEquals(
            "past a year the device is missing platform fixes",
            DiagnosticStatus.WARNING,
            status(patchDaysAgo(400)).status
        )
        assertEquals(
            "past two years the check fails",
            DiagnosticStatus.FAILED,
            status(patchDaysAgo(800)).status
        )
        assertTrue(
            "the failed check says why it matters",
            status(patchDaysAgo(800)).explanation.contains("almost certainly unpatched")
        )
    }

    @Test
    fun anAbsentOrUnparsablePatchDateIsUnverified() {
        assertEquals(
            DiagnosticStatus.UNVERIFIED,
            report(software = TestFixtures.software(securityPatch = null)).check("patch_level").status
        )
        assertEquals(
            DiagnosticStatus.UNVERIFIED,
            report(software = TestFixtures.software(securityPatch = "not-a-date")).check("patch_level").status
        )
        assertEquals(
            "a truncated date cannot be read either",
            DiagnosticStatus.UNVERIFIED,
            report(software = TestFixtures.software(securityPatch = "2024")).check("patch_level").status
        )
    }

    @Test
    fun encryptionIsJudgedBySubstringAsTheCodeCurrentlyDoes() {
        fun status(state: String) =
            report(security = TestFixtures.security().copy(encryptionState = state)).check("encryption")

        assertEquals(DiagnosticStatus.VERIFIED, status("encrypted").status)
        assertEquals("the comparison ignores case", DiagnosticStatus.VERIFIED, status("Encrypted").status)

        // Recorded, not endorsed: "unencrypted" contains "encrypted", so an unencrypted volume is
        // reported as verified. Correcting it moves a status the Security screen shows, so it is
        // filed in docs/IMPROVEMENT-BACKLOG.md (P2) as needing sign-off; this pin makes that fix
        // change a test on purpose rather than by accident.
        assertEquals(DiagnosticStatus.VERIFIED, status("unencrypted").status)
    }

    @Test
    fun hardwareKeystoreMentionsStrongBoxOnlyWhenPresent() {
        val withStrongBox = report().check("hardware_keystore")
        assertEquals(DiagnosticStatus.VERIFIED, withStrongBox.status)
        assertTrue(withStrongBox.explanation.contains("StrongBox"))

        val withoutStrongBox = report(
            hardware = TestFixtures.hardware().copy(strongBoxAvailable = false)
        ).check("hardware_keystore")
        assertEquals(DiagnosticStatus.VERIFIED, withoutStrongBox.status)
        assertFalse(
            "no StrongBox claim when the device does not report one",
            withoutStrongBox.explanation.contains("StrongBox")
        )

        val unavailable = report(
            security = TestFixtures.security().copy(hardwareKeystoreAvailable = false)
        ).check("hardware_keystore")
        assertEquals(
            "not confirmed is unverified, never a pass",
            DiagnosticStatus.UNVERIFIED,
            unavailable.status
        )
    }

    @Test
    fun playIntegrityIsNeverSimulated() {
        val check = report().check("play_integrity")

        assertEquals(DiagnosticStatus.NOT_SUPPORTED, check.status)
        assertEquals(EvidenceSource.OFFICIAL_DOCUMENTATION, check.evidence.single().source)
        assertTrue(
            "the check states that no verdict is produced",
            check.explanation.contains("no verdict is produced and none is simulated")
        )
    }

    @Test
    fun installerSourceDecidesPassOrUnverified() {
        assertEquals(DiagnosticStatus.PASS, report().check("installer").status)

        val sideloaded = report(
            security = TestFixtures.security().copy(
                installedFromTrustedStore = false,
                installerPackage = "com.example.sideloader"
            )
        ).check("installer")
        assertEquals(DiagnosticStatus.UNVERIFIED, sideloaded.status)
        assertTrue("the explanation names the installer", sideloaded.explanation.contains("com.example.sideloader"))

        val unknown = report(
            security = TestFixtures.security().copy(installedFromTrustedStore = false, installerPackage = null)
        ).check("installer")
        assertEquals(DiagnosticStatus.UNVERIFIED, unknown.status)
        assertTrue(unknown.explanation.contains("an unknown source"))
    }

    @Test
    fun theOverallVerdictAggregatesTheChecks() {
        val clean = report()
        assertEquals(DiagnosticStatus.PASS, clean.overallStatus)
        assertEquals(
            "No integrity problems were detected in the checks this app can perform.",
            clean.summary
        )

        val oneWarning = report(security = TestFixtures.security(verifiedBootState = "orange"))
        assertEquals(DiagnosticStatus.WARNING, oneWarning.overallStatus)
        assertEquals("1 integrity check(s) need attention.", oneWarning.summary)

        val failed = report(software = TestFixtures.software(securityPatch = patchDaysAgo(800)))
        assertEquals(
            "a failure outranks any number of warnings",
            DiagnosticStatus.FAILED,
            failed.overallStatus
        )
        assertEquals("The system software has serious integrity problems.", failed.summary)

        val severalWarnings = report(
            security = TestFixtures.security(rootIndicators = listOf("su"), verifiedBootState = "orange")
        )
        assertEquals(DiagnosticStatus.WARNING, severalWarnings.overallStatus)
        assertEquals("2 integrity check(s) need attention.", severalWarnings.summary)
    }
}
