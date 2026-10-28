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
            writeIndex(entries.take(50))
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
        indexFile.parentFile?.mkdirs()
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
        indexFile.writeText(array.toString())
    }
}
