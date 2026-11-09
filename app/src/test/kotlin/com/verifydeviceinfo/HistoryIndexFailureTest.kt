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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The index write is best-effort, like the report write beside it.
 *
 * `save` runs inside the scan's own `try` block in `AppViewModel.scan`, so anything that escaped it
 * discarded a scan that had already finished: the user was shown "Scan failed" and got no report,
 * because the only thing that had failed was the index entry listing that report. A store that
 * cannot take the index — full, unwritable, or blocked as here — must instead degrade to "this scan
 * is not listed", leaving the previous index exactly as it was and the new report file on disk for
 * the next successful save to account for.
 *
 * The obstruction is the one `HistoryIndexWriteTest` already uses: the temporary sibling the
 * crash-safe write goes through is a directory, so the write cannot even open it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryIndexFailureTest {

    private lateinit var history: FileDiagnosticHistory

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val historyDir: File get() = File(context.filesDir, "history")

    private val reportsDir: File get() = File(historyDir, "reports")

    /** The path `atomicWriteText` uses for the index's temporary sibling. */
    private val indexBlocker: File get() = File(historyDir, "index.json.tmp")

    @Before
    fun setUp() {
        historyDir.deleteRecursively()
        history = FileDiagnosticHistory(context)
    }

    @After
    fun tearDown() {
        indexBlocker.setWritable(true, true)
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

    private fun storedReports(): List<String> =
        reportsDir.listFiles()?.map { it.name }.orEmpty().sorted()

    private fun blockIndexWrite() {
        assertTrue(historyDir.mkdirs() || historyDir.isDirectory)
        assertTrue("could not obstruct the index write", indexBlocker.mkdir())
    }

    @Test
    fun `an index that cannot be written does not fail the save`() = runBlocking {
        blockIndexWrite()

        val entry = history.save(report(), reportPath = null)

        // The call returns instead of throwing, which is what keeps the completed scan on screen.
        assertTrue("the report was not stored", File(reportsDir, "${entry.id}.json").isFile)
        // The degraded state is honest: the scan is simply not listed.
        assertTrue(history.list().isEmpty())
    }

    @Test
    fun `the previous index is left untouched when the new one cannot be written`() = runBlocking {
        val first = history.save(report(), reportPath = null)
        blockIndexWrite()

        val second = history.save(report(), reportPath = null)

        val listed = history.list()
        assertEquals(listOf(first.id), listed.map { it.id })
        assertNotNull(history.loadRaw(first.id))
        // Both documents are on disk: the orphan is pruned by a later save, never by this one.
        assertEquals(
            listOf("${first.id}.json", "${second.id}.json").sorted(),
            storedReports()
        )
        assertFalse(File(historyDir, "index.json.tmp").isFile)
    }

    @Test
    fun `a later save recovers and prunes the orphaned report`() = runBlocking {
        val first = history.save(report(), reportPath = null)
        blockIndexWrite()
        val orphan = history.save(report(), reportPath = null)
        assertTrue("the orphan was not stored", File(reportsDir, "${orphan.id}.json").isFile)
        assertTrue("could not clear the obstruction", indexBlocker.delete())

        val third = history.save(report(), reportPath = null)

        assertEquals(listOf(third.id, first.id), history.list().map { it.id })
        // The orphan has no index entry pointing at it, so it is gone; nothing else was touched.
        assertEquals(
            listOf("${first.id}.json", "${third.id}.json").sorted(),
            storedReports()
        )
    }
}
