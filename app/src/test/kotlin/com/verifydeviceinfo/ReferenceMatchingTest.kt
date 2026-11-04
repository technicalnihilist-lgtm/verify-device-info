package com.verifydeviceinfo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.ReferenceDevice
import com.verifydeviceinfo.data.android.AssetReferenceRepository
import com.verifydeviceinfo.data.android.ReferenceIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The matching rules behind "is this device the model it claims to be". A wrong winner here is
 * not a crash but a wrong verdict, so every rule the scan depends on is pinned: brand equality
 * first, then the longest pattern, then file order for an exact tie.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReferenceMatchingTest {

    private fun device(brand: String, name: String, vararg patterns: String) = ReferenceDevice(
        modelPatterns = patterns.toList(),
        brand = brand,
        marketingName = name,
        soc = "",
        socManufacturer = "",
        releaseDate = "",
        ramOptionsGb = emptyList(),
        storageOptionsGb = emptyList(),
        displayWidthPx = 0,
        displayHeightPx = 0,
        densityDpi = 0,
        refreshRateHz = 0f,
        rearCameraCount = 0,
        frontCameraCount = 0,
        mainCameraMegapixels = 0.0,
        batteryMah = 0,
        expectedSensors = emptyList(),
        sourceUrl = "",
        sourceDate = "",
        sourceName = ""
    )

    @Test
    fun `a model string containing two patterns reports the first listed pattern of its entry`() {
        val index = ReferenceIndex(
            listOf(device("Samsung", "Galaxy S23", "SM-S911", "Galaxy S23"))
        )

        val match = index.match("SM-S911 Galaxy S23", "Samsung", "")

        assertEquals("Galaxy S23", match?.device?.marketingName)
        assertEquals("SM-S911", match?.matchedPattern)
    }

    @Test
    fun `a model string containing the patterns of two entries prefers the longer pattern`() {
        val index = ReferenceIndex(
            listOf(
                device("Samsung", "Galaxy S23", "Galaxy S23"),
                device("Samsung", "Galaxy S23 Ultra", "Galaxy S23 Ultra")
            )
        )

        val match = index.match("Samsung Galaxy S23 Ultra", "Samsung", "")

        assertEquals("Galaxy S23 Ultra", match?.device?.marketingName)
        assertEquals("Galaxy S23 Ultra", match?.matchedPattern)
    }

    @Test
    fun `a brand match beats a longer pattern from another brand`() {
        val index = ReferenceIndex(
            listOf(
                device("Samsung", "Galaxy S23", "SM-S911"),
                device("Google", "Pixel 7", "SM")
            )
        )

        // Same model string, same index: only the reported brand differs.
        val google = index.match("SM-S911", "Google", "")
        assertEquals("Pixel 7", google?.device?.marketingName)
        assertEquals("SM", google?.matchedPattern)

        val samsung = index.match("SM-S911", "Samsung", "")
        assertEquals("Galaxy S23", samsung?.device?.marketingName)
    }

    @Test
    fun `an exact tie keeps the entry that comes first in the set`() {
        val index = ReferenceIndex(
            listOf(
                device("Samsung", "First", "AB-12"),
                device("Samsung", "Second", "AB 12")
            )
        )

        val match = index.match("AB-12", "Samsung", "")

        assertEquals("First", match?.device?.marketingName)
        assertEquals("AB-12", match?.matchedPattern)
    }

    @Test
    fun `case, spaces and punctuation are ignored on both sides`() {
        val index = ReferenceIndex(listOf(device("Samsung", "Galaxy S23", "Galaxy S23")))

        val match = index.match("samsung  galaxy-s23 5g", "samsung", "")

        assertEquals("Galaxy S23", match?.device?.marketingName)
        assertEquals("Galaxy S23", match?.matchedPattern)
    }

    @Test
    fun `the device string must equal a pattern, not merely contain it`() {
        val index = ReferenceIndex(listOf(device("Samsung", "Galaxy S23", "SM-S911")))

        assertEquals("Galaxy S23", index.match("", "Samsung", "sm-s911")?.device?.marketingName)
        assertNull(index.match("", "Samsung", "SM-S911B"))
    }

    @Test
    fun `a model outside the reference set does not match`() {
        val index = ReferenceIndex(listOf(device("Samsung", "Galaxy S23", "SM-S911")))

        assertNull(index.match("Nokia 3310", "Nokia", ""))
        assertNull(index.match("", "", ""))
    }

    @Test
    fun `an entry with no patterns, or with a blank one, never matches`() {
        val index = ReferenceIndex(
            listOf(
                device("Samsung", "No patterns"),
                device("Samsung", "Blank pattern", ""),
                device("Samsung", "Galaxy S23", "SM-S911")
            )
        )

        assertEquals("Galaxy S23", index.match("SM-S911", "Samsung", "")?.device?.marketingName)
        assertNull(ReferenceIndex(listOf(device("Samsung", "Blank pattern", ""))).match("", "", ""))
    }

    @Test
    fun `the bundled asset is matched through the repository`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AssetReferenceRepository(context)

        val match = repository.match("SM-S911B", "Samsung", "")
        assertEquals("Galaxy S23", match?.device?.marketingName)
        assertEquals("SM-S911", match?.matchedPattern)

        assertNull(repository.match("Nokia 3310", "Nokia", ""))
        assertEquals(16, repository.all().size)
    }
}
