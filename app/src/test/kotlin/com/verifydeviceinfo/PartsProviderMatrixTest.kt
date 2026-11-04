package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.ComponentAssessment
import com.verifydeviceinfo.core.model.ComponentState
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.EvidenceSource
import com.verifydeviceinfo.core.model.PartsReport
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evidence → [ComponentState] matrix for [DefaultPartsProvider].
 *
 * One test per state the provider can actually emit, plus the invariant that ties the five-state
 * classification together: [ComponentState.ORIGINALITY_VERIFIED] is never produced, because Android
 * exposes no component identity to a third-party app. Every row asserts the state chosen for a
 * specific evidence set, not merely that `assess` returned without throwing, so a change to the
 * thresholds or to the ordering of the `when` branches fails here instead of shipping.
 *
 * The seven components are always emitted in this order:
 * Display panel, Battery, Camera modules, Storage, Sensors and biometrics,
 * Charging port/speaker/microphone/vibration, Motherboard and device identity.
 */
class PartsProviderMatrixTest {

    private val provider = DefaultPartsProvider()

    private val expectedComponents = listOf(
        "Display panel",
        "Battery",
        "Camera modules",
        "Storage",
        "Sensors and biometrics",
        "Charging port, speaker, microphone and vibration",
        "Motherboard and device identity"
    )

    // ---- helpers ---------------------------------------------------------------

    private fun assess(
        snapshot: DeviceSnapshot = TestFixtures.snapshot(),
        reference: ReferenceMatch? = TestFixtures.referenceMatch(),
        session: DiagnosticSession = DiagnosticSession()
    ): PartsReport = provider.assess(snapshot, reference, session)

    private fun PartsReport.state(prefix: String): ComponentState =
        components.first { it.component.startsWith(prefix) }.state

    private fun PartsReport.component(prefix: String): ComponentAssessment =
        components.first { it.component.startsWith(prefix) }

    /** A snapshot whose display, cameras, storage, sensors and identity all match the reference. */
    private fun matchingSnapshot(
        identity: com.verifydeviceinfo.core.model.DeviceIdentity = TestFixtures.identity(),
        hardware: com.verifydeviceinfo.core.model.HardwareFacts = matchingHardware()
    ) = TestFixtures.snapshot(identity = identity, hardware = hardware)

    private fun matchingHardware(
        display: com.verifydeviceinfo.core.model.DisplayFacts? = TestFixtures.display(),
        cameras: List<com.verifydeviceinfo.core.model.CameraFacts> = listOf(
            TestFixtures.camera("0", "BACK", 50.0),
            TestFixtures.camera("1", "FRONT", 12.0),
            TestFixtures.camera("2", "BACK", 10.0),
            TestFixtures.camera("3", "BACK", 12.0)
        ),
        sensors: List<com.verifydeviceinfo.core.model.SensorFacts> = listOf(
            TestFixtures.sensor("Accelerometer"),
            TestFixtures.sensor("Gyroscope"),
            TestFixtures.sensor("Proximity"),
            TestFixtures.sensor("Light")
        ),
        storageBytes: Long = 128_000_000_000L,
        ramBytes: Long = 8_000_000_000L
    ) = TestFixtures.hardware(
        display = display,
        cameras = cameras,
        sensors = sensors,
        storageBytes = storageBytes,
        ramBytes = ramBytes
    )

    private fun passingSession(vararg ids: String) = DiagnosticSession(
        ids.map { DiagnosticResult(testId = it, title = it.replaceFirstChar { c -> c.uppercase() }, status = DiagnosticStatus.PASS) }
    )

    // ---- the report shape ------------------------------------------------------

    @Test
    fun `every assessment carries the seven components in a fixed order`() {
        val report = assess()
        assertEquals(expectedComponents, report.components.map { it.component })
    }

    // ---- UNABLE_TO_INSPECT -----------------------------------------------------

