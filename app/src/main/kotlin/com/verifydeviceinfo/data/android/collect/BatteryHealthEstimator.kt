package com.verifydeviceinfo.data.android.collect

import kotlin.math.roundToInt

/**
 * Derives the "estimated capacity vs design" figure from the charge counter.
 *
 * Deliberately free of framework types so the plausibility guards can be exercised by plain unit
 * tests without a device.
 *
 * The charge counter is documented in microampere-hours, but vendor implementations have been seen
 * to report milliampere-hours, and the design capacity comes from a hidden platform class through
 * reflection, so it is only best-effort. Either mistake used to be clamped into a plausible-looking
 * percentage — 1% or 150% — which reads on screen as a measurement. A ratio outside the credible
 * range is withheld instead, so the report says the capacity is not measurable rather than
 * inventing a number.
 */
internal object BatteryHealthEstimator {

    /** A charge counter is only trusted at, or within a point of, full charge. */
    const val MIN_FULL_CHARGE_PERCENT = 99

    /**
     * Below this the reading is a unit or design-capacity error, not a worn pack: a battery holding
     * under a fifth of its design capacity would not run the device.
     */
    const val MIN_PLAUSIBLE_PERCENT = 20

    /** Above this the design capacity is under-reported rather than the pack being oversized. */
    const val MAX_PLAUSIBLE_PERCENT = 150

    /**
     * Charge counter in microampere-hours to an estimated full-charge capacity in milliampere-hours,
     * or null when the device is not at full charge or the counter is unusable.
     */
    fun estimatedFullChargeMah(percent: Int?, chargeCounterMicroAmpHours: Long?): Int? {
        if (percent == null || percent < MIN_FULL_CHARGE_PERCENT) return null
        val counter = chargeCounterMicroAmpHours ?: return null
        if (counter <= 0L) return null
        return (counter / 1000.0).roundToInt().takeIf { it > 0 }
    }

    /**
     * Estimated full-charge capacity as a percentage of the design capacity, or null when the
     * device is not at full charge, either input is missing, or the ratio is not credible.
     */
    fun healthPercent(
        percent: Int?,
        chargeCounterMicroAmpHours: Long?,
        designCapacityMah: Int?
    ): Int? {
        val fullMah = estimatedFullChargeMah(percent, chargeCounterMicroAmpHours) ?: return null
        val designMah = designCapacityMah ?: return null
        if (designMah <= 0) return null
        val ratio = fullMah * 100.0 / designMah
        if (!ratio.isFinite()) return null
        val rounded = ratio.roundToInt()
        return rounded.takeIf { it in MIN_PLAUSIBLE_PERCENT..MAX_PLAUSIBLE_PERCENT }
    }
}
