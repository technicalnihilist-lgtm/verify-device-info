package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.HealthScores
import com.verifydeviceinfo.core.model.IntegrityReport
import com.verifydeviceinfo.core.model.PartsReport
import com.verifydeviceinfo.core.model.ScoreBand
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the coverage rule the score engine is built on: a missing value leaves both numerator and
 * denominator and lowers coverage, and a group whose coverage falls under 30% reports no score at
 * all rather than a number guessed from a sliver of evidence.
 *
 * Every case below is driven through the public [DefaultScoreEngine.score] entry point with an
 * explicit parts and integrity report, so the weights that decide coverage are the only thing
 * being varied.
 *
 * Note on "exactly at the floor": no combination of the engine's weights lands on 30%. Each
 * group's available weight is a sum of the subset of {2f,2f,1f,1f,1f,1f} / {3f,3f,2f,2f,1f,2f,1f}
 * / {3f,3f,1f,2f} / {2f,1f,3f,2f,1f} / {3f,2f,2f,1f,1f} / {2f,3f,3f} / {4f,2f} that is actually
 * reachable, and none of those ratios rounds to 30 — the closest are 22% (below) and 33% (above).
 * The floor is therefore pinned at the tightest reachable pair, which is the strongest statement
 * the engine can make about it without changing behaviour.
 */
class ScoreCoverageTest {

    private val engine = DefaultScoreEngine()

    private fun scoresWith(
        snapshot: DeviceSnapshot = TestFixtures.snapshot(),
        parts: PartsReport = PartsReport(emptyList(), emptyList()),
        integrity: IntegrityReport = IntegrityReport(emptyList(), DiagnosticStatus.PASS, "test"),
        session: DiagnosticSession = DiagnosticSession()
    ): HealthScores = engine.score(snapshot, parts, integrity, session)

    @Test
    fun `the engine emits exactly the score groups the report screen counts`() {
        // The report screen renders a "health scores with inputs and coverage" line from this count, so
        // list and its order are user-visible: a seventh group, a dropped one or a rename would
        // change what the user reads.
        val result = scoresWith()
        assertEquals(
            listOf(
                "Hardware health",
                "Software and security",
                "Component authenticity confidence",
                "Battery condition",
                "Display and sensors",
                "Performance",
                "Repair evidence completeness"
            ),
            result.scores.map { it.name }
        )
    }

    @Test
    fun `battery coverage under the floor withholds the score and above it does not`() {
        // Weights: reported health 3, estimated capacity 3, temperature 1, charge cycles 2 (9 total).
        val cyclesOnly = TestFixtures.battery(
            health = "Unknown",
            healthCode = 0,
            healthPercent = null,
            cycleCount = 120,
            temperatureCelsius = null
        )
        val withheld = scoresWith(snapshot = TestFixtures.snapshot(battery = cyclesOnly))
            .byName("Battery condition")!!
        assertEquals(22, withheld.coveragePercent)
        assertNull(withheld.score)
        assertEquals(ScoreBand.UNKNOWN, withheld.band)
        assertTrue(withheld.summary, withheld.summary.contains("Insufficient data (22%"))

        // Reported health alone is 3 of 9 = 33%, the tightest reachable coverage above the floor.
        val healthOnly = TestFixtures.battery(
            healthCode = 2,
            healthPercent = null,
            cycleCount = null,
            temperatureCelsius = null
        )
        val scored = scoresWith(snapshot = TestFixtures.snapshot(battery = healthOnly))
            .byName("Battery condition")!!
        assertEquals(33, scored.coveragePercent)
        assertEquals(100, scored.score)
        assertEquals(ScoreBand.GOOD, scored.band)
    }

    @Test
    fun `display coverage under the floor withholds the score and above it does not`() {
        // Weights: display configuration 2, refresh rate 1, display/touch tests 3, sensor tests 2,
        // sensor count 1 (9 total). A lone sensor test is 2 of 9 = 22%.
        val sensorTestOnly = scoresWith(
            snapshot = TestFixtures.snapshot(
                hardware = TestFixtures.hardware(display = null, sensors = emptyList())
            ),
            session = DiagnosticSession(
                listOf(DiagnosticResult("sensor_accelerometer", "Accelerometer", DiagnosticStatus.PASS))
            )
        ).byName("Display and sensors")!!
        assertEquals(22, sensorTestOnly.coveragePercent)
        assertNull(sensorTestOnly.score)
        assertEquals(ScoreBand.UNKNOWN, sensorTestOnly.band)

        // Configuration plus refresh rate is 3 of 9 = 33%.
        val configOnly = scoresWith(
            snapshot = TestFixtures.snapshot(
                hardware = TestFixtures.hardware(display = TestFixtures.display(), sensors = emptyList())
            )
        ).byName("Display and sensors")!!
        assertEquals(33, configOnly.coveragePercent)
        assertEquals(100, configOnly.score)
    }

