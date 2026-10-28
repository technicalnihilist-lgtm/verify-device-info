package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.ComponentState
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.ScoreBand
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreEngineTest {

    private val engine = DefaultScoreEngine()
    private val partsProvider = DefaultPartsProvider()
    private val integrityAnalyzer = DefaultIntegrityAnalyzer()

    private fun scores(
        snapshot: com.verifydeviceinfo.core.model.DeviceSnapshot,
        session: DiagnosticSession
    ): com.verifydeviceinfo.core.model.HealthScores {
        val parts = partsProvider.assess(snapshot, TestFixtures.referenceMatch(), session)
        val integrity = integrityAnalyzer.analyze(snapshot)
        return engine.score(snapshot, parts, integrity, session)
    }

    @Test
    fun `a healthy device produces a hardware score with high coverage`() {
        val result = scores(TestFixtures.snapshot(), DiagnosticSession())
        val hardware = result.byName("Hardware health")
        assertNotNull(hardware)
        assertNotNull(hardware!!.score)
        assertTrue(hardware.coveragePercent > 50)
        assertTrue(hardware.inputs.isNotEmpty())
    }

    @Test
    fun `missing battery data withholds the score instead of scoring zero`() {
        val battery = TestFixtures.battery(health = "Unknown", healthCode = 0, healthPercent = null, cycleCount = null)
        val result = scores(TestFixtures.snapshot(battery = battery), DiagnosticSession())
        val batteryScore = result.byName("Battery condition")
        assertNotNull(batteryScore)
        // Only the temperature input remains, which is below the 30% coverage floor.
        assertNull(batteryScore!!.score)
        assertEquals(ScoreBand.UNKNOWN, batteryScore.band)
    }

    @Test
    fun `an old security patch lowers the security score`() {
        val recent = scores(TestFixtures.snapshot(), DiagnosticSession())
        val old = scores(TestFixtures.snapshot(software = TestFixtures.software(securityPatch = "2019-01-01")), DiagnosticSession())
        val recentScore = recent.byName("Software and security")!!.score!!
        val oldScore = old.byName("Software and security")!!.score!!
        assertTrue("recent=$recentScore old=$oldScore", oldScore < recentScore)
    }

    @Test
    fun `root indicators reduce the security score`() {
        val clean = scores(TestFixtures.snapshot(), DiagnosticSession())
        val rooted = scores(
            TestFixtures.snapshot(security = TestFixtures.security(rootIndicators = listOf("Found /system/bin/su"))),
            DiagnosticSession()
        )
        assertTrue(
            rooted.byName("Software and security")!!.score!! <
                clean.byName("Software and security")!!.score!!
        )
    }

    @Test
    fun `repair evidence completeness reflects inspection coverage not health`() {
        val result = scores(TestFixtures.snapshot(), DiagnosticSession())
        val completeness = result.byName("Repair evidence completeness")
        assertNotNull(completeness)
        assertNotNull(completeness!!.score)
        val parts = partsProvider.assess(TestFixtures.snapshot(), TestFixtures.referenceMatch(), DiagnosticSession())
        assertTrue(parts.components.any { it.state == ComponentState.NO_ANOMALY_UNVERIFIED })
    }

    @Test
    fun `running a failing test lowers hardware health`() {
        val clean = scores(TestFixtures.snapshot(), DiagnosticSession())
        val failing = scores(
            TestFixtures.snapshot(),
            DiagnosticSession(
                listOf(
                    DiagnosticResult("display_colour", "Colour", DiagnosticStatus.FAILED),
                    DiagnosticResult("touch", "Touch", DiagnosticStatus.PASS)
                )
            )
        )
        assertTrue(
            failing.byName("Hardware health")!!.score!! <= clean.byName("Hardware health")!!.score!!
        )
        assertTrue(failing.byName("Display and sensors")!!.score!! < 100)
    }
}