    @Test
    fun `UNABLE_TO_INSPECT - no reference data leaves the comparable components uninspectable`() {
        val report = assess(reference = null)
        // Display, cameras, storage and sensors all need a reference to compare against.
        listOf("Display panel", "Camera modules", "Storage", "Sensors and biometrics").forEach { prefix ->
            val component = report.component(prefix)
            assertEquals("$prefix without a reference", ComponentState.UNABLE_TO_INSPECT, component.state)
            // "Not inspected" is reported as an absence of evidence, never as a finding.
            assertEquals(Confidence.NONE, component.confidence)
            assertEquals(1, component.evidence.size)
            assertEquals(EvidenceSource.HEURISTIC, component.evidence.single().source)
        }
        // Battery and motherboard need no reference, so they are still assessed.
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, report.state("Battery"))
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, report.state("Motherboard"))
    }

    @Test
    fun `UNABLE_TO_INSPECT - an unreadable display configuration does not abandon the other rows`() {
        val report = assess(snapshot = matchingSnapshot(hardware = matchingHardware(display = null)))
        assertEquals(ComponentState.UNABLE_TO_INSPECT, report.state("Display panel"))
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, report.state("Camera modules"))
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, report.state("Storage"))
    }

    @Test
    fun `UNABLE_TO_INSPECT - the functional-only components stay uninspectable until a test runs`() {
        val empty = assess()
        assertEquals(ComponentState.UNABLE_TO_INSPECT, empty.state("Charging port"))
        assertEquals(Confidence.NONE, empty.component("Charging port").confidence)

        val tested = assess(session = passingSession("charging"))
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, tested.state("Charging port"))
    }

    // ---- NO_ANOMALY_UNVERIFIED -------------------------------------------------

    @Test
    fun `NO_ANOMALY_UNVERIFIED - a device matching its reference reports no anomaly on every row`() {
        val report = assess(
            snapshot = matchingSnapshot(),
            session = passingSession("charging", "speaker", "microphone", "vibration")
        )
        assertEquals(
            expectedComponents.map { ComponentState.NO_ANOMALY_UNVERIFIED },
            report.components.map { it.state }
        )
        // "No anomaly" is never higher confidence than LOW: matching numbers cannot prove origin.
        report.components.forEach { assertTrue("${it.component} confidence ${it.confidence}",
            it.confidence == Confidence.LOW) }
    }

    // ---- POSSIBLE_REPLACEMENT --------------------------------------------------

    @Test
    fun `POSSIBLE_REPLACEMENT - a lower-resolution panel`() {
        val report = assess(snapshot = matchingSnapshot(hardware = matchingHardware(display = TestFixtures.display(720, 1600))))
        val display = report.component("Display panel")
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, display.state)
        assertEquals(Confidence.MEDIUM, display.confidence)
        assertTrue(display.explanation.contains("720x1600"))
        // The other rows are untouched by a display finding.
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, report.state("Storage"))
    }

    @Test
    fun `POSSIBLE_REPLACEMENT - a panel that differs only on the short side`() {
        // Long side matches, so this is not one of the hardware mismatches the motherboard row counts.
        val report = assess(snapshot = matchingSnapshot(hardware = matchingHardware(display = TestFixtures.display(1000, 2340))))
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, report.state("Display panel"))
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, report.state("Motherboard"))
    }

    @Test
    fun `POSSIBLE_REPLACEMENT - a non-good battery health code`() {
        val report = assess(snapshot = TestFixtures.snapshot(battery = TestFixtures.battery(health = "Dead", healthCode = 4, healthPercent = null)))
        val battery = report.component("Battery")
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, battery.state)
        assertEquals(Confidence.MEDIUM, battery.confidence)
    }

    @Test
    fun `POSSIBLE_REPLACEMENT - capacity below eighty percent with good reported health`() {
        val report = assess(snapshot = TestFixtures.snapshot(battery = TestFixtures.battery(healthPercent = 60)))
        val battery = report.component("Battery")
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, battery.state)
        // A capacity estimate is weaker evidence than the service's own health code.
        assertEquals(Confidence.LOW, battery.confidence)
        assertTrue(battery.explanation.contains("60%"))
    }

    @Test
    fun `POSSIBLE_REPLACEMENT - fewer camera modules than the reference lists`() {
        val hardware = matchingHardware(
            cameras = listOf(TestFixtures.camera("0", "BACK", 50.0), TestFixtures.camera("1", "FRONT", 12.0))
        )
        val report = assess(snapshot = matchingSnapshot(hardware = hardware))
        val cameras = report.component("Camera modules")
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, cameras.state)
        assertTrue(cameras.explanation.contains("1 rear and 1 front"))
    }

    @Test
    fun `POSSIBLE_REPLACEMENT - storage outside the published options`() {
        // 64 GB against a 128/256/512 GB reference is well outside the 12% tolerance.
        val report = assess(snapshot = matchingSnapshot(hardware = matchingHardware(storageBytes = 64_000_000_000L)))
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, report.state("Storage"))
    }

    @Test
    fun `POSSIBLE_REPLACEMENT - a reference sensor that was not enumerated`() {
        val hardware = matchingHardware(
            sensors = listOf(TestFixtures.sensor("Accelerometer"), TestFixtures.sensor("Proximity"), TestFixtures.sensor("Light"))
        )
        val report = assess(snapshot = matchingSnapshot(hardware = hardware))
        val sensors = report.component("Sensors and biometrics")
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, sensors.state)
        assertTrue(sensors.explanation.contains("gyroscope"))
        assertFalse(sensors.explanation.contains("accelerometer"))
    }

    @Test
    fun `POSSIBLE_REPLACEMENT - inconsistent identity strings with matching hardware`() {
        val identity = TestFixtures.identity(manufacturer = "unknown-oem", brand = "samsung")
        val report = assess(snapshot = matchingSnapshot(identity = identity))
        val motherboard = report.component("Motherboard")
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, motherboard.state)
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, report.state("Display panel"))
    }

    @Test
    fun `POSSIBLE_REPLACEMENT - one hardware mismatch with inconsistent identity is not counterfeit`() {
        // Exactly one mismatch (SoC) plus an identity disagreement: below the two-mismatch bar.
        val identity = TestFixtures.identity(manufacturer = "unknown-oem", brand = "samsung", socModel = "SM9999")
        val report = assess(snapshot = matchingSnapshot(identity = identity))
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, report.state("Motherboard"))
    }

    // ---- POSSIBLE_COUNTERFEIT --------------------------------------------------

    @Test
    fun `POSSIBLE_COUNTERFEIT - identity disagreement plus two independent hardware mismatches`() {
        // Two mismatches: the SoC part number and the installed RAM.
        val identity = TestFixtures.identity(manufacturer = "unknown-oem", brand = "samsung", socModel = "SM9999")
        val report = assess(snapshot = matchingSnapshot(identity = identity, hardware = matchingHardware(ramBytes = 3_000_000_000L)))
        val motherboard = report.component("Motherboard")
        assertEquals(ComponentState.POSSIBLE_COUNTERFEIT, motherboard.state)
        assertEquals(Confidence.MEDIUM, motherboard.confidence)
        assertTrue(motherboard.explanation.contains("2 independent hardware values"))
    }

    @Test
    fun `POSSIBLE_COUNTERFEIT - hardware mismatches alone never reach counterfeit`() {
        // Consistent identity, three hardware mismatches: no identity warning, so no counterfeit.
        val hardware = matchingHardware(
            display = TestFixtures.display(720, 1600),
            storageBytes = 64_000_000_000L,
            ramBytes = 3_000_000_000L
        )
        val report = assess(snapshot = matchingSnapshot(hardware = hardware))
        assertEquals(ComponentState.NO_ANOMALY_UNVERIFIED, report.state("Motherboard"))
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, report.state("Display panel"))
        assertEquals(ComponentState.POSSIBLE_REPLACEMENT, report.state("Storage"))
    }

    // ---- ORIGINALITY_VERIFIED --------------------------------------------------

    @Test
    fun `ORIGINALITY_VERIFIED is never emitted for any evidence set`() {
        val clean = matchingSnapshot()
        val scenarios: List<Pair<String, PartsReport>> = listOf(
            "no reference" to assess(reference = null),
            "matching device" to assess(snapshot = clean, session = passingSession("charging", "speaker", "microphone", "vibration")),
            "unreadable display" to assess(snapshot = matchingSnapshot(hardware = matchingHardware(display = null))),
            "bad battery health" to assess(snapshot = TestFixtures.snapshot(battery = TestFixtures.battery(health = "Dead", healthCode = 4, healthPercent = null))),
            "low capacity" to assess(snapshot = TestFixtures.snapshot(battery = TestFixtures.battery(healthPercent = 60))),
            "camera mismatch" to assess(snapshot = matchingSnapshot(hardware = matchingHardware(cameras = emptyList()))),
            "storage mismatch" to assess(snapshot = matchingSnapshot(hardware = matchingHardware(storageBytes = 64_000_000_000L))),
            "sensor mismatch" to assess(snapshot = matchingSnapshot(hardware = matchingHardware(sensors = emptyList()))),
            "identity only" to assess(snapshot = matchingSnapshot(identity = TestFixtures.identity(manufacturer = "unknown-oem", brand = "samsung"))),
            "counterfeit pattern" to assess(
                snapshot = matchingSnapshot(
                    identity = TestFixtures.identity(manufacturer = "unknown-oem", brand = "samsung", socModel = "SM9999"),
                    hardware = matchingHardware(ramBytes = 3_000_000_000L)
                )
            ),
            "functional failure" to assess(
                session = DiagnosticSession(
                    listOf(DiagnosticResult(testId = "microphone", title = "Microphone", status = DiagnosticStatus.FAILED, note = "no signal"))
                )
            )
        )

        val reachable = setOf(
            ComponentState.POSSIBLE_REPLACEMENT,
            ComponentState.POSSIBLE_COUNTERFEIT,
            ComponentState.NO_ANOMALY_UNVERIFIED,
            ComponentState.UNABLE_TO_INSPECT
        )
        scenarios.forEach { (label, report) ->
            report.components.forEach { component ->
                assertNotEquals("$label: ${component.component}", ComponentState.ORIGINALITY_VERIFIED, component.state)
                assertTrue("$label: unexpected state ${component.state} on ${component.component}",
                    component.state in reachable)
                assertTrue("$label: ${component.component} has no explanation", component.explanation.isNotBlank())
                assertTrue("$label: ${component.component} has no evidence", component.evidence.isNotEmpty())
                // Confidence is NONE exactly when nothing could be inspected.
                assertEquals(
                    "$label: ${component.component} confidence",
                    component.state == ComponentState.UNABLE_TO_INSPECT,
                    component.confidence == Confidence.NONE
                )
            }
        }
    }
}
