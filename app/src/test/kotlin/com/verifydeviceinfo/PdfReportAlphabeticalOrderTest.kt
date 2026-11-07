package com.verifydeviceinfo

import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification
import com.verifydeviceinfo.data.android.alphabeticalByLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The A–Z section of the PDF export.
 *
 * Pinned here: the order is unchanged for every device locale, and the sort key is computed once
 * per field rather than once per comparison.
 */
class PdfReportAlphabeticalOrderTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun field(label: String) = SpecField(
        key = label.lowercase(Locale.US).replace(' ', '_'),
        label = label,
        value = "value of $label",
        group = SpecGroup.OTHER,
        status = DiagnosticStatus.VERIFIED,
        confidence = Confidence.HIGH,
        verification = Verification.DIRECTLY_VERIFIED,
        source = "test"
    )

    /** Mixed case, plus the labels that a default-locale lowercase would reorder under `tr-TR`. */
    private val labels = listOf(
        "IMEI", "Imaging sensor", "identifier", "Kernel", "build type",
        "Battery capacity", "API level", "Wi-Fi MAC", "uptime", "Chipset"
    )

    private fun fields() = labels.map(::field)

    @Test
    fun `order is identical to the previous selector`() {
        val expected = fields().sortedBy { it.label.lowercase() }
        assertEquals(expected.map { it.label }, fields().alphabeticalByLabel().map { it.label })
    }

    @Test
    fun `order does not depend on the default locale`() {
        Locale.setDefault(Locale.US)
        val us = fields().alphabeticalByLabel().map { it.label }

        Locale.setDefault(Locale("tr", "TR"))
        val tr = fields().alphabeticalByLabel().map { it.label }

        assertEquals(us, tr)
    }

    @Test
    fun `the sort key is computed once per field`() {
        val fields = fields()
        var calls = 0
        val ordered = fields.alphabeticalByLabel { field -> calls++; field.label }

        assertEquals(fields.size, calls)
        assertEquals(fields.size, ordered.size)
    }

    @Test
    fun `the previous selector ran the key function many times per field`() {
        // The measurement this item was raised on: `sortedBy { it.label.lowercase() }` evaluates
        // the selector twice per comparison, i.e. O(n log n) times, not once per field.
        val fields = fields()
        var calls = 0
        fields.sortedBy { calls++; it.label.lowercase() }

        assertTrue(
            "expected far more than ${fields.size} selector calls, got $calls",
            calls > fields.size * 4
        )
    }

    @Test
    fun `fields with equal keys keep their original order`() {
        // The old sort was stable; the new one must be too, or fields sharing a label could swap.
        val same = listOf(field("Uptime"), field("UPTIME"), field("Uptime"))
        assertEquals(
            listOf("value of Uptime", "value of UPTIME", "value of Uptime"),
            same.alphabeticalByLabel().map { it.value }
        )
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<SpecField>(), emptyList<SpecField>().alphabeticalByLabel())
    }
}
