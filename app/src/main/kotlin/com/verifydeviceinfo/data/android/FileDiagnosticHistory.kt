package com.verifydeviceinfo.data.android

import android.content.Context
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.HistoryEntry
import com.verifydeviceinfo.core.model.RiskLevel
import com.verifydeviceinfo.domain.LocalDiagnosticHistory
import com.verifydeviceinfo.domain.report.ReportJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Local-only history. Reports are written to app-private storage as JSON; nothing leaves the
 * device unless the user explicitly shares an export.
 */
class FileDiagnosticHistory(private val context: Context) : LocalDiagnosticHistory {

    private val indexFile: File get() = File(context.filesDir, "history/index.json")
    private val reportDir: File get() = File(context.filesDir, "history/reports")

    override suspend fun save(report: DeviceReport, reportPath: String?): HistoryEntry =
        withContext(Dispatchers.IO) {
            reportDir.mkdirs()
            val id = UUID.randomUUID().toString()
            val entry = HistoryEntry(
                id = id,
                timestampMillis = report.generatedAtMillis,
                modelLabel = "${report.snapshot.identity.manufacturer} ${report.snapshot.identity.model}",
                riskLevel = report.authenticity.riskLevel,
                hardwareScore = report.scores.byName("Hardware health")?.score,
                authenticityScore = report.scores.byName("Component authenticity confidence")?.score,
                issueCount = report.authenticity.findings.count {
                    it.status == com.verifydeviceinfo.core.model.DiagnosticStatus.WARNING ||
                        it.status == com.verifydeviceinfo.core.model.DiagnosticStatus.FAILED
                },
                reportPath = reportPath
            )
            try {
                File(reportDir, "$id.json").writeText(ReportJson.encode(report, redacted = true))
            } catch (ignored: Exception) {
            }
            val entries = readIndex().toMutableList()
            entries.add(0, entry)
            val kept = entries.take(MAX_INDEX_ENTRIES)
            writeIndex(kept)
            // Reports that fell out of the index can no longer be opened from the History screen,
            // so drop their files as well; otherwise app-private storage grows without bound.
            runCatching { pruneReports(kept) }
            entry
        }

    override suspend fun list(): List<HistoryEntry> = withContext(Dispatchers.IO) { readIndex() }

    override suspend fun loadRaw(reportId: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = File(reportDir, "$reportId.json")
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        reportDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
        Unit
    }

    private fun pruneReports(kept: List<HistoryEntry>) {
        val files = reportDir.listFiles() ?: return
        val stale = staleReportFiles(kept.mapTo(HashSet()) { it.id }, files.map { it.name }).toSet()
        if (stale.isEmpty()) return
        files.forEach { file -> if (file.name in stale) file.delete() }
    }

    private fun readIndex(): List<HistoryEntry> {
        val file = indexFile
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                HistoryEntry(
                    id = obj.getString("id"),
                    timestampMillis = obj.getLong("timestampMillis"),
                    modelLabel = obj.getString("modelLabel"),
                    riskLevel = runCatching { RiskLevel.valueOf(obj.getString("riskLevel")) }
                        .getOrDefault(RiskLevel.UNKNOWN),
                    hardwareScore = obj.optInt("hardwareScore", -1).takeIf { it >= 0 },
                    authenticityScore = obj.optInt("authenticityScore", -1).takeIf { it >= 0 },
                    issueCount = obj.optInt("issueCount", 0),
                    reportPath = obj.optString("reportPath").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("timestampMillis", entry.timestampMillis)
                    put("modelLabel", entry.modelLabel)
                    put("riskLevel", entry.riskLevel.name)
                    entry.hardwareScore?.let { put("hardwareScore", it) }
                    entry.authenticityScore?.let { put("authenticityScore", it) }
                    put("issueCount", entry.issueCount)
                    entry.reportPath?.let { put("reportPath", it) }
                }
            )
        }
        atomicWriteText(indexFile, array.toString())
    }

    private companion object {
        const val MAX_INDEX_ENTRIES = 50
    }
}

/**
 * Writes [content] to [target] so that an interrupted write can never leave a truncated file.
 *
 * The bytes go to a sibling temporary file, are flushed to the storage device, and only then
 * renamed over [target]. A rename inside one directory is atomic, so a reader sees either the
 * complete previous contents or the complete new contents — never half of either. If anything
 * fails the temporary file is removed and [target] is left exactly as it was.
 */
internal fun atomicWriteText(target: File, content: String) {
    val file = target.absoluteFile
    val dir = file.parentFile ?: throw IOException("no parent directory for ${file.name}")
    if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
        throw IOException("could not create ${dir.name}")
    }
    val temp = File(dir, "${file.name}.tmp")
    try {
        FileOutputStream(temp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
    } catch (t: Throwable) {
        // Only clean up a partial temp file; anything else at that path is not ours to remove.
        runCatching { if (temp.isFile) temp.delete() }
        throw t
    }
}

/**
 * Report files are written as `<uuid>.json`. Nothing else in `history/reports/` is ours to remove,
 * so only names matching that scheme are ever considered stale.
 */
private val REPORT_FILE_NAME =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.json")

/**
 * Names of report files that no longer have an index entry and can be deleted. Pure, so it is
 * unit-testable without a `Context`.
 */
internal fun staleReportFiles(keptIds: Set<String>, existingNames: List<String>): List<String> =
    existingNames.filter { name ->
        REPORT_FILE_NAME.matches(name) && name.substring(0, name.length - 5) !in keptIds
    }
