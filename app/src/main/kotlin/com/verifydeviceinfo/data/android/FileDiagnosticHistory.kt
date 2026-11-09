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
                // Crash-safe, like the index that points at it: the stored report is what the
                // History screen re-opens, and a `writeText` interrupted half-way (process killed,
                // storage full) used to leave a truncated document that was then shown as if it
                // were the whole report. A temporary sibling plus a rename means a reader sees the
                // complete document or no document at all.
                atomicWriteText(
                    File(reportDir, "$id.json"),
                    ReportJson.encode(report, redacted = true)
                )
            } catch (ignored: Exception) {
            }
            // `null` here means the stored index could not be understood, which is not the same
            // thing as an empty one: the prune below deletes every report file it cannot account
            // for, so a corrupt index read as "nothing is kept" would destroy the whole history.
            val previous = readIndex()
            val entries = (previous ?: emptyList()).toMutableList()
            entries.add(0, entry)
            val kept = entries.take(MAX_INDEX_ENTRIES)
            // Best-effort, for the same reason the report write above is: `save` runs inside the
            // scan's own try block, so an exception escaping here used to discard a scan that had
            // already finished — the user was told "Scan failed" and got no report at all, because
            // the one thing that failed was the index entry listing it. A store that cannot take
            // the index now degrades to "this scan is not listed" instead. Pruning stays correct:
            // `kept` is the previous entries plus this one, so it can only be a superset of what
            // the index on disk still points at.
            runCatching { writeIndex(kept) }
            // Reports that fell out of the index can no longer be opened from the History screen,
            // so drop their files as well; otherwise app-private storage grows without bound.
            runCatching { pruneReports(kept, indexReadable = previous != null) }
            entry
        }

    override suspend fun list(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        readIndex() ?: emptyList()
    }

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

    private fun pruneReports(kept: List<HistoryEntry>, indexReadable: Boolean) {
        val files = reportDir.listFiles() ?: return
        val stale = staleReportFiles(
            keptIds = kept.mapTo(HashSet()) { it.id },
            existingNames = files.map { it.name },
            indexReadable = indexReadable
        ).toSet()
        if (stale.isEmpty()) return
        files.forEach { file -> if (file.name in stale) file.delete() }
    }

    /**
     * The stored index, or `null` when the file exists but its contents cannot be understood.
     *
     * The two cases are deliberately distinct. An absent index means nothing was ever stored, so
     * nothing is kept; an unreadable one means the entries are unknown, and [pruneReports] must not
     * act on "unknown" as if it were "none".
     */
    private fun readIndex(): List<HistoryEntry>? {
        val file = indexFile
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return null
        }
        return parseHistoryIndex(text)
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
 *
 * [indexReadable] is the guard against the destructive reading of a corrupt index: when the stored
 * index could not be parsed, every stored report looks unaccounted for, and deleting them would turn
 * one damaged file into the loss of the entire history. Nothing is pruned in that case.
 */
internal fun staleReportFiles(
    keptIds: Set<String>,
    existingNames: List<String>,
    indexReadable: Boolean = true
): List<String> = if (!indexReadable) {
    emptyList()
} else {
    existingNames.filter { name ->
        REPORT_FILE_NAME.matches(name) && name.substring(0, name.length - 5) !in keptIds
    }
}

/**
 * Parses the history index, tolerating damage entry by entry.
 *
 * Returns `null` when the document itself is not a JSON array — the caller has to distinguish that
 * from an empty history. Inside a readable document a single unusable entry (a missing or blank
 * `id`, a value of the wrong type, a non-object element) is skipped instead of taking the other
 * entries with it: this file is rewritten on every save, so one bad entry used to mean the next
 * save started from an empty list and pruned every stored report.
 */
internal fun parseHistoryIndex(text: String): List<HistoryEntry>? {
    val array = try {
        JSONArray(text)
    } catch (e: Exception) {
        return null
    }
    return (0 until array.length()).mapNotNull { index -> parseHistoryEntry(array.optJSONObject(index)) }
}

/** One index entry, or `null` when it cannot be used. See [parseHistoryIndex]. */
internal fun parseHistoryEntry(obj: JSONObject?): HistoryEntry? {
    if (obj == null) return null
    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
    return HistoryEntry(
        id = id,
        timestampMillis = obj.optLong("timestampMillis", 0L),
        modelLabel = obj.optString("modelLabel"),
        riskLevel = runCatching { RiskLevel.valueOf(obj.optString("riskLevel")) }
            .getOrDefault(RiskLevel.UNKNOWN),
        hardwareScore = obj.optInt("hardwareScore", -1).takeIf { it >= 0 },
        authenticityScore = obj.optInt("authenticityScore", -1).takeIf { it >= 0 },
        issueCount = obj.optInt("issueCount", 0),
        reportPath = obj.optString("reportPath").takeIf { it.isNotBlank() }
    )
}
