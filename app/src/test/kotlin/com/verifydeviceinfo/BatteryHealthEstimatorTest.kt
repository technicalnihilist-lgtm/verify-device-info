package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.collect.BatteryHealthEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the battery capacity estimate and the guards around it. Before these guards the figure was
 * clamped into 1%..150%, so a counter reported in the wrong unit still produced a number that
 * looked like a measurement.
 */
class BatteryHealthEstimatorTest {

    // A 3900 mAh design with a 3700 mAh counter at full charge is 94.8%, rendered as 95%.
    private val design = 3900
    private val counterAtFull = 3_700_000L

    @Test
    fun reportsAPercentageAtFullCharge() {
        assertEquals(95, BatteryHealthEstimator.healthPercent(100, counterAtFull, design))
    }

    @Test
    fun withholdsBelowFullCharge() {
        // 78% charge is clearly not full, so the counter under-reports the capacity.
        assertNull(BatteryHealthEstimator.healthPercent(78, counterAtFull, design))
        assertNull(BatteryHealthEstimator.healthPercent(98, counterAtFull, design))
        assertEquals(95, BatteryHealthEstimator.healthPercent(99, counterAtFull, design))
    }

    @Test
    fun withholdsWhenTheCounterIsUnusable() {
        assertNull(BatteryHealthEstimator.healthPercent(100, null, design))
        assertNull(BatteryHealthEstimator.healthPercent(100, 0L, design))
        assertNull(BatteryHealthEstimator.healthPercent(100, -1L, design))
        // 500 uAh rounds to 0 mAh: a counter that small carries no information.
        assertNull(BatteryHealthEstimator.healthPercent(100, 500L, design))
    }

    @Test
    fun withholdsWhenTheDesignCapacityIsMissing() {
        assertNull(BatteryHealthEstimator.healthPercent(100, counterAtFull, null))
        assertNull(BatteryHealthEstimator.healthPercent(100, counterAtFull, 0))
        assertNull(BatteryHealthEstimator.healthPercent(100, counterAtFull, -3900))
    }

    @Test
    fun withholdsWhenTheChargeLevelIsMissing() {
        assertNull(BatteryHealthEstimator.healthPercent(null, counterAtFull, design))
    }

    @Test
    fun withholdsACounterReportedInMilliampHours() {
        // A vendor that reports 3700 instead of 3_700_000 yields 3 mAh, i.e. 0.08% of design.
        // The old code clamped this to 1%; it is now withheld.
        assertNull(BatteryHealthEstimator.healthPercent(100, 3_700L, design))
    }

    @Test
    fun withholdsAnUnderReportedDesignCapacity() {
        // 3700 mAh against a 1000 mAh design is 370%, which the old code clamped to 150%.
        assertNull(BatteryHealthEstimator.healthPercent(100, counterAtFull, 1000))
    }

    @Test
    fun acceptsAWornPackInsideTheRange() {
        // 3000 mAh against a 5000 mAh design is 60%.
        assertEquals(60, BatteryHealthEstimator.healthPercent(100, 3_000_000L, 5000))
    }

    @Test
    fun acceptsAReplacementPackAboveDesignCapacity() {
        // 4700 mAh against a 3900 mAh design is 120.5%, credible for a replaced cell.
        assertEquals(121, BatteryHealthEstimator.healthPercent(100, 4_700_000L, design))
    }

    @Test
    fun holdsThePlausibilityBoundaries() {
        assertEquals(
            BatteryHealthEstimator.MIN_PLAUSIBLE_PERCENT,
            BatteryHealthEstimator.healthPercent(100, 1_000_000L, 5000)
        )
        assertNull(BatteryHealthEstimator.healthPercent(100, 950_000L, 5000))
        assertEquals(
            BatteryHealthEstimator.MAX_PLAUSIBLE_PERCENT,
            BatteryHealthEstimator.healthPercent(100, 3_000_000L, 2000)
        )
        assertNull(BatteryHealthEstimator.healthPercent(100, 3_100_000L, 2000))
    }

    @Test
    fun estimatedCapacityConvertsMicroAmpHours() {
        assertEquals(3700, BatteryHealthEstimator.estimatedFullChargeMah(100, counterAtFull))
        assertEquals(3, BatteryHealthEstimator.estimatedFullChargeMah(100, 3_400L))
        assertNull(BatteryHealthEstimator.estimatedFullChargeMah(50, counterAtFull))
    }
}
