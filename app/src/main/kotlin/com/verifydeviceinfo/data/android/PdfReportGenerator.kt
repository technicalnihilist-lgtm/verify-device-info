package com.verifydeviceinfo.data.android

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.SpecField
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

    private companion object {
        const val REPORT_FILE_NAME = "verify-device-info-report.pdf"
    }

    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 40f

    override suspend fun toJson(report: DeviceReport): String =
        withContext(Dispatchers.Default) { ReportJson.encode(report, redacted = report.redacted) }

    override suspend fun toJsonFile(report: DeviceReport, file: File): File {
        val text = toJson(report)
        withContext(Dispatchers.IO) { atomicWriteText(file, text) }
        return file
    }

    override suspend fun toPdf(report: DeviceReport): File = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        val writer = PageWriter(document)
        writePdfFile(
            file = File(context.cacheDir, REPORT_FILE_NAME),
            build = { writer.render(report) },
            finishLastPage = writer::close,
            writeTo = document::writeTo,
            closeDocument = document::close
        )
    }

    /** Draws the whole report into the writer's document, page breaks included. */
    private fun PageWriter.render(report: DeviceReport) {
        title("Verify Device Info — Device Report")
        small(
            "Generated ${Formatters.date(report.generatedAtMillis)} · app version ${report.appVersion}" +
                (if (report.redacted) " · sensitive identifiers redacted" else "")
        )
        if (report.snapshot.isMock) {
            warning("MOCK DATA — this report was produced from a development fixture, not a real device.")
        }
        rule()

        // ---- Identity ----
        section("1. Device identification")
        keyValue("Manufacturer", report.snapshot.identity.manufacturer)
        keyValue("Brand", report.snapshot.identity.brand)
        keyValue("Model", report.snapshot.identity.model)
        report.snapshot.identity.marketingName?.let { keyValue("Marketing name", it) }
        keyValue("Device codename", report.snapshot.identity.device)
        keyValue("Product", report.snapshot.identity.product)
        keyValue("Hardware", report.snapshot.identity.hardware)
        keyValue("Build fingerprint", report.snapshot.identity.fingerprint)
        keyValue(
            "SoC",
            listOfNotNull(
                report.snapshot.identity.socManufacturer,
                report.snapshot.identity.socModel
            ).joinToString(" ").ifBlank { "Not exposed" }
        )
        keyValue(
            "Serial number",
            if (report.redacted) "Redacted" else report.snapshot.identity.serial ?: "Not accessible"
        )

        // ---- OS ----
        section("2. Operating system")
        keyValue("Android version", report.snapshot.software.androidRelease)
        keyValue("API level", report.snapshot.software.apiLevel.toString())
        keyValue("Security patch", report.snapshot.software.securityPatch ?: "Unknown")
        keyValue("Kernel", report.snapshot.software.kernelVersion ?: "Unknown")
        keyValue("Build type / tags",
            "${report.snapshot.identity.buildType} / ${report.snapshot.identity.buildTags}")
        keyValue("Uptime", Formatters.uptime(report.snapshot.software.uptimeMillis))

        // ---- Specs ----
        section("3. Specifications (A–Z)")
        report.snapshot.fields
            .alphabeticalByLabel()
            .forEach { field ->
                val value = if (report.redacted && field.sensitive) "Redacted" else field.value
                keyValue(field.label, "$value  [${field.status.name}, ${field.confidence.name} confidence]")
            }

        // ---- Authenticity ----
        section("4. Authenticity analysis")
        body(report.authenticity.headline)
        keyValue("Risk level", report.authenticity.riskLevel.name)
        keyValue("Reference data", report.authenticity.referenceSourceSummary)
        report.authenticity.findings.forEach { finding ->
            finding(finding.title, finding.status, finding.explanation)
            finding.evidence.forEach { evidence ->
                small("• ${evidence.description} (${evidence.source.name})")
            }
        }

        // ---- Parts ----
        section("5. Parts and repair analysis")
        report.parts.components.forEach { component ->
            finding(
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
        small("Limitations:")
        report.parts.limitations.forEach { small("• $it") }

        // ---- Age ----
        section("6. Device age")
        keyValue("Model launch date", report.age.modelLaunchDate ?: "Unknown")
        keyValue("Model age", Formatters.duration(report.age.modelLaunchAgeDays))
        keyValue("Firmware build date", report.age.firmwareBuildDate ?: "Unknown")
        keyValue("Firmware age", Formatters.duration(report.age.firmwareBuildAgeDays))
        keyValue("Ownership age", Formatters.duration(report.age.ownershipAgeDays))
        keyValue("Estimated usage age", Formatters.duration(report.age.estimatedUsageAgeDays))
        keyValue("Security patch age", Formatters.duration(report.age.daysSinceSecurityPatch))
        report.age.notes.forEach { small("• $it") }

        // ---- Integrity ----
        section("7. System integrity")
        body(report.integrity.summary)
        report.integrity.checks.forEach { check ->
            finding(check.title, check.status, check.explanation)
        }

        // ---- Scores ----
        section("8. Health scores")
        report.scores.scores.forEach { score ->
            val value = if (score.score == null) {
                "Not scored (${score.coveragePercent}% coverage)"
            } else {
                "${score.score}/100 ${score.band.name} (${score.coveragePercent}% coverage)"
            }
            finding(
                score.name,
                when (score.band) {
                    ScoreBand.GOOD -> DiagnosticStatus.PASS
                    ScoreBand.FAIR -> DiagnosticStatus.WARNING
                    ScoreBand.POOR -> DiagnosticStatus.FAILED
                    ScoreBand.UNKNOWN -> DiagnosticStatus.UNVERIFIED
                },
                "$value — ${score.summary}"
            )
            score.inputs.forEach { small("• ${it.label}: ${it.value} (weight ${it.weight})") }
        }

        // ---- Diagnostics ----
        section("9. Diagnostic tests")
        if (report.session.results.isEmpty()) {
            body("No interactive tests were run for this scan.")
        } else {
            report.session.results.forEach { result ->
                finding(
                    result.title,
                    result.status,
                    listOfNotNull(result.measured, result.note).joinToString(" — ").ifBlank { "Recorded" }
                )
            }
        }

        // ---- Unknowns ----
        section("10. What could not be verified")
        val unknowns = report.snapshot.fields.filter {
            it.status == DiagnosticStatus.NOT_SUPPORTED ||
                it.status == DiagnosticStatus.UNVERIFIED ||
                it.status == DiagnosticStatus.PERMISSION_REQUIRED
        }
        if (unknowns.isEmpty()) {
            body("Every field in this report was readable.")
        } else {
            unknowns.forEach { small("• ${it.label}: ${it.note ?: it.status.name}") }
        }

        footer(
            "This report reflects what the device reported about itself at " +
                "${Formatters.date(report.generatedAtMillis)}. Software cannot prove that physical " +
                "components are genuine, and unavailable data is not evidence of tampering."
        )
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

/**
 * The file lifecycle of one PDF export, separated from the drawing so it can be tested without a
 * device.
 *
 * `PdfDocument` refuses to write while a page is still current, and the layout only finishes the
 * pages it breaks away from, so the page still open at the end has to be finished by
 * [finishLastPage] *before* [writeTo] runs. The document is closed on every path — a failed build
 * leaves a page open, which makes `close()` itself refuse, so the page is finished first and any
 * cleanup failure is swallowed rather than allowed to replace the exception already in flight. A
 * file that could not be completed is deleted, because a truncated report would otherwise be
 * shared as if it were whole.
 */
internal inline fun writePdfFile(
    file: File,
    build: () -> Unit,
    finishLastPage: () -> Unit,
    writeTo: (java.io.OutputStream) -> Unit,
    closeDocument: () -> Unit
): File {
    try {
        build()
        finishLastPage()
        FileOutputStream(file).use(writeTo)
        return file
    } catch (t: Throwable) {
        file.delete()
        throw t
    } finally {
        runCatching { finishLastPage() }
        runCatching { closeDocument() }
    }
}

/**
 * Alphabetical order for the report's A–Z section, keyed once per field.
 *
 * The previous `sortedBy { it.label.lowercase() }` ran the selector O(n log n) times and
 * lowercased both sides of every comparison, allocating two throw-away strings per comparison —
 * a few thousand of them for the A–Z section of one export. Here each key is built once per
 * field, and [sortedWith] is stable, so the order is identical to the old one.
 *
 * The key uses `lowercase()`, which is the invariant (locale-independent) mapping — the same
 * call the old selector made, so this changes no ordering on any device locale.
 *
 * [labelOf] is the label selector, injectable so a test can count how often it runs.
 */
internal fun List<SpecField>.alphabeticalByLabel(
    labelOf: (SpecField) -> String = { it.label }
): List<SpecField> = map { labelOf(it).lowercase() to it }
    .sortedWith(compareBy { it.first })
    .map { it.second }