    @Test
    fun `performance coverage under the floor withholds the score and above it does not`() {
        // Weights: free RAM 3, free storage 2, thermal status 2, low-RAM flag 1, CPU cores 1
        // (9 total). The flag and the core count are never missing, so 2 of 9 = 22% is the floor.
        val headroomOnly = TestFixtures.hardware(ramBytes = 0, storageBytes = 0)
        val withheld = scoresWith(
            snapshot = TestFixtures.snapshot(
                hardware = headroomOnly,
                software = TestFixtures.software(thermalStatusSupported = false)
            )
        ).byName("Performance")!!
        assertEquals(22, withheld.coveragePercent)
        assertNull(withheld.score)
        assertEquals(ScoreBand.UNKNOWN, withheld.band)

        // Adding the thermal reading is 4 of 9 = 44%, the next reachable step up.
        val scored = scoresWith(
            snapshot = TestFixtures.snapshot(
                hardware = headroomOnly,
                software = TestFixtures.software(thermalStatus = "None")
            )
        ).byName("Performance")!!
        assertEquals(44, scored.coveragePercent)
        assertEquals(100, scored.score)
    }

    @Test
    fun `an empty parts report withholds both parts-derived scores`() {
        // No components at all leaves every input in both groups missing: 0% coverage, no score.
        val result = scoresWith(parts = PartsReport(emptyList(), emptyList()))

        val authenticity = result.byName("Component authenticity confidence")!!
        assertEquals(0, authenticity.coveragePercent)
        assertNull(authenticity.score)
        assertEquals(ScoreBand.UNKNOWN, authenticity.band)

        val repair = result.byName("Repair evidence completeness")!!
        assertEquals(0, repair.coveragePercent)
        assertNull(repair.score)
        assertEquals(ScoreBand.UNKNOWN, repair.band)
    }

    @Test
    fun `repair evidence scores from diagnostics alone when no component could be inspected`() {
        // Components inspected 4, diagnostics completed 2 (6 total). One recorded test is 2 of 6 =
        // 33%, and twenty points of evidence is a poor but real score rather than a withheld one.
        val repair = scoresWith(
            parts = PartsReport(emptyList(), emptyList()),
            session = DiagnosticSession(
                listOf(DiagnosticResult("touch", "Touch", DiagnosticStatus.PASS))
            )
        ).byName("Repair evidence completeness")!!
        assertEquals(33, repair.coveragePercent)
        assertEquals(20, repair.score)
        assertEquals(ScoreBand.POOR, repair.band)
    }

    @Test
    fun `hardware health can never fall under the coverage floor`() {
        // Four of its inputs — cameras enumerated, biometric, audio output, vibration motor — are
        // always present, so the least coverage it can report is 5 of 8 = 63%. Nothing a device
        // fails to report can turn hardware health into a withheld score.
        val sparse = TestFixtures.hardware(
            display = null,
            cameras = emptyList(),
            sensors = emptyList(),
            storageBytes = 0L
        )
        val hardware = scoresWith(snapshot = TestFixtures.snapshot(hardware = sparse))
            .byName("Hardware health")!!
        assertEquals(63, hardware.coveragePercent)
        assertNotNull(hardware.score)
        assertEquals(60, hardware.score)
    }

    @Test
    fun `software and security can never fall under the coverage floor`() {
        // Root indicators, encryption state, the debuggable flag and the API level are always
        // present: 7 of 14 = 50% is the least this group can report.
        val software = scoresWith(
            snapshot = TestFixtures.snapshot(
                software = TestFixtures.software(securityPatch = null),
                security = TestFixtures.security(bootloaderUnlocked = null)
            ),
            integrity = IntegrityReport(emptyList(), DiagnosticStatus.UNVERIFIED, "test")
        ).byName("Software and security")!!
        assertEquals(50, software.coveragePercent)
        assertNotNull(software.score)
        assertEquals(100, software.score)
    }
}
