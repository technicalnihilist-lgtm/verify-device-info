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

    fun normalise(value: String?): String =
        value.orEmpty().lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

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

    fun releaseDateToMillis(date: String?): Long? {
        if (date.isNullOrBlank()) return null
        return try {
            val parts = date.split("-")
            val year = parts[0].toInt()
            val month = if (parts.size > 1) parts[1].toInt() else 1
            val day = if (parts.size > 2) parts[2].toInt() else 1
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
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
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }
        if (refTokens.any { r.contains(it) }) return true
        val reportedTokens = reported.orEmpty().lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }
        return reportedTokens.any { ref.contains(it) }
    }

    fun referenceLabel(reference: ReferenceDevice?): String =
        reference?.let { "${it.brand} ${it.marketingName}" } ?: "no reference entry"
}
