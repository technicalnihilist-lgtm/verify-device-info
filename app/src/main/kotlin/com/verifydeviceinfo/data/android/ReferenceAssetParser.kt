package com.verifydeviceinfo.data.android

import com.verifydeviceinfo.core.model.ReferenceDevice
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the bundled reference asset into [ReferenceDevice] values.
 *
 * The set is curated by hand and shipped inside the APK, so a single damaged entry is a real
 * possibility after an edit. Parsing is therefore per entry: an element of `devices` that is not
 * an object, or that throws while it is being read, is skipped and the rest of the set still
 * loads. Before this, one bad element threw out of the loop and every entry after it was lost —
 * the app silently fell back to a truncated reference set.
 *
 * A document that is not JSON, or that has no `devices` array, yields an empty list: the same
 * "no reference data" degradation the screen already renders.
 *
 * Field extraction is unchanged from the original loader, so a well-formed entry produces exactly
 * the same [ReferenceDevice] as before, including the `optInt`/`optDouble` defaults for a field
 * that is absent.
 */
internal object ReferenceAssetParser {

    fun parse(json: String): List<ReferenceDevice> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val devicesJson = root.optJSONArray("devices") ?: return emptyList()
        val parsed = ArrayList<ReferenceDevice>(devicesJson.length())
        for (index in 0 until devicesJson.length()) {
            val device = try {
                parseEntry(devicesJson, index)
            } catch (e: Exception) {
                // One malformed entry costs that entry only.
                null
            }
            if (device != null) parsed += device
        }
        return parsed
    }

    /** Returns null when the element at [index] is not a JSON object. */
    private fun parseEntry(devices: JSONArray, index: Int): ReferenceDevice? {
        val obj = devices.optJSONObject(index) ?: return null
        return ReferenceDevice(
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
