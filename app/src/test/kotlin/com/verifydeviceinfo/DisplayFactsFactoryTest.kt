package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.collect.DisplayFactsFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Guards the display arithmetic that the collector used to inline, and the fallback that keeps a
 * multi-display device from losing its whole display section.
 */
class DisplayFactsFactoryTest {

    @Test
    fun diagonalUsesPhysicalDpi() {
        // 1080 / 400 = 2.7 in, 2340 / 400 = 5.85 in -> sqrt(2.7^2 + 5.85^2)
        val expected = sqrt(2.7f * 2.7f + 5.85f * 5.85f)
        val actual = DisplayFactsFactory.diagonalInches(1080, 2340, 400f, 400f)
        assertEquals(expected, actual, 0.001f)
    }

    @Test
    fun diagonalIsZeroWhenPhysicalDpiIsMissing() {
        assertEquals(0f, DisplayFactsFactory.diagonalInches(1080, 2340, 0f, 0f), 0f)
        assertEquals(0f, DisplayFactsFactory.diagonalInches(1080, 2340, 400f, 0f), 0f)
        assertEquals(0f, DisplayFactsFactory.diagonalInches(1080, 2340, 0f, 400f), 0f)
    }

    @Test
    fun diagonalIsZeroForAnEmptyDisplay() {
        assertEquals(0f, DisplayFactsFactory.diagonalInches(0, 0, 400f, 400f), 0f)
        assertEquals(0f, DisplayFactsFactory.diagonalInches(1080, 0, 400f, 400f), 0f)
    }

    @Test
    fun diagonalSurvivesAnInfiniteDpi() {
        val actual = DisplayFactsFactory.diagonalInches(1080, 2340, Float.POSITIVE_INFINITY, 400f)
        assertTrue("diagonal must stay finite, was $actual", actual.isFinite())
    }

    @Test
    fun buildKeepsTheReportedMetrics() {
        val facts = DisplayFactsFactory.build(
            widthPx = 1080,
            heightPx = 2340,
            densityDpi = 420,
            xdpi = 400f,
            ydpi = 400f,
            refreshRateHz = 120f,
            supportedRefreshRates = listOf(60f, 120f, 90f, 120f),
            hdrTypes = listOf("HDR10", "HDR10"),
            wideColorGamut = true
        )
        assertEquals(1080, facts.widthPx)
        assertEquals(2340, facts.heightPx)
        assertEquals(420, facts.densityDpi)
        assertEquals(120f, facts.refreshRateHz, 0f)
        assertEquals(listOf(60f, 90f, 120f), facts.supportedRefreshRates)
        assertEquals(listOf("HDR10"), facts.hdrTypes)
        assertTrue(facts.wideColorGamut)
        assertTrue(facts.diagonalInches > 0f)
    }

    @Test
    fun buildSubstitutesTheFallbackRefreshRate() {
        assertEquals(
            DisplayFactsFactory.FALLBACK_REFRESH_HZ,
            DisplayFactsFactory.build(1080, 2340, 420, 400f, 400f, 0f, listOf(0f), emptyList(), false).refreshRateHz,
            0f
        )
        assertEquals(
            DisplayFactsFactory.FALLBACK_REFRESH_HZ,
            DisplayFactsFactory.build(
                1080, 2340, 420, 400f, 400f, Float.NaN, emptyList(), emptyList(), false
            ).refreshRateHz,
            0f
        )
    }

    @Test
    fun resourceMetricsProduceAUsableDisplaySection() {
        // The fallback path is what a multi-display device lands on; assert the shape it yields is
        // the same one the real-metrics path yields, so the report rows stay populated.
        val facts = DisplayFactsFactory.build(
            widthPx = 1080,
            heightPx = 2340,
            densityDpi = 420,
            xdpi = 400f,
            ydpi = 400f,
            refreshRateHz = DisplayFactsFactory.FALLBACK_REFRESH_HZ,
            supportedRefreshRates = listOf(DisplayFactsFactory.FALLBACK_REFRESH_HZ),
            hdrTypes = emptyList(),
            wideColorGamut = false
        )
        assertNotNull(facts)
        assertTrue(facts.widthPx > 0 && facts.heightPx > 0)
        assertEquals(listOf(60f), facts.supportedRefreshRates)
    }
}
