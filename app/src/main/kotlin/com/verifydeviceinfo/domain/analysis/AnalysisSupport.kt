package com.verifydeviceinfo.domain.analysis

import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.Evidence
import com.verifydeviceinfo.core.model.EvidenceSource
import com.verifydeviceinfo.core.model.Finding
import com.verifydeviceinfo.core.model.ReferenceDevice
import com.verifydeviceinfo.core.model.RiskLevel
import java.util.Locale

internal object AnalysisSupport {

    /**
     * Compiled once for the life of the process. `Regex` compiles its pattern in the constructor,
     * and [normalise] runs in tight loops over model, brand and SoC strings during a scan, so
     * rebuilding the pattern per call dominated the cost of normalising a short string.
     */
    private val nonAlphanumeric = Regex("[^a-z0-9]")

    /** Same reasoning as [nonAlphanumeric], for the token splitting in [socMatches]. */
    private val nonAlphanumericRuns = Regex("[^a-z0-9]+")

    fun normalise(value: String?): String =
        value.orEmpty().lowercase(Locale.US).replace(nonAlphanumeric, "")

    /** Brand families where the marketing brand legitimately differs from the manufacturer. */
    val brandAliases: Map<String, Set<String>> = mapOf(
        "xiaomi" to setOf("xiaomi", "redmi", "poco"),
        "bbk" to setOf("vivo", "iqoo", "oppo", "realme", "oneplus"),
        "vivo" to setOf("vivo", "iqoo"),
        "oppo" to setOf("oppo", "realme", "oneplus"),
        "samsung" to setOf("samsung"),
        "google" to setOf("google"),
        "motorola" to setOf("motorola", "moto"),
        "nothing" to setOf("nothing"),
        "huawei" to setOf("huawei", "honor"),
        "honor" to setOf("honor", "huawei"),
        "tecno" to setOf("tecno", "infinix", "itel"),
        "transsion" to setOf("tecno", "infinix", "itel")
    )

    fun brandsRelated(manufacturer: String, brand: String): Boolean {
        val m = normalise(manufacturer)
        val b = normalise(brand)
        if (m.isEmpty() || b.isEmpty()) return false
        if (m == b) return true
        val family = brandAliases[m] ?: return false
        return family.contains(b)
    }

    /**
     * Splits an Android build fingerprint. The third segment carries the platform release after a
     * colon (device:release), so it is trimmed to the device name alone.
     */
    fun parseFingerprint(fingerprint: String): List<String> {
        val parts = fingerprint.split("/").map { it.trim() }
        return parts.mapIndexed { index, value ->
            if (index == 2) value.substringBefore(":") else value
        }
    }

    fun finding(
        id: String,
        title: String,
        status: DiagnosticStatus,
        risk: RiskLevel,
        explanation: String,
        confidence: Confidence,
        evidence: List<Evidence>
    ) = Finding(id, title, status, risk, explanation, evidence, confidence)

    fun evidence(description: String, source: EvidenceSource, detail: String? = null) =
        Evidence(description, source, detail)

    /** Risk aggregation: the worst indicator wins, but never silently upgrades a single weak signal. */
    fun aggregateRisk(findings: List<Finding>): RiskLevel {
        if (findings.isEmpty()) return RiskLevel.UNKNOWN
        if (findings.any { it.risk == RiskLevel.HIGH }) return RiskLevel.HIGH
        val warnings = findings.filter { it.risk == RiskLevel.ELEVATED || it.risk == RiskLevel.MODERATE }
        val strongWarnings = warnings.count { it.confidence == Confidence.HIGH || it.confidence == Confidence.MEDIUM }
        return when {
            strongWarnings >= 3 -> RiskLevel.HIGH
            strongWarnings == 2 -> RiskLevel.ELEVATED
            strongWarnings == 1 -> RiskLevel.MODERATE
            findings.any { it.status == DiagnosticStatus.UNVERIFIED } -> RiskLevel.UNKNOWN
            else -> RiskLevel.LOW
        }
    }

    /**
     * A reference release date — `2023-03-14`, or the coarser `2023-03` and `2023` — as UTC
     * midnight, or `null` when it is not a real calendar date.
     *
     * The parse is strict on purpose. `Calendar` is lenient by default, so `2023-13-01` silently
     * became 1 January 2024 and `2023-02-30` became 2 March: a mistyped curation date produced a
     * plausible, wrong launch date, a wrong "Model age" and a wrong age input to the score, with
     * nothing on screen to say the date had been repaired. A date that is not a date is not
     * evidence, so it is reported as missing rather than as a different date.
     *
     * Every `releaseDate` in the bundled reference set is a real calendar date, so this changes
     * nothing for the shipped data; it only refuses a value the set could never have meant.
     */
    fun releaseDateToMillis(date: String?): Long? {
        if (date.isNullOrBlank()) return null
        return try {
            val parts = date.split("-")
            val year = parts[0].toInt()
            val month = if (parts.size > 1) parts[1].toInt() else 1
            val day = if (parts.size > 2) parts[2].toInt() else 1
            // Refuse an impossible month or day before Calendar gets a chance to roll it over.
            if (month !in 1..12 || day !in 1..31) return null
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.isLenient = false
            cal.clear()
            cal.set(year, month - 1, day)
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Loose SoC comparison. Android reports part numbers (SM8550) on some builds and marketing
     * names (Snapdragon 8 Gen 2) on others, so both directions are tested before declaring a
     * mismatch — a false "fake chipset" warning is worse than no warning.
     */
    fun socMatches(reported: String?, reference: String): Boolean {
        val r = normalise(reported)
        val ref = normalise(reference)
        if (r.isEmpty() || ref.isEmpty()) return false
        if (r == ref) return true
        if (ref.contains(r) || r.contains(ref)) return true
        val refTokens = reference.lowercase(Locale.US)
            .split(nonAlphanumericRuns)
            .filter { it.length >= 4 }
        if (refTokens.any { r.contains(it) }) return true
        val reportedTokens = reported.orEmpty().lowercase(Locale.US)
            .split(nonAlphanumericRuns)
            .filter { it.length >= 4 }
        return reportedTokens.any { ref.contains(it) }
    }

    fun referenceLabel(reference: ReferenceDevice?): String =
        reference?.let { "${it.brand} ${it.marketingName}" } ?: "no reference entry"
}
