package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.domain.analysis.AnalysisSupport
import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The security patch level is read by three consumers — the Security screen's check, the age
 * estimate's `daysSinceSecurityPatch` and the Software-and-security score — and each of them used
 * to carry its own copy of a lenient `Calendar` parse. `Calendar` rolls an out-of-range field over
 * instead of refusing it, so `2024-13` became 1 January 2025 and `2024-00` became 1 December 2023:
 * a patch level that is not a date was reported as a plausible one, and because `daysBetween` floors
 * at zero, a rolled-forward month could only make the device look *better* patched than it was.
 *
 * These tests pin the shared parse and the three consumers. A valid patch level must produce the
 * instant it always did — the day component is ignored and the 1st of the month is used, which is
 * what the three old copies did — so nothing changes for a device that publishes a real date.
 */
class SecurityPatchDateTest {

    private fun utcMillis(year: Int, month: Int, day: Int = 1): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    // ---- the shared parse ----------------------------------------------------

    @Test
    fun `a full patch date reads as the first of its month`() {
        assertEquals(utcMillis(2024, 5), AnalysisSupport.patchMonthToMillis("2024-05-05"))
        assertEquals(utcMillis(2024, 5), AnalysisSupport.patchMonthToMillis("2024-05-31"))
        assertEquals(
            "the day component does not move the result",
            AnalysisSupport.patchMonthToMillis("2024-05-01"),
            AnalysisSupport.patchMonthToMillis("2024-05-05")
        )
    }

    @Test
    fun `a year and month with no day still reads`() {
        assertEquals(utcMillis(2024, 5), AnalysisSupport.patchMonthToMillis("2024-05"))
    }

    @Test
    fun `an impossible month is refused rather than rolled over`() {
        // Lenient Calendar turned these into 1 January 2025 and 1 December 2023 respectively.
        assertNull("month 13 used to become the next January", AnalysisSupport.patchMonthToMillis("2024-13-01"))
        assertNull("month 0 used to become the previous December", AnalysisSupport.patchMonthToMillis("2024-00-15"))
        assertNull(AnalysisSupport.patchMonthToMillis("2024-99-01"))
    }

    @Test
    fun `a value that carries no date at all is refused`() {
        assertNull(AnalysisSupport.patchMonthToMillis(null))
        assertNull(AnalysisSupport.patchMonthToMillis(""))
        assertNull(AnalysisSupport.patchMonthToMillis("   "))
        assertNull(AnalysisSupport.patchMonthToMillis("2024"))
        assertNull(AnalysisSupport.patchMonthToMillis("not-a-date"))
        assertNull(AnalysisSupport.patchMonthToMillis("20240505"))
        assertNull(AnalysisSupport.patchMonthToMillis("20x4-05-05"))
    }

    // ---- the three consumers -------------------------------------------------

    @Test
    fun `the integrity check reports a rolled-over month as unverified`() {
        val analyzer = DefaultIntegrityAnalyzer()
        val status = analyzer
            .analyze(TestFixtures.snapshot(software = TestFixtures.software(securityPatch = "2024-13-01")))
            .checks.first { it.id == "patch_level" }

        // Before this fix the invented January date read as 165 days old and the check passed.
        assertEquals(DiagnosticStatus.UNVERIFIED, status.status)
        assertEquals(
            "no patch date was reported by the build",
            "No security patch date was reported by the build.",
            status.explanation
        )
        assertEquals(
            "the raw value is still shown as evidence, so nothing is hidden",
            "ro.build.version.security_patch=2024-13-01",
            status.evidence.first().description
        )
    }

    @Test
    fun `the age estimate reports no patch age for an impossible month`() {
        val estimator = DefaultAgeEstimator()
        val bogus = estimator.estimate(
            TestFixtures.snapshot(software = TestFixtures.software(securityPatch = "2024-13-01")),
            TestFixtures.referenceMatch(),
            null
        )
        assertNull(bogus.daysSinceSecurityPatch)

        val valid = estimator.estimate(
            TestFixtures.snapshot(software = TestFixtures.software(securityPatch = "2024-05-05")),
            TestFixtures.referenceMatch(),
            null
        )
        assertNotNull("a real patch level still yields an age", valid.daysSinceSecurityPatch)
    }

    @Test
    fun `the score treats an impossible month exactly like an absent patch level`() {
        val engine = DefaultScoreEngine()
        val parts = DefaultPartsProvider()
        val integrity = DefaultIntegrityAnalyzer()

        fun softwareScore(patch: String?) = run {
            val snapshot = TestFixtures.snapshot(software = TestFixtures.software(securityPatch = patch))
            val session = DiagnosticSession()
            val partsReport = parts.assess(snapshot, TestFixtures.referenceMatch(), session)
            engine.score(snapshot, partsReport, integrity.analyze(snapshot), session)
                .byName("Software and security")!!
        }

        val absent = softwareScore(null)
        val bogus = softwareScore("2024-13-01")
        assertEquals(
            "an unreadable patch level is excluded, not scored as a recent one",
            absent.score,
            bogus.score
        )
        assertEquals(absent.band, bogus.band)
    }
}
