package com.verifydeviceinfo.data.android

import android.content.Context
import com.verifydeviceinfo.core.model.ReferenceDevice
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.domain.ReferenceRepository
import java.util.Locale

/**
 * Offline reference data, bundled with the APK as an asset. No network call is made, so the
 * app works with no connectivity and sends nothing anywhere.
 *
 * Coverage is deliberately limited to models that were curated from manufacturer
 * specification pages. Anything outside the set is reported as UNVERIFIED, not as suspicious.
 */
class AssetReferenceRepository(private val context: Context) : ReferenceRepository {

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

    private var devices: List<ReferenceDevice>? = null
    private var entries: List<Entry> = emptyList()

    override fun all(): List<ReferenceDevice> = load()

    override fun match(model: String, brand: String, device: String): ReferenceMatch? {
        load()
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

    override fun sourceSummary(): String =
        "Bundled reference set: ${load().size} models curated from manufacturer specification pages. " +
            "Entries record their own source URL and curation date."

    private fun normalise(value: String): String {
        val upper = value.uppercase(Locale.US)
        val out = StringBuilder(upper.length)
        for (ch in upper) {
            if ((ch in 'A'..'Z') || (ch in '0'..'9')) out.append(ch)
        }
        return out.toString()
    }

    @Synchronized
    private fun load(): List<ReferenceDevice> {
        devices?.let { return it }
        val parsed = try {
            val json = context.assets.open("device_reference.json").bufferedReader().use { it.readText() }
            // Per-entry parsing: one damaged entry is skipped, the rest of the set still loads.
            ReferenceAssetParser.parse(json)
        } catch (e: Exception) {
            // A missing asset degrades to "no reference data", never to a crash.
            emptyList()
        }
        // Build the normalised index once, next to the parsed entries, so a scan only normalises
        // the three strings it is asked about.
        entries = parsed.map { device ->
            Entry(
                device = device,
                brandKey = normalise(device.brand),
                patterns = device.modelPatterns.map { PatternKey(it, normalise(it)) }
            )
        }
        devices = parsed
        return parsed
    }
}
