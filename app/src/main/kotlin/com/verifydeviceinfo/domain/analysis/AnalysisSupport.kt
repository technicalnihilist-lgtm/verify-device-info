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
     * Compiled once for the life of the process, for the token splitting in [socMatches].
     *
     * `Regex` compiles its pattern in the constructor, so a pattern rebuilt per call dominated the
     * cost of splitting a short string. [normalise] no longer uses a pattern at all — see there.
     */
    private val nonAlphanumericRuns = Regex("[^a-z0-9]+")

    /**
     * Uppercase-or-lowercase-insensitive, alphanumerics only: the form every comparison in this file
     * works on.
     *
     * This used to be `lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")`. The pattern was
     * cached, but `Regex.replace` still built a `Matcher` and a result string on every call, and
     * [normalise] runs in tight loops over model, brand and SoC strings during a scan — twice per
     * brand comparison, once per reference entry at load, once per SoC comparison. The filter is now
     * a character loop, and a string that is already clean (a lowercase brand like `samsung`, which
     * is the common case) is returned without building anything at all.
     *
     * The result is character-for-character what the regex produced: `lowercase` still runs first, so
     * a mapping that changes the length (`İ` → `i̇`) is filtered exactly as before, and the kept set is
     * still `a`-`z` and `0`-`9` only — every other character, including non-ASCII letters and
     * digits in another script, is dropped.
     */
    fun normalise(value: String?): String {
        val lowered = value.orEmpty().lowercase(Locale.US)
        var clean = true
        for (index in lowered.indices) {
            val character = lowered[index]
            if ((character < 'a' || character > 'z') && (character < '0' || character > '9')) {
                clean = false
                break
            }
        }
        if (clean) return lowered
        val filtered = StringBuilder(lowered.length)
        for (character in lowered) {
            if ((character in 'a'..'z') || (character in '0'..'9')) filtered.append(character)
        }
        return filtered.toString()
    }

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
     * A security patch level — `2024-05-05`, or the coarser `2024-05` some vendors publish — as UTC
     * midnight on the first day of that month, or `null` when it is not a real year and month.
     *
     * Only the year and month are read, because a patch level names a bulletin month and the day
     * component varies by vendor for the same bulletin; the day is pinned to the 1st exactly as the
     * three copies this replaced pinned it, so a valid value produces the same instant it always did.
     *
     * The parse is strict for the same reason [releaseDateToMillis] is. `Calendar` is lenient, so
     * `2024-13` silently became 1 January 2025 and `2024-00` became 1 December 2023: a patch level
     * that is not a date was turned into a plausible one, and because a date in the future reads as
     * "patched recently" (`daysBetween` floors at zero), the invented value could only ever make the
     * Security screen and the Software-and-security score look *better* than the device earned. A
     * value the build never published as a date is reported as missing instead, which is the same
     * UNVERIFIED / excluded-input path an absent patch level already took.
     */
    fun patchMonthToMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            // `2024-05` is the shortest form that carries both fields; anything shorter has no
            // month at index 5 and used to throw its way to `null` through `substring`.
            if (value.length < 7) return null
            val year = value.substring(0, 4).toInt()
            val month = value.substring(5, 7).toInt()
            // Refuse an impossible month before Calendar gets a chance to roll it over.
            if (month !in 1..12) return null
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.isLenient = false
            cal.clear()
            cal.set(year, month - 1, 1)
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
