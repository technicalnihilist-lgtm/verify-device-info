package com.verifydeviceinfo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.data.android.ReferenceAssetParser
import com.verifydeviceinfo.domain.analysis.AnalysisSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A reference release date is either a real calendar date or it is not a date at all.
 *
 * `Calendar` is lenient by default: `2023-13-01` became 1 January 2024 and `2023-02-30` became
 * 2 March, so a mistyped curation date in the bundled set produced a plausible, wrong launch date
 * — and with it a wrong "Model age" and a wrong age input to the score. The parse is now strict, so
 * an impossible date is reported as missing (the same as an absent one) instead of as another date.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReferenceDateTest {

    /** 14 March 2023, 00:00 UTC. */
    private val march14 = 1_678_752_000_000L

    @Test
    fun `a full date is UTC midnight on that day`() {
        assertEquals(march14, AnalysisSupport.releaseDateToMillis("2023-03-14"))
    }

    @Test
    fun `coarse dates keep their old meaning`() {
        // "2023-03" means the first of March, "2023" the first of January — unchanged.
        assertEquals(1_677_628_800_000L, AnalysisSupport.releaseDateToMillis("2023-03"))
        assertEquals(1_672_531_200_000L, AnalysisSupport.releaseDateToMillis("2023"))
    }

    @Test
    fun `a month past December is refused instead of rolling into the next year`() {
        // Lenient parsing returned 1 January 2024 for this.
        assertNull(AnalysisSupport.releaseDateToMillis("2023-13-01"))
        assertNull(AnalysisSupport.releaseDateToMillis("2023-00-10"))
    }

    @Test
    fun `a day the month does not have is refused instead of rolling into the next month`() {
        // Lenient parsing returned 2 March 2023 for the first and 1 May 2023 for the second.
        assertNull(AnalysisSupport.releaseDateToMillis("2023-02-30"))
        assertNull(AnalysisSupport.releaseDateToMillis("2023-04-31"))
        assertNull(AnalysisSupport.releaseDateToMillis("2023-01-32"))
    }

    @Test
    fun `a leap day is a real date in a leap year and not in a common one`() {
        assertEquals(1_709_164_800_000L, AnalysisSupport.releaseDateToMillis("2024-02-29"))
        assertNull(AnalysisSupport.releaseDateToMillis("2023-02-29"))
    }

    @Test
    fun `absent and unparseable values are still missing`() {
        assertNull(AnalysisSupport.releaseDateToMillis(null))
        assertNull(AnalysisSupport.releaseDateToMillis(""))
        assertNull(AnalysisSupport.releaseDateToMillis("   "))
        assertNull(AnalysisSupport.releaseDateToMillis("unknown"))
        assertNull(AnalysisSupport.releaseDateToMillis("2023-03-14T09:30:00Z"))
    }

    /**
     * The guard for the change being invisible on the shipped data: every release date in the
     * bundled set parses, and parses to a date in the past. A future edit that mistypes one would
     * fail here rather than silently drop the model's launch date on a device.
     */
    @Test
    fun `every bundled release date is a real date`() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        val json = assets.open("device_reference.json").bufferedReader().use { it.readText() }
        val devices = ReferenceAssetParser.parse(json)
        assertTrue("the bundled reference set is empty", devices.isNotEmpty())
        devices.forEach { device ->
            val millis = AnalysisSupport.releaseDateToMillis(device.releaseDate)
            assertTrue(
                "${device.brand} ${device.marketingName} has releaseDate '${device.releaseDate}', " +
                    "which is not a real calendar date",
                millis != null
            )
            assertTrue(
                "${device.brand} ${device.marketingName} has a release date in the future",
                millis!! < System.currentTimeMillis()
            )
        }
    }
}
