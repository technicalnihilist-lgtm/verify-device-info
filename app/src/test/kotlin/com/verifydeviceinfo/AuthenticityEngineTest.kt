package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.RiskLevel
import com.verifydeviceinfo.domain.analysis.DefaultAuthenticityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticityEngineTest {

    private val engine = DefaultAuthenticityEngine()

    @Test
    fun `a consistent device with reference data has no high risk finding`() {
        val report = engine.analyze(TestFixtures.snapshot(), TestFixtures.referenceMatch())
        assertTrue(
            "expected no HIGH risk, found ${report.riskLevel}",
            report.riskLevel != RiskLevel.HIGH
        )
        assertTrue(report.findings.any { it.id == "identity.brand" && it.status == DiagnosticStatus.VERIFIED })
        assertTrue(report.findings.any { it.id == "soc.reference" && it.status == DiagnosticStatus.VERIFIED })
        assertTrue(report.findings.any { it.id == "display.reference" && it.status == DiagnosticStatus.VERIFIED })
    }

    @Test
    fun `a spoofed fingerprint is reported as a warning, not as proof`() {
        val identity = TestFixtures.identity(
            manufacturer = "samsung",
            brand = "samsung",
            fingerprint = "xiaomi/fuxi/fuxi:14/UKQ1/1234:user/release-keys"
        )
        val report = engine.analyze(TestFixtures.snapshot(identity = identity), TestFixtures.referenceMatch())
        val finding = report.findings.firstOrNull { it.id == "build.fingerprint" }
        assertNotNull(finding)
        assertEquals(DiagnosticStatus.WARNING, finding!!.status)
        assertEquals(RiskLevel.ELEVATED, finding.risk)
        assertTrue(finding.explanation.contains("Spoofed") || finding.explanation.contains("hand-edited"))
    }

    @Test
    fun `missing reference data yields UNVERIFIED rather than a pass or a failure`() {
        val report = engine.analyze(TestFixtures.snapshot(), null)
        val finding = report.findings.firstOrNull { it.id == "reference.missing" }
        assertNotNull(finding)
        assertEquals(DiagnosticStatus.UNVERIFIED, finding!!.status)
        assertEquals(RiskLevel.UNKNOWN, report.riskLevel)
    }

    @Test
    fun `emulator markers raise the risk level`() {
        val identity = TestFixtures.identity(
            model = "sdk_gphone64_x86_64",
            device = "emu64xa",
            fingerprint = "google/sdk_gphone64_x86_64/emu64xa:14/UE1A/1234:userdebug/test-keys"
        )
        val report = engine.analyze(TestFixtures.snapshot(identity = identity), null)
        assertEquals(RiskLevel.HIGH, report.riskLevel)
        assertTrue(report.findings.any { it.id == "identity.emulator" })
    }

    @Test
    fun `test-keys firmware is flagged as an indicator`() {
        val identity = TestFixtures.identity(tags = "test-keys", type = "userdebug")
        val report = engine.analyze(TestFixtures.snapshot(identity = identity), TestFixtures.referenceMatch())
        assertTrue(report.findings.any { it.id == "build.tags" && it.status == DiagnosticStatus.WARNING })
        assertTrue(report.findings.any { it.id == "build.type" && it.status == DiagnosticStatus.WARNING })
    }

    @Test
    fun `a lower resolution panel is reported as an indicator only`() {
        val hardware = TestFixtures.hardware(display = TestFixtures.display(width = 720, height = 1600))
        val report = engine.analyze(TestFixtures.snapshot(hardware = hardware), TestFixtures.referenceMatch())
        val finding = report.findings.firstOrNull { it.id == "display.reference" }
        assertNotNull(finding)
        assertEquals(DiagnosticStatus.WARNING, finding!!.status)
        assertTrue(finding.explanation.contains("non-original"))
    }

    @Test
    fun `the report always states that no manufacturer API was used`() {
        val report = engine.analyze(TestFixtures.snapshot(), TestFixtures.referenceMatch())
        assertTrue(
            report.findings.any {
                it.id == "manufacturer.verification" && it.status == DiagnosticStatus.NOT_SUPPORTED
            }
        )
    }
}
