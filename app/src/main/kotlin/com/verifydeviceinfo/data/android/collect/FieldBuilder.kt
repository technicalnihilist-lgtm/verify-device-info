package com.verifydeviceinfo.data.android.collect

import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification

/** Small helper so every field carries provenance without repetitive boilerplate. */
internal object FieldBuilder {

    fun value(
        key: String,
        label: String,
        value: String?,
        group: SpecGroup,
        source: String,
        confidence: Confidence = Confidence.HIGH,
        verification: Verification = Verification.DIRECTLY_VERIFIED,
        status: DiagnosticStatus = DiagnosticStatus.VERIFIED,
        note: String? = null,
        sensitive: Boolean = false
    ): SpecField = SpecField(
        key = key,
        label = label,
        value = value?.takeIf { it.isNotBlank() } ?: "Unavailable",
        group = group,
        status = if (value.isNullOrBlank()) DiagnosticStatus.UNVERIFIED else status,
        confidence = if (value.isNullOrBlank()) Confidence.NONE else confidence,
        verification = if (value.isNullOrBlank()) Verification.INACCESSIBLE else verification,
        source = source,
        note = note,
        sensitive = sensitive
    )

    fun unsupported(
        key: String,
        label: String,
        group: SpecGroup,
        source: String,
        reason: String
    ): SpecField = SpecField(
        key = key,
        label = label,
        value = "Not accessible",
        group = group,
        status = DiagnosticStatus.NOT_SUPPORTED,
        confidence = Confidence.NONE,
        verification = Verification.INACCESSIBLE,
        source = source,
        note = reason
    )
}
