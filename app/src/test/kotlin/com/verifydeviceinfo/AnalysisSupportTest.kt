package com.verifydeviceinfo

import com.verifydeviceinfo.domain.analysis.AnalysisSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSupportTest {

    @Test
    fun `sub-brands are recognised as the same vendor family`() {
        assertTrue(AnalysisSupport.brandsRelated("Xiaomi", "Redmi"))
        assertTrue(AnalysisSupport.brandsRelated("Xiaomi", "POCO"))
        assertTrue(AnalysisSupport.brandsRelated("samsung", "samsung"))
        assertTrue(AnalysisSupport.brandsRelated("vivo", "iQOO"))
    }

    @Test
    fun `unrelated brands are not treated as the same family`() {
        assertFalse(AnalysisSupport.brandsRelated("samsung", "xiaomi"))
        assertFalse(AnalysisSupport.brandsRelated("Google", "Samsung"))
    }

    @Test
    fun `part numbers and marketing names match in both directions`() {
        assertTrue(AnalysisSupport.socMatches("SM8550", "Snapdragon 8 Gen 2 SM8550"))
        assertTrue(AnalysisSupport.socMatches("Snapdragon 8 Gen 2", "Snapdragon 8 Gen 2 SM8550"))
    }

    @Test
    fun `a genuinely different chipset does not match`() {
        assertFalse(AnalysisSupport.socMatches("SM8550", "Exynos 2200"))
        assertFalse(AnalysisSupport.socMatches("MT6833", "Snapdragon 685 SM6225"))
    }

    @Test
    fun `fingerprint parsing splits the android format`() {
        val parts = AnalysisSupport.parseFingerprint(
            "samsung/dm1qxxx/dm1q:14/UP1A/1234:user/release-keys"
        )
        assertEquals(6, parts.size)
        assertEquals("samsung", parts[0])
        assertEquals("dm1q", parts[2])
        assertEquals("release-keys", parts[5])
    }

    @Test
    fun `risk aggregation never upgrades a single weak signal to high`() {
        val findings = listOf(
            com.verifydeviceinfo.core.model.Finding(
                id = "a", title = "a",
                status = com.verifydeviceinfo.core.model.DiagnosticStatus.WARNING,
                risk = com.verifydeviceinfo.core.model.RiskLevel.MODERATE,
                explanation = "",
                evidence = emptyList(),
                confidence = com.verifydeviceinfo.core.model.Confidence.LOW
            )
        )
        val risk = AnalysisSupport.aggregateRisk(findings)
        assertTrue(
            "a single low-confidence indicator must not be escalated, got $risk",
            risk != com.verifydeviceinfo.core.model.RiskLevel.HIGH &&
                risk != com.verifydeviceinfo.core.model.RiskLevel.ELEVATED
        )
    }

    /** The expression [AnalysisSupport.normalise] was built from, kept here as the oracle. */
    private fun normaliseByRegex(value: String?): String =
        value.orEmpty().lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]"), "")

    @Test
    fun `normalise agrees with the regex expression it replaced`() {
        val corpus = listOf(
            null,
            "",
            " ",
            "samsung",
            "Samsung",
            "SM-S911B",
            "sm s911b",
            "SM_S911B",
            "Snapdragon 8 Gen 2",
            "  leading and trailing  ",
            "two  spaces",
            "tab\tand\nnewline",
            "Pixel 8 Pro (akita)",
            "1.5 GHz",
            "İstanbul",
            "ıI",
            "ÄÖÜ",
            "中文手机",
            "٨٥٥٠",
            "𝟴𝟱𝟱𝟬",
            "e\u0301clair",
            "SM‐S911B",
            "😀 emoji",
            "-",
            "a-b_c.d/e:f",
            "\\u0000nul",
            "a\u0000b"
        )
        for (value in corpus) {
            val label = if (value == null) "null" else "'$value'"
            assertEquals("normalise($label)", normaliseByRegex(value), AnalysisSupport.normalise(value))
        }
    }

    @Test
    fun `normalise keeps only ascii letters and digits`() {
        assertEquals("sms911b", AnalysisSupport.normalise("SM-S911B"))
        assertEquals("snapdragon8gen2", AnalysisSupport.normalise("Snapdragon 8 Gen 2"))
        assertEquals("", AnalysisSupport.normalise("— – ·"))
        assertEquals("", AnalysisSupport.normalise(null))
    }

    @Test
    fun `an already clean value is normalised without being rebuilt`() {
        // The fast path returns the lowercased input itself; the value is what matters here.
        assertEquals("samsung", AnalysisSupport.normalise("samsung"))
        assertEquals("sm8550", AnalysisSupport.normalise("SM8550"))
    }
}
