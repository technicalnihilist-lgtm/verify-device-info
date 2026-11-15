package com.verifydeviceinfo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.core.model.HistoryEntry
import com.verifydeviceinfo.data.android.FileDiagnosticHistory
import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import com.verifydeviceinfo.domain.analysis.DefaultAuthenticityEngine
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Clearing the history deletes two things — the index and the documents it points at — and the
 * order matters.
 *
 * The index is the only one of the two the History screen renders: it is what the list is built
 * from, and an entry is expanded by reading the document its id names. So a clear that removes the
 * documents first and the index second has one state it can stop in that the user sees and that
 * nothing repairs — a full list of entries, every one of which opens to nothing. The next save reads
 * the index it finds and writes those entries back, and the prune has no file left to remove, so the
 * broken list outlives every later scan.
 *
 * These tests pin the order by its observable consequence: an index that cannot be removed leaves
 * the documents alone, and the documents a clear leaves behind when it stops after its first step
 * are invisible and get pruned. Both hold on the fixed `clear` and neither holds on the old one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryClearTest {

    private lateinit var history: FileDiagnosticHistory

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val historyDir: File get() = File(context.filesDir, "history")

    private val reportsDir: File get() = File(historyDir, "reports")

    private val indexFile: File get() = File(historyDir, "index.json")

    @Before
    fun setUp() {
        historyDir.setWritable(true, true)
        historyDir.deleteRecursively()
        history = FileDiagnosticHistory(context)
    }

    @After
    fun tearDown() {
        historyDir.setWritable(true, true)
        reportsDir.setWritable(true, true)
        historyDir.deleteRecursively()
    }

    private fun report(): DeviceReport {
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
            redacted = true
        )
    }

    private fun storedReportIds(): List<String> =
        reportsDir.listFiles()?.map { it.name.removeSuffix(".json") }.orEmpty().sorted()

    @Test
    fun `clear empties the list and the documents behind it`() = runBlocking {
        val entry = history.save(report(), reportPath = null)
        assertEquals(listOf(entry.id), storedReportIds())

        history.clear()

        assertEquals("the index survived a clear", emptyList<HistoryEntry>(), history.list())
        assertFalse("the index file survived a clear", indexFile.exists())
        assertEquals("a stored document survived a clear", emptyList<String>(), storedReportIds())
    }

    @Test
    fun `an index that cannot be removed leaves the documents alone`() = runBlocking {
        val entry = history.save(report(), reportPath = null)

        assertTrue("could not make the history directory read-only", historyDir.setWritable(false, true))
        try {
            history.clear()
        } finally {
            historyDir.setWritable(true, true)
        }

        // The clear did not happen, so it must not have deleted the documents the index still names:
        // that is exactly the state where the list renders entries that open to nothing.
        assertEquals("the documents were deleted behind an index that is still there",
            listOf(entry.id), storedReportIds())
        assertEquals(listOf(entry.id), history.list().map { it.id })
        assertTrue("the entry no longer opens", history.loadRaw(entry.id)?.isNotEmpty() == true)
    }

    @Test
    fun `a document left by an interrupted clear is invisible and is pruned`() = runBlocking {
        val orphan = history.save(report(), reportPath = null)
        // What a clear that stopped after removing the index leaves: the list is already empty, and
        // the document is still on disk with nothing pointing at it.
        assertTrue(indexFile.delete())
        assertEquals(emptyList<HistoryEntry>(), history.list())
        assertEquals(listOf(orphan.id), storedReportIds())

        val kept = history.save(report(), reportPath = null)

        assertEquals("the orphan was not pruned by the next save", listOf(kept.id), storedReportIds())
        assertEquals(listOf(kept.id), history.list().map { it.id })
        assertNull(history.loadRaw(orphan.id))
    }
}
