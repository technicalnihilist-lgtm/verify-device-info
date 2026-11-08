package com.verifydeviceinfo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.data.android.FileDiagnosticHistory
import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import com.verifydeviceinfo.domain.analysis.DefaultAuthenticityEngine
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine
import com.verifydeviceinfo.domain.report.ReportJson
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The stored report file, not just the index beside it.
 *
 * `save` writes one JSON document per scan and the index entry that points at it. The index has
 * been written through [com.verifydeviceinfo.data.android.atomicWriteText] since B2, but the report
 * itself was still written with `writeText`, which truncates the target before writing the new
 * contents: a save interrupted half-way — process killed, storage full — left a truncated document
 * under `history/reports/`, and the History screen shows that file's raw text when the entry is
 * expanded. These tests pin that the report is stored complete, that the temporary sibling the
 * crash-safe write uses never survives a save, and that a report that cannot be stored leaves no
 * partial file behind.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryReportWriteTest {

    private lateinit var history: FileDiagnosticHistory

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val historyDir: File get() = File(context.filesDir, "history")

    private val reportsDir: File get() = File(historyDir, "reports")

    @Before
    fun setUp() {
        historyDir.deleteRecursively()
        history = FileDiagnosticHistory(context)
    }

    @After
    fun tearDown() {
        reportsDir.setWritable(true, true)
        historyDir.deleteRecursively()
    }

    private fun report(redacted: Boolean = true): DeviceReport {
        val snapshot = TestFixtures.snapshot()
        val session = DiagnosticSession()
        val parts = DefaultPartsProvider().assess(snapshot, TestFixtures.referenceMatch(), session)
        val integrity = DefaultIntegrityAnalyzer().analyze(snapshot)
        return DeviceReport(
            snapshot = snapshot,
            authenticity = DefaultAuthenticityEngine().analyze(snapshot, TestFixtures.referenceMatch()),
            parts = parts,
            age = DefaultAgeEstimator().estimate(snapshot, TestFixtures.referenceMatch(), null),
            integrity = integrity,
            scores = DefaultScoreEngine().score(snapshot, parts, integrity, session),
            session = session,
            generatedAtMillis = TestFixtures.NOW,
            appVersion = "1.0.0",
            redacted = redacted
        )
    }

    private fun storedReports(): List<File> =
        reportsDir.listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()

    @Test
    fun `stores exactly the bytes the encoder produces`() = runBlocking {
        val report = report()

        val entry = history.save(report, reportPath = null)

        val stored = File(reportsDir, "${entry.id}.json")
        assertTrue("the report was not stored", stored.isFile)
        assertEquals(ReportJson.encode(report, redacted = true), stored.readText())
    }

    @Test
    fun `the stored report is a whole json document`() = runBlocking {
        val entry = history.save(report(), reportPath = null)

        val stored = File(reportsDir, "${entry.id}.json")
        // A truncated file fails this parse; that is the failure mode the crash-safe write removes.
        val document = JSONObject(stored.readText())
        assertTrue(document.has("reportFormatVersion"))
        assertTrue(document.has("platform"))
        assertEquals(stored.readText(), history.loadRaw(entry.id))
    }

    @Test
    fun `leaves no temporary file beside the report`() = runBlocking {
        history.save(report(), reportPath = null)

        val names = reportsDir.listFiles()?.map { it.name }.orEmpty()
        assertEquals(1, names.size)
        assertTrue(names.single().endsWith(".json"))
        assertFalse(names.any { it.endsWith(".tmp") })
    }

    @Test
    fun `a report that cannot be stored leaves no partial file`() = runBlocking {
        assertTrue(reportsDir.mkdirs())
        assertTrue(reportsDir.setWritable(false, true))

        val entry = history.save(report(), reportPath = null)

        assertEquals("a partial report survived a failed write", emptyList<File>(), storedReports())
        // The save itself still succeeds: the metadata entry is what the History list renders, and
        // failing to store the document has always degraded to "the report cannot be re-opened".
        assertTrue(history.list().any { it.id == entry.id })
    }
}
