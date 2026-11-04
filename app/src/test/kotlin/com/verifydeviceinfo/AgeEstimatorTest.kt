package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeEstimatorTest {

    private val estimator = DefaultAgeEstimator()

    private val day = 86_400_000L

    @Test
    fun `launch age and firmware age are separate values`() {
        val report = estimator.estimate(TestFixtures.snapshot(), TestFixtures.referenceMatch(), null)
        assertNotNull(report.modelLaunchDate)
        assertNotNull(report.modelLaunchAgeDays)
        assertNotNull(report.firmwareBuildAgeDays)
        assertTrue(report.modelLaunchAgeDays!! > report.firmwareBuildAgeDays!!)
    }

    @Test
    fun `ownership age is only produced when a purchase date is supplied`() {
        val without = estimator.estimate(TestFixtures.snapshot(), TestFixtures.referenceMatch(), null)
        assertNull(without.ownershipAgeDays)

        val with = estimator.estimate(
            TestFixtures.snapshot(),
            TestFixtures.referenceMatch(),
            TestFixtures.NOW - 100L * day
        )
        assertEquals(100L, with.ownershipAgeDays)
    }

    @Test
    fun `notes state that a launch date is not a manufacturing date`() {
        val report = estimator.estimate(TestFixtures.snapshot(), TestFixtures.referenceMatch(), null)
        assertTrue(report.notes.any { it.contains("not this handset") })
        assertTrue(report.notes.any { it.contains("Manufacturing date is not exposed") })
    }

    @Test
    fun `without reference data the model age stays unknown`() {
        val report = estimator.estimate(TestFixtures.snapshot(), null, null)
        assertNull(report.modelLaunchAgeDays)
        assertNotNull(report.firmwareBuildAgeDays)
    }

    // ---- Boundaries: unknown launch date ------------------------------------

    @Test
    fun `reference entry without a release date leaves model age unknown`() {
        val undated = TestFixtures.reference().copy(releaseDate = "")
        val report = estimator.estimate(
            TestFixtures.snapshot(),
            ReferenceMatch(undated, "SM-S911"),
            null
        )

        assertNull(report.modelLaunchAgeDays)
        assertNotNull(report.firmwareBuildAgeDays)
        assertTrue(report.notes.any { it.contains("No reference launch date is available") })
    }

    @Test
    fun `malformed release date is treated as unknown rather than throwing`() {
        val malformed = TestFixtures.reference().copy(releaseDate = "Q1 2023")
        val report = estimator.estimate(
            TestFixtures.snapshot(),
            ReferenceMatch(malformed, "SM-S911"),
            null
        )

        assertNull(report.modelLaunchAgeDays)
        assertNotNull(report.firmwareBuildAgeDays)
    }

    // ---- Boundaries: unknown firmware build date ----------------------------

    @Test
    fun `missing firmware build date leaves firmware age unknown`() {
        val snapshot = TestFixtures.snapshot(
            identity = TestFixtures.identity().copy(buildTimeMillis = null)
        )
        val report = estimator.estimate(snapshot, TestFixtures.referenceMatch(), null)

        assertNull(report.firmwareBuildAgeDays)
        // The report keeps the date null rather than a placeholder; AgeScreen renders null as
        // "Unknown", so the unknown case stays distinguishable from a real date.
        assertNull(report.firmwareBuildDate)
        // The launch date is still known, so confidence stays LOW rather than dropping to NONE.
        assertEquals(Confidence.LOW, report.confidence)
    }

    @Test
    fun `no launch date, no build date and no purchase date yields NONE confidence`() {
        val snapshot = TestFixtures.snapshot(
            identity = TestFixtures.identity().copy(buildTimeMillis = null),
            battery = TestFixtures.battery(cycleCount = null),
            software = TestFixtures.software(securityPatch = "")
        )
        val report = estimator.estimate(snapshot, null, null)

        assertNull(report.modelLaunchAgeDays)
        assertNull(report.firmwareBuildAgeDays)
        assertNull(report.ownershipAgeDays)
        assertNull(report.estimatedUsageAgeDays)
        assertNull(report.daysSinceSecurityPatch)
        assertEquals(Confidence.NONE, report.confidence)
    }

    // ---- Boundaries: purchase date in the future ----------------------------

    @Test
    fun `purchase date in the future never produces a negative ownership age`() {
        val future = TestFixtures.NOW + 30L * day
        val report = estimator.estimate(TestFixtures.snapshot(), TestFixtures.referenceMatch(), future)

        // Formatters.daysBetween clamps at zero, so a clock or entry error cannot surface as a
        // negative duration in the report.
        assertEquals(0L, report.ownershipAgeDays)
        assertNotNull(report.purchaseDateUsed)
    }

    @Test
    fun `purchase date in the future does not make usage age negative`() {
        val snapshot = TestFixtures.snapshot(battery = TestFixtures.battery(cycleCount = null))
        val future = TestFixtures.NOW + 30L * day
        val report = estimator.estimate(snapshot, TestFixtures.referenceMatch(), future)

        assertEquals(0L, report.estimatedUsageAgeDays)
    }

    // ---- Boundaries: zero charge cycles -------------------------------------

    @Test
    fun `zero cycle count is treated as no cycle count`() {
        val snapshot = TestFixtures.snapshot(battery = TestFixtures.battery(cycleCount = 0))

        val withoutPurchase = estimator.estimate(snapshot, TestFixtures.referenceMatch(), null)
        assertNull(withoutPurchase.estimatedUsageAgeDays)
        assertTrue(withoutPurchase.notes.any { it.contains("Usage age could not be estimated") })

        val withPurchase = estimator.estimate(
            snapshot,
            TestFixtures.referenceMatch(),
            TestFixtures.NOW - 100L * day
        )
        assertEquals(100L, withPurchase.estimatedUsageAgeDays)
    }

    @Test
    fun `one cycle count is used instead of the ownership fallback`() {
        val snapshot = TestFixtures.snapshot(battery = TestFixtures.battery(cycleCount = 1))
        val report = estimator.estimate(
            snapshot,
            TestFixtures.referenceMatch(),
            TestFixtures.NOW - 400L * day
        )

        // 1 cycle / 0.8 cycles per day truncates to 1 day, and the cycle-derived value wins over
        // the 400-day ownership fallback.
        assertEquals(1L, report.estimatedUsageAgeDays)
    }
}
