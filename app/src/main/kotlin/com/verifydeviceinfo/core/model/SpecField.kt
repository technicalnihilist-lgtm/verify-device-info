package com.verifydeviceinfo.core.model

/**
 * A single collected device property. Every field carries its own provenance, so the UI and
 * the exported report can always answer "how do you know that?".
 */
data class SpecField(
    val key: String,
    val label: String,
    val value: String,
    val group: SpecGroup,
    val status: DiagnosticStatus,
    val confidence: Confidence,
    val verification: Verification,
    val source: String,
    val note: String? = null,
    /** Sensitive identifiers are redacted by default in exports. */
    val sensitive: Boolean = false
)

/** One auditable piece of supporting information behind a finding. */
data class Evidence(
    val description: String,
    val source: EvidenceSource,
    val detail: String? = null
)

/** Progress emitted while a scan runs. Drives the animated scan sequence, never faked. */
data class ScanProgress(
    val completedStages: Int,
    val totalStages: Int,
    val stageLabel: String,
    val finished: Boolean = false
) {
    val fraction: Float get() = if (totalStages <= 0) 0f else (completedStages.toFloat() / totalStages).coerceIn(0f, 1f)
}
