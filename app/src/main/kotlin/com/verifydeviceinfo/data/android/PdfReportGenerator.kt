package com.verifydeviceinfo.data.android

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.ComponentState
import com.verifydeviceinfo.core.model.ScoreBand
import com.verifydeviceinfo.core.util.Formatters
import com.verifydeviceinfo.domain.ReportGenerator
import com.verifydeviceinfo.domain.report.ReportJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Vector PDF report. Text is drawn with PdfDocument rather than rasterised, so the export is
 * searchable, selectable and small.
 */
class PdfReportGenerator(private val context: Context) : ReportGenerator {

    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 40f

    override suspend fun toJson(report: DeviceReport): String =
        withContext(Dispatchers.Default) { ReportJson.encode(report, redacted = report.redacted) }

    override suspend fun toPdf(report: DeviceReport): File = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        val writer = PageWriter(document)

        writer.title("Verify Device Info — Device Report")
        writer.small(
            "Generated ${Formatters.date(report.generatedAtMillis)} · app version ${report.appVersion}" +
                (if (report.redacted) " · sensitive identifiers redacted" else "")
        )
        if (report.snapshot.isMock) {
            writer.warning("MOCK DATA — this report was produced from a development fixture, not a real device.")
        }
        writer.rule()

        // ---- Identity ----
        writer.section("1. Device identification")
        writer.keyValue("Manufacturer", report.snapshot.identity.manufacturer)
        writer.keyValue("Brand", report.snapshot.identity.brand)
        writer.keyValue("Model", report.snapshot.identity.model)
        report.snapshot.identity.marketingName?.let { writer.keyValue("Marketing name", it) }
        writer.keyValue("Device codename", report.snapshot.identity.device)
        writer.keyValue("Product", report.snapshot.identity.product)
        writer.keyValue("Hardware", report.snapshot.identity.hardware)
        writer.keyValue("Build fingerprint", report.snapshot.identity.fingerprint)
        writer.keyValue(
            "SoC",
            listOfNotNull(
                report.snapshot.identity.socManufacturer,
                report.snapshot.identity.socModel
            ).joinToString(" ").ifBlank { "Not exposed" }
        )
        writer.keyValue(
            "Serial number",
            if (report.redacted) "Redacted" else report.snapshot.identity.serial ?: "Not accessible"
        )

        // ---- OS ----
        writer.section("2. Operating system")
        writer.keyValue("Android version", report.snapshot.software.androidRelease)
        writer.keyValue("API level", report.snapshot.software.apiLevel.toString())
        writer.keyValue("Security patch", report.snapshot.software.securityPatch ?: "Unknown")
        writer.keyValue("Kernel", report.snapshot.software.kernelVersion ?: "Unknown")
        writer.keyValue("Build type / tags",
            "${report.snapshot.identity.buildType} / ${report.snapshot.identity.buildTags}")
        writer.keyValue("Uptime", Formatters.uptime(report.snapshot.software.uptimeMillis))

        // ---- Specs ----
        writer.section("3. Specifications (A–Z)")
        report.snapshot.fields
            .sortedBy { it.label.lowercase() }
            .forEach { field ->
                val value = if (report.redacted && field.sensitive) "Redacted" else field.value
                writer.keyValue(field.label, "$value  [${field.status.name}, ${field.confidence.name} confidence]")
            }

        // ---- Authenticity ----
        writer.section("4. Authenticity analysis")
        writer.body(report.authenticity.headline)
        writer.keyValue("Risk level", report.authenticity.riskLevel.name)
        writer.keyValue("Reference data", report.authenticity.referenceSourceSummary)
        report.authenticity.findings.forEach { finding ->
            writer.finding(finding.title, finding.status, finding.explanation)
            finding.evidence.forEach { evidence ->
                writer.small("• ${evidence.description} (${evidence.source.name})")
            }
        }

