package com.verifydeviceinfo.domain.analysis

import com.verifydeviceinfo.core.model.AgeReport
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DeviceSnapshot
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.domain.DeviceAgeEstimator
import com.verifydeviceinfo.core.util.Formatters

/**
 * Age estimation from independent sources, each reported separately.
 *
 * The model's launch date is never presented as this handset's manufacturing date, and no
 * serial number is decoded — no official public decoder exists for the formats in use.
 */
class DefaultAgeEstimator : DeviceAgeEstimator {

    override fun estimate(
        snapshot: DeviceSnapshot,
        reference: ReferenceMatch?,
        purchaseDateMillis: Long?
    ): AgeReport {
        val now = snapshot.collectedAtMillis
        val notes = mutableListOf<String>()

        val launchMillis = reference?.let { AnalysisSupport.releaseDateToMillis(it.device.releaseDate) }
        val launchAge = launchMillis?.let { Formatters.daysBetween(it, now) }
        if (launchMillis != null) {
            notes += "Model launch date comes from the bundled reference entry for " +
                "${reference.device.brand} ${reference.device.marketingName} " +
                "(source: ${reference.device.sourceUrl}, curated ${reference.device.sourceDate})."
            notes += "Launch age describes the model, not this handset. A handset bought yesterday is still " +
                "as old as the model."
        } else {
            notes += "No reference launch date is available for this model, so model age is unknown."
        }

        val buildMillis = snapshot.identity.buildTimeMillis
        val buildAge = buildMillis?.let { Formatters.daysBetween(it, now) }
        if (buildMillis != null) {
            notes += "Firmware build date (ro.build.date) is the date the installed system image was " +
                "compiled. It is an upper bound on manufacturing age: the handset cannot be older than its " +
                "firmware, but it may be newer."
        }

        val ownershipAge = purchaseDateMillis?.let { Formatters.daysBetween(it, now) }
        if (purchaseDateMillis != null) {
            notes += "Ownership age uses the purchase date you entered. It is not read from the device."
        } else {
            notes += "No purchase date was provided, so ownership age is unknown. The app does not guess it " +
                "from app install time, which only reflects when this app was installed."
        }

        val cycles = snapshot.battery.cycleCount
        val estimatedUsage = when {
            cycles != null && cycles > 0 -> {
                notes += "Usage age is inferred from $cycles charge cycles at an assumed 0.8 cycles per day. " +
                    "That assumption is a population average, so the result is a rough estimate only."
                (cycles / 0.8).toLong()
            }
            ownershipAge != null -> {
                notes += "Usage age falls back to ownership age because no cycle count is exposed on this " +
                    "Android version."
                ownershipAge
            }
            else -> {
                notes += "Usage age could not be estimated: this Android version exposes neither a cycle " +
                    "count nor a purchase date."
                null
            }
        }

        val patchMillis = parseSecurityPatch(snapshot.software.securityPatch)
        val patchAge = patchMillis?.let { Formatters.daysBetween(it, now) }
        if (patchAge != null) {
            notes += "Security patch age is measured from the SPL reported in the build properties."
        } else {
            notes += "The security patch level was not readable from this build."
        }

        notes += "Manufacturing date is not exposed by Android. No serial number was decoded, because no " +
            "publicly documented decoder exists for current formats."

        val confidence = when {
            launchMillis != null && buildMillis != null && cycles != null -> Confidence.MEDIUM
            launchMillis != null && buildMillis != null -> Confidence.LOW
            launchMillis != null || buildMillis != null -> Confidence.LOW
            else -> Confidence.NONE
        }

        return AgeReport(
            modelLaunchDate = reference?.device?.releaseDate,
            modelLaunchAgeDays = launchAge,
            firmwareBuildDate = buildMillis?.let { Formatters.utcDate(it) },
            firmwareBuildAgeDays = buildAge,
            ownershipAgeDays = ownershipAge,
            purchaseDateUsed = purchaseDateMillis?.let { Formatters.dateOnly(it) },
            estimatedUsageAgeDays = estimatedUsage,
            securityPatchDate = snapshot.software.securityPatch,
            daysSinceSecurityPatch = patchAge,
            notes = notes,
            confidence = confidence
        )
    }

    private fun parseSecurityPatch(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(value.substring(0, 4).toInt(), value.substring(5, 7).toInt() - 1, 1)
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
}
