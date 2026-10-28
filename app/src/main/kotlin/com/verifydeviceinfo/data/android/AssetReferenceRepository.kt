package com.verifydeviceinfo.data.android

import android.content.Context
import com.verifydeviceinfo.core.model.ReferenceDevice
import com.verifydeviceinfo.core.model.ReferenceMatch
import com.verifydeviceinfo.domain.ReferenceRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Offline reference data, bundled with the APK as an asset. No network call is made, so the
 * app works with no connectivity and sends nothing anywhere.
 *
 * Coverage is deliberately limited to models that were curated from manufacturer
 * specification pages. Anything outside the set is reported as UNVERIFIED, not as suspicious.
 */
class AssetReferenceRepository(private val context: Context) : ReferenceRepository {

    private var cache: List<ReferenceDevice>? = null

    override fun all(): List<ReferenceDevice> = load()

    override fun match(model: String, brand: String, device: String): ReferenceMatch? {
        val normalisedModel = normalise(model)
        val normalisedDevice = normalise(device)
        val normalisedBrand = normalise(brand)
        val candidates = load()
        // Prefer a brand match, then the longest pattern that occurs in the model string.
        val matches = candidates.mapNotNull { deviceEntry ->
            val pattern = deviceEntry.modelPatterns.firstOrNull { raw ->
                val p = normalise(raw)
                p.isNotEmpty() && (normalisedModel.contains(p) || normalisedDevice == p)
            }
            if (pattern == null) null
            else {
                val brandMatches = normalise(deviceEntry.brand) == normalisedBrand
                Triple(deviceEntry, pattern, brandMatches)
            }
        }
        val best = matches.sortedWith(
            compareByDescending<Triple<ReferenceDevice, String, Boolean>> { it.third }
                .thenByDescending { it.second.length }
        ).firstOrNull() ?: return null
        return ReferenceMatch(best.first, best.second)
    }

    override fun sourceSummary(): String =
        "Bundled reference set: ${load().size} models curated from manufacturer specification pages. " +
            "Entries record their own source URL and curation date."

    private fun normalise(value: String): String =
        value.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")

    @Synchronized
    private fun load(): List<ReferenceDevice> {
        cache?.let { return it }
        val parsed = mutableListOf<ReferenceDevice>()
        try {
            val json = context.assets.open("device_reference.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val devices = root.optJSONArray("devices") ?: JSONArray()
            for (index in 0 until devices.length()) {
                val obj = devices.getJSONObject(index)
                parsed += ReferenceDevice(
                    modelPatterns = obj.optJSONArray("modelPatterns").toStringList(),
                    brand = obj.optString("brand"),
                    marketingName = obj.optString("marketingName"),
                    soc = obj.optString("soc"),
                    socManufacturer = obj.optString("socManufacturer"),
                    releaseDate = obj.optString("releaseDate"),
                    ramOptionsGb = obj.optJSONArray("ramOptionsGb").toIntList(),
                    storageOptionsGb = obj.optJSONArray("storageOptionsGb").toIntList(),
                    displayWidthPx = obj.optInt("displayWidthPx"),
                    displayHeightPx = obj.optInt("displayHeightPx"),
                    densityDpi = obj.optInt("densityDpi"),
                    refreshRateHz = obj.optDouble("refreshRateHz", 60.0).toFloat(),
                    rearCameraCount = obj.optInt("rearCameraCount"),
                    frontCameraCount = obj.optInt("frontCameraCount"),
                    mainCameraMegapixels = obj.optDouble("mainCameraMegapixels", 0.0),
                    batteryMah = obj.optInt("batteryMah"),
                    expectedSensors = obj.optJSONArray("expectedSensors").toStringList(),
                    sourceUrl = obj.optString("sourceUrl"),
                    sourceDate = obj.optString("sourceDate"),
                    sourceName = obj.optString("sourceName")
                )
            }
        } catch (e: Exception) {
            // A missing or malformed asset degrades to "no reference data", never to a crash.
        }
        cache = parsed
        return parsed
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until length()) list += optString(i)
        return list
    }

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        val list = mutableListOf<Int>()
        for (i in 0 until length()) list += optInt(i)
        return list
    }
}