        // ---- Parts ----
        writer.section("5. Parts and repair analysis")
        report.parts.components.forEach { component ->
            writer.finding(
                "${component.component} — ${component.state.name.replace('_', ' ')}",
                when (component.state) {
                    ComponentState.ORIGINALITY_VERIFIED -> DiagnosticStatus.VERIFIED
                    ComponentState.POSSIBLE_COUNTERFEIT -> DiagnosticStatus.FAILED
                    ComponentState.POSSIBLE_REPLACEMENT -> DiagnosticStatus.WARNING
                    ComponentState.NO_ANOMALY_UNVERIFIED -> DiagnosticStatus.UNVERIFIED
                    ComponentState.UNABLE_TO_INSPECT -> DiagnosticStatus.NOT_SUPPORTED
                },
                component.explanation
            )
        }
        writer.small("Limitations:")
        report.parts.limitations.forEach { writer.small("• $it") }

        // ---- Age ----
        writer.section("6. Device age")
        writer.keyValue("Model launch date", report.age.modelLaunchDate ?: "Unknown")
        writer.keyValue("Model age", Formatters.duration(report.age.modelLaunchAgeDays))
        writer.keyValue("Firmware build date", report.age.firmwareBuildDate ?: "Unknown")
        writer.keyValue("Firmware age", Formatters.duration(report.age.firmwareBuildAgeDays))
        writer.keyValue("Ownership age", Formatters.duration(report.age.ownershipAgeDays))
        writer.keyValue("Estimated usage age", Formatters.duration(report.age.estimatedUsageAgeDays))
        writer.keyValue("Security patch age", Formatters.duration(report.age.daysSinceSecurityPatch))
        report.age.notes.forEach { writer.small("• $it") }

        // ---- Integrity ----
        writer.section("7. System integrity")
        writer.body(report.integrity.summary)
        report.integrity.checks.forEach { check ->
            writer.finding(check.title, check.status, check.explanation)
        }

        // ---- Scores ----
        writer.section("8. Health scores")
        report.scores.scores.forEach { score ->
            val value = if (score.score == null) {
                "Not scored (${score.coveragePercent}% coverage)"
            } else {
                "${score.score}/100 ${score.band.name} (${score.coveragePercent}% coverage)"
            }
            writer.finding(
                score.name,
                when (score.band) {
                    ScoreBand.GOOD -> DiagnosticStatus.PASS
                    ScoreBand.FAIR -> DiagnosticStatus.WARNING
                    ScoreBand.POOR -> DiagnosticStatus.FAILED
                    ScoreBand.UNKNOWN -> DiagnosticStatus.UNVERIFIED
                },
                "$value — ${score.summary}"
            )
            score.inputs.forEach { writer.small("• ${it.label}: ${it.value} (weight ${it.weight})") }
        }

        // ---- Diagnostics ----
        writer.section("9. Diagnostic tests")
        if (report.session.results.isEmpty()) {
            writer.body("No interactive tests were run for this scan.")
        } else {
            report.session.results.forEach { result ->
                writer.finding(
                    result.title,
                    result.status,
                    listOfNotNull(result.measured, result.note).joinToString(" — ").ifBlank { "Recorded" }
                )
            }
        }

        // ---- Unknowns ----
        writer.section("10. What could not be verified")
        val unknowns = report.snapshot.fields.filter {
            it.status == DiagnosticStatus.NOT_SUPPORTED ||
                it.status == DiagnosticStatus.UNVERIFIED ||
                it.status == DiagnosticStatus.PERMISSION_REQUIRED
        }
        if (unknowns.isEmpty()) {
            writer.body("Every field in this report was readable.")
        } else {
            unknowns.forEach { writer.small("• ${it.label}: ${it.note ?: it.status.name}") }
        }

        writer.footer(
            "This report reflects what the device reported about itself at " +
                "${Formatters.date(report.generatedAtMillis)}. Software cannot prove that physical " +
                "components are genuine, and unavailable data is not evidence of tampering."
        )

        val file = File(context.cacheDir, "verify-device-info-report.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        file
    }

