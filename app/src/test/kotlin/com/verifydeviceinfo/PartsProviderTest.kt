package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.ComponentState
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsProviderTest {

    private val provider = DefaultPartsProvider()

    @Test
    fun `no reference data leaves components uninspectable instead of counterfeit`() {
        val report = provider.assess(
            TestFixtures.snapshot(),
            null,
            DiagnosticSession()
        )
        val display = report.components.first { it.component == "Display panel" }
        assertEquals(ComponentState.UNABLE_TO_INSPECT, display.state)
        assertTrue(report.components.none { it.state == ComponentState.POSSIBLE_COUNTERFEIT })
    }

    @Test
    fun `a bad battery health code is reported as a possible replacement`() {
        val battery = TestFixtures.battery(health = "Dead", healthCode = 4, healthPercent = null)
        val report = provider.assess(
            TestFixtures.snapshot(battery = battery),
            TestFixtures.referenceMatch(),
            DiagnosticSession()
        )
        val component = report.components.first { it.component == "Battery" }
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, component.state)
    }

    @Test
    fun `a functional failure is not treated as evidence of a replaced part`() {
        val session = DiagnosticSession(
            listOf(
                DiagnosticResult(
                    testId = "microphone",
                    title = "Microphone",
                    status = DiagnosticStatus.FAILED,
                    note = "no signal"
                )
            )
        )
        val report = provider.assess(TestFixtures.snapshot(), TestFixtures.referenceMatch(), session)
        val component = report.components.first { it.component.startsWith("Charging port") }
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, component.state)
        assertTrue(component.explanation.contains("not evidence"))
    }

    @Test
    fun `a lower resolution panel plus identity mismatches can reach counterfeit`() {
        val identity = TestFixtures.identity(
            manufacturer = "unknown-oem",
            brand = "samsung",
            fingerprint = "xiaomi/fuxi/fuxi:14/UKQ1/1234:user/release-keys"
        )
        val hardware = TestFixtures.hardware(
            display = TestFixtures.display(width = 720, height = 1600),
            ramBytes = 3_000_000_000L
        )
        val report = provider.assess(
            TestFixtures.snapshot(identity = identity, hardware = hardware),
            TestFixtures.referenceMatch(),
            DiagnosticSession()
        )
        val motherboard = report.components.first { it.component.startsWith("Motherboard") }
        assertEquals(ComponentState.POSSIBLE_COUNTERFEIT, motherboard.state)
    }

    @Test
    fun `a missing battery health code is not reported as good health`() {
        // Code 0 is what the collector records when the sticky intent carries no EXTRA_HEALTH: the
        // platform said nothing about the pack, which is not the same as saying it is healthy.
        val battery = TestFixtures.battery(
            health = "Unknown",
            healthCode = 0,
            healthPercent = null,
            cycleCount = null
        )
        val report = provider.assess(
            TestFixtures.snapshot(battery = battery),
            TestFixtures.referenceMatch(),
            DiagnosticSession()
        )
        val component = report.components.first { it.component == "Battery" }
        assertEquals(ComponentState.UNABLE_TO_INSPECT, component.state)
        assertFalse(component.explanation.contains("good health"))
        assertEquals(Confidence.NONE, component.confidence)
    }

    @Test
    fun `an unknown battery health code is not evidence of a replacement cell`() {
        // Code 1 is BATTERY_HEALTH_UNKNOWN, the platform's way of saying "cannot tell". A warning
        // here would accuse a pack the device never assessed.
        val battery = TestFixtures.battery(
            health = "Unknown",
            healthCode = 1,
            healthPercent = null,
            cycleCount = null
        )
        val report = provider.assess(
            TestFixtures.snapshot(battery = battery),
            TestFixtures.referenceMatch(),
            DiagnosticSession()
        )
        val component = report.components.first { it.component == "Battery" }
        assertEquals(ComponentState.UNABLE_TO_INSPECT, component.state)
        assertTrue(report.components.none { it.component == "Battery" && it.state == ComponentState.POSSIBLE_REPLACEMENT })
    }

    @Test
    fun `an explicitly good battery health code still reports no anomaly`() {
        val battery = TestFixtures.battery(health = "Good", healthCode = 2, healthPercent = 95)
        val report = provider.assess(
            TestFixtures.snapshot(battery = battery),
            TestFixtures.referenceMatch(),
            DiagnosticSession()
        )
        val component = report.components.first { it.component == "Battery" }
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, component.state)
        assertTrue(component.explanation.contains("good health"))
    }

    @Test
    fun `limitations always explain what cannot be verified`() {
        val report = provider.assess(TestFixtures.snapshot(), TestFixtures.referenceMatch(), DiagnosticSession())
        assertTrue(report.limitations.any { it.contains("serial numbers") })
        assertFalse(report.limitations.isEmpty())
    }
}
