package com.verifydeviceinfo

import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeEstimatorTest {

    private val estimator = DefaultAgeEstimator()

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
            TestFixtures.NOW - 100L * 86_400_000L
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
}