    /** Minimal paginated text layout with automatic page breaks. */
    private inner class PageWriter(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var canvas = android.graphics.Canvas()
        private var y = margin
        private var pageNumber = 0

        private val titlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 18f
            color = Color.BLACK
            isAntiAlias = true
        }
        private val sectionPaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13f
            color = Color.rgb(20, 60, 120)
            isAntiAlias = true
        }
        private val bodyPaint = Paint().apply {
            textSize = 9.5f
            color = Color.rgb(30, 30, 30)
            isAntiAlias = true
        }
        private val smallPaint = Paint().apply {
            textSize = 8f
            color = Color.rgb(90, 90, 90)
            isAntiAlias = true
        }
        private val labelPaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 9.5f
            color = Color.rgb(20, 20, 20)
            isAntiAlias = true
        }
        private val rulePaint = Paint().apply {
            color = Color.rgb(200, 200, 200)
            strokeWidth = 0.8f
        }

        private fun ensurePage(required: Float) {
            if (page == null || y + required > pageHeight - margin) {
                finishPage()
                pageNumber++
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = document.startPage(info)
                canvas = page!!.canvas
                y = margin
            }
        }

        private fun finishPage() {
            page?.let {
                smallPaint.textSize = 7.5f
                it.canvas.drawText(
                    "Verify Device Info · page $pageNumber",
                    margin, pageHeight - 20f, smallPaint
                )
                document.finishPage(it)
            }
            page = null
        }

        fun title(text: String) {
            ensurePage(30f)
            canvas.drawText(text, margin, y, titlePaint)
            y += 22f
        }

        fun section(text: String) {
            ensurePage(40f)
            y += 8f
            canvas.drawText(text, margin, y, sectionPaint)
            y += 6f
            canvas.drawLine(margin, y, pageWidth - margin, y, rulePaint)
            y += 12f
        }

        fun body(text: String) {
            wrap(text, bodyPaint).forEach { line ->
                ensurePage(14f)
                canvas.drawText(line, margin, y, bodyPaint)
                y += 12f
            }
        }

        fun small(text: String) {
            wrap(text, smallPaint).forEach { line ->
                ensurePage(12f)
                canvas.drawText(line, margin + 6f, y, smallPaint)
                y += 10.5f
            }
        }

        fun warning(text: String) {
            wrap(text, bodyPaint).forEach { line ->
                ensurePage(14f)
                canvas.drawText(line, margin, y, bodyPaint)
                y += 12f
            }
        }

        fun keyValue(label: String, value: String) {
            ensurePage(14f)
            val labelWidth = 150f
            canvas.drawText(label, margin, y, labelPaint)
            val lines = wrap(value, bodyPaint, pageWidth - margin * 2 - labelWidth)
            lines.forEachIndexed { index, line ->
                if (index > 0) ensurePage(12f)
                canvas.drawText(line, margin + labelWidth, y, bodyPaint)
                y += 12f
            }
            if (lines.isEmpty()) y += 12f
        }

        fun finding(title: String, status: DiagnosticStatus, explanation: String) {
            ensurePage(26f)
            canvas.drawText("• $title", margin, y, labelPaint)
            val tag = when (status) {
                DiagnosticStatus.VERIFIED, DiagnosticStatus.PASS -> "OK"
                DiagnosticStatus.WARNING -> "WARNING"
                DiagnosticStatus.FAILED -> "FAILED"
                DiagnosticStatus.UNVERIFIED -> "UNVERIFIED"
                DiagnosticStatus.NOT_SUPPORTED -> "NOT SUPPORTED"
                DiagnosticStatus.PERMISSION_REQUIRED -> "PERMISSION REQUIRED"
            }
            val tagWidth = bodyPaint.measureText(tag)
            canvas.drawText(tag, pageWidth - margin - tagWidth, y, smallPaint)
            y += 12f
            wrap(explanation, bodyPaint, pageWidth - margin * 2 - 10f).forEach { line ->
                ensurePage(12f)
                canvas.drawText(line, margin + 10f, y, bodyPaint)
                y += 11.5f
            }
            y += 3f
        }

        fun rule() {
            ensurePage(10f)
            canvas.drawLine(margin, y, pageWidth - margin, y, rulePaint)
            y += 10f
        }

        fun footer(text: String) {
            y += 10f
            wrap(text, smallPaint).forEach { line ->
                ensurePage(12f)
                canvas.drawText(line, margin, y, smallPaint)
                y += 10.5f
            }
        }

        fun close() {
            finishPage()
        }

        private fun wrap(text: String, paint: Paint, maxWidth: Float = pageWidth - margin * 2): List<String> {
            if (text.isBlank()) return emptyList()
            val words = text.split(" ")
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            words.forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) lines += current.toString()
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
            return lines
        }
    }
}
