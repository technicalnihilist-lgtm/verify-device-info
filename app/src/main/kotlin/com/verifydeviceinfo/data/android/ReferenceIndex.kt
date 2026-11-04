package com.verifydeviceinfo.data.android

import com.verifydeviceinfo.core.model.ReferenceDevice
import com.verifydeviceinfo.core.model.ReferenceMatch
import java.util.Locale

/**
 * The normalised view of the reference set that matching runs against.
 *
 * The index is built once per set, so a scan only normalises the three strings it is asked
 * about, and the matching rules can be exercised without a Context or an asset file.
 */
internal class ReferenceIndex(devices: List<ReferenceDevice>) {

    /**
     * A curated pattern, kept as the original string (that is what a [ReferenceMatch] reports)
     * alongside its normalised key, so the normalisation happens once when the asset is loaded
     * rather than on every scan.
     */
    private class PatternKey(val raw: String, val key: String)

    /** A reference entry with its brand and pattern keys pre-normalised. */
    private class Entry(
        val device: ReferenceDevice,
        val brandKey: String,
        val patterns: List<PatternKey>
    )

    private val entries: List<Entry> = devices.map { device ->
        Entry(
            device = device,
            brandKey = normalise(device.brand),
            patterns = device.modelPatterns.map { PatternKey(it, normalise(it)) }
        )
    }

    fun match(model: String, brand: String, device: String): ReferenceMatch? {
        val normalisedModel = normalise(model)
        val normalisedDevice = normalise(device)
        val normalisedBrand = normalise(brand)
        // Prefer a brand match, then the longest pattern that occurs in the model string.
        // Entries are visited in file order, so an exact tie keeps the earlier entry — the same
        // winner a stable descending sort produced.
        var bestDevice: ReferenceDevice? = null
        var bestPattern: String? = null
        var bestBrandMatches = false
        var bestLength = -1
        for (entry in entries) {
            val pattern = entry.patterns.firstOrNull { candidate ->
                candidate.key.isNotEmpty() &&
                    (normalisedModel.contains(candidate.key) || normalisedDevice == candidate.key)
            } ?: continue
            val brandMatches = entry.brandKey == normalisedBrand
            val better = when {
                bestDevice == null -> true
                brandMatches != bestBrandMatches -> brandMatches
                else -> pattern.raw.length > bestLength
            }
            if (better) {
                bestDevice = entry.device
                bestPattern = pattern.raw
                bestBrandMatches = brandMatches
                bestLength = pattern.raw.length
            }
        }
        val matched = bestDevice ?: return null
        return ReferenceMatch(matched, bestPattern ?: return null)
    }

    companion object {
        /** Uppercase alphanumerics only, so `SM-S911B`, `sm s911b` and `SM_S911B` agree. */
        fun normalise(value: String): String {
            val upper = value.uppercase(Locale.US)
            val out = StringBuilder(upper.length)
            for (ch in upper) {
                if ((ch in 'A'..'Z') || (ch in '0'..'9')) out.append(ch)
            }
            return out.toString()
        }
    }
}
