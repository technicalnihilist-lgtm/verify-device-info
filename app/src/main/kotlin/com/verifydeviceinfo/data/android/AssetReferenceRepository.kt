package com.verifydeviceinfo.data.android

import android.content.Context
import com.verifydeviceinfo.core.model.ReferenceDevice
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.domain.ReferenceRepository

/**
 * Offline reference data, bundled with the APK as an asset. No network call is made, so the
 * app works with no connectivity and sends nothing anywhere.
 *
 * Coverage is deliberately limited to models that were curated from manufacturer
 * specification pages. Anything outside the set is reported as UNVERIFIED, not as suspicious.
 */
class AssetReferenceRepository(private val context: Context) : ReferenceRepository {

    private var devices: List<ReferenceDevice>? = null

    /**
     * The normalised, pre-computed view of [devices] that matching runs against. Built once,
     * beside the parsed entries, so a scan only normalises the three strings it is asked about.
     */
    private var index: ReferenceIndex? = null

    override fun all(): List<ReferenceDevice> = load()

    override fun match(model: String, brand: String, device: String): ReferenceMatch? {
        load()
        return index?.match(model, brand, device)
    }

    override fun sourceSummary(): String =
        "Bundled reference set: ${load().size} models curated from manufacturer specification pages. " +
            "Entries record their own source URL and curation date."

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
        index = ReferenceIndex(parsed)
        devices = parsed
        return parsed
    }
}
