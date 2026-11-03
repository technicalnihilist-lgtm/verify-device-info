package com.verifydeviceinfo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.data.android.ReferenceAssetParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The reference loader must survive a damaged entry: the set is hand-curated and shipped inside
 * the APK, and one broken element used to throw out of the parse loop and silently truncate the
 * reference data to whatever had been read before it.
 *
 * Robolectric is used for its real `org.json` implementation, the same one the app runs against
 * on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReferenceAssetParserTest {

    private fun entry(brand: String, pattern: String, refreshRateHz: String = "120"): String = """
        {
          "modelPatterns": ["$pattern"],
          "brand": "$brand",
          "marketingName": "$pattern",
          "soc": "Test SoC",
          "socManufacturer": "Test",
          "releaseDate": "2024-01-01",
          "ramOptionsGb": [8],
          "storageOptionsGb": [128, 256],
          "displayWidthPx": 1080,
          "displayHeightPx": 2400,
          "densityDpi": 420,
          "refreshRateHz": $refreshRateHz,
          "rearCameraCount": 3,
          "frontCameraCount": 1,
          "mainCameraMegapixels": 50.0,
          "batteryMah": 5000,
          "expectedSensors": ["accelerometer"],
          "sourceUrl": "https://example.invalid/specs",
          "sourceDate": "2026-10-28",
          "sourceName": "Test source"
        }
    """.trimIndent()

    private fun document(vararg elements: String): String =
        """{"schemaVersion": 1, "devices": [${elements.joinToString(",")}]}"""

    @Test
    fun `a non-object element is skipped and the entries around it still load`() {
        val parsed = ReferenceAssetParser.parse(
            document(entry("Alpha", "A1"), "\"not an entry\"", entry("Beta", "B1"), entry("Gamma", "G1"))
        )

        assertEquals(listOf("Alpha", "Beta", "Gamma"), parsed.map { it.brand })
    }

    @Test
    fun `a null element and a nested array are skipped, not fatal`() {
        val parsed = ReferenceAssetParser.parse(
            document(entry("Alpha", "A1"), "null", "[1, 2, 3]", "42", entry("Beta", "B1"))
        )

        assertEquals(listOf("Alpha", "Beta"), parsed.map { it.brand })
    }

    @Test
    fun `an entry whose field has the wrong type is still read, with the field defaulted`() {
        // "modelPatterns" as a string rather than an array: optJSONArray yields null, so the
        // entry keeps an empty pattern list instead of throwing away the entry.
        val odd = """{"brand": "Odd", "modelPatterns": "O1"}"""
        val parsed = ReferenceAssetParser.parse(document(entry("Alpha", "A1"), odd))

        assertEquals(listOf("Alpha", "Odd"), parsed.map { it.brand })
        assertEquals(emptyList<String>(), parsed[1].modelPatterns)
    }

    @Test
    fun `the entry after a bad element keeps every field`() {
        val parsed = ReferenceAssetParser.parse(document("true", entry("Beta", "B1")))

        assertEquals(1, parsed.size)
        val device = parsed[0]
        assertEquals("B1", device.marketingName)
        assertEquals("Test SoC", device.soc)
        assertEquals(listOf(128, 256), device.storageOptionsGb)
        assertEquals(1080, device.displayWidthPx)
        assertEquals(5000, device.batteryMah)
        assertEquals(50.0, device.mainCameraMegapixels, 0.0)
        assertEquals("https://example.invalid/specs", device.sourceUrl)
    }

    @Test
    fun `absent numeric fields keep their defaults`() {
        val sparse = """{"brand": "Sparse", "modelPatterns": ["S1"]}"""
        val parsed = ReferenceAssetParser.parse(document(sparse))

        assertEquals(1, parsed.size)
        val device = parsed[0]
        assertEquals(60.0f, device.refreshRateHz, 0.0f)
        assertEquals(0, device.displayWidthPx)
        assertEquals(0, device.batteryMah)
        assertEquals(0.0, device.mainCameraMegapixels, 0.0)
        assertEquals(emptyList<Int>(), device.ramOptionsGb)
        assertEquals(emptyList<String>(), device.expectedSensors)
        assertEquals("", device.sourceUrl)
    }

    @Test
    fun `a document that is not JSON yields an empty list`() {
        assertTrue(ReferenceAssetParser.parse("not json at all").isEmpty())
        assertTrue(ReferenceAssetParser.parse("").isEmpty())
        assertTrue(ReferenceAssetParser.parse("""{"schemaVersion": 1}""").isEmpty())
        assertTrue(ReferenceAssetParser.parse("""{"devices": {}}""").isEmpty())
    }

    @Test
    fun `the bundled asset parses every entry`() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        val json = assets.open("device_reference.json").bufferedReader().use { it.readText() }

        val parsed = ReferenceAssetParser.parse(json)

        assertEquals(16, parsed.size)
        val first = parsed.first()
        assertEquals("Samsung", first.brand)
        assertEquals("Galaxy S23", first.marketingName)
        assertEquals(listOf("SM-S911", "Galaxy S23"), first.modelPatterns)
        assertEquals(3900, first.batteryMah)
        assertEquals(120.0f, first.refreshRateHz, 0.0f)
        assertTrue(parsed.all { it.brand.isNotEmpty() && it.modelPatterns.isNotEmpty() })
    }
}
