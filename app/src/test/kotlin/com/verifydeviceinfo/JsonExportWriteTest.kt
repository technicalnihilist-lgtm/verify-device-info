package com.verifydeviceinfo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.DeviceReport
import com.verifydeviceinfo.core.model.DiagnosticSession
import com.verifydeviceinfo.data.android.PdfReportGenerator
import com.verifydeviceinfo.domain.analysis.DefaultAgeEstimator
import com.verifydeviceinfo.domain.analysis.DefaultAuthenticityEngine
import com.verifydeviceinfo.domain.analysis.DefaultIntegrityAnalyzer
import com.verifydeviceinfo.domain.analysis.DefaultPartsProvider
import com.verifydeviceinfo.domain.analysis.DefaultScoreEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The JSON export's file lifecycle.
 *
 * The export used to be written with `File.writeText` from the view model, which truncates the
 * previous export before writing the new one: an export interrupted between the two left a
 * half-written file that was then handed to the share sheet as if it were whole. `toJsonFile` routes
 * the write through the crash-safe `atomicWriteText` helper instead, so a reader of the export sees
 * either the complete previous contents or the complete new contents.
 *
 * These tests pin that: the bytes are exactly what `ReportJson` encodes (so the exported document
 * does not change), the previous export is replaced rather than appended to, no temporary file is
 * left behind, and a failed write leaves the previous export untouched.
 */
@RunWith(AndroidJUnit4::class)
class JsonExportWriteTest {

    private lateinit var directory: File
    private lateinit var generator: PdfReportGenerator

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("json-export").toFile()
        generator = PdfReportGenerator(context)
    }

    @After
    fun tearDown() {
        directory.setReadable(true, false)
        directory.setWritable(true, false)
        directory.deleteRecursively()
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

    @Test
    fun `writes exactly the bytes the encoder produces`() = runBlocking {
        val report = report()
        val target = File(directory, "verify-device-info-report.json")

        val returned = generator.toJsonFile(report, target)

        assertSame(target, returned)
        assertEquals(generator.toJson(report), target.readText())
        assertTrue(target.readText().trimStart().startsWith("{"))
    }

    @Test
    fun `redaction flag reaches the file`() = runBlocking {
        val target = File(directory, "report.json")

        generator.toJsonFile(report(redacted = true), target)

        assertEquals(generator.toJson(report(redacted = true)), target.readText())
    }

    @Test
    fun `replaces a longer previous export instead of appending to it`() = runBlocking {
        val target = File(directory, "verify-device-info-report.json")
        val stale = "x".repeat(64 * 1024)
        target.writeText(stale)
        assertEquals(stale.length.toLong(), target.length())

        generator.toJsonFile(report(), target)

        assertEquals(generator.toJson(report()), target.readText())
        assertTrue("stale bytes survived the export", target.length() < stale.length)
    }

    @Test
    fun `leaves no temporary file behind`() = runBlocking {
        val target = File(directory, "verify-device-info-report.json")

        generator.toJsonFile(report(), target)

        val leftovers = directory.listFiles().orEmpty().map { it.name }.filter { it != target.name }
        assertEquals("unexpected files next to the export: $leftovers", emptyList<String>(), leftovers)
    }

    @Test
    fun `creates the directory it is asked to write into`() = runBlocking {
        val target = File(directory, "cache/exports/verify-device-info-report.json")

        generator.toJsonFile(report(), target)

        assertTrue(target.isFile)
        assertEquals(generator.toJson(report()), target.readText())
    }

    @Test
    fun `a failed write leaves the previous export intact`() = runBlocking {
        val exports = File(directory, "exports")
        assertTrue(exports.mkdirs())
        val target = File(exports, "verify-device-info-report.json")
        val previous = """{"previous":true}"""
        target.writeText(previous)
        // A directory that cannot be written to: the temporary file cannot be created, so the
        // write must fail before anything replaces the export that is already there.
        assertTrue(exports.setWritable(false, false))

        try {
            generator.toJsonFile(report(), target)
            fail("expected the write to fail in a read-only directory")
        } catch (expected: IOException) {
            assertEquals(previous, target.readText())
            // Each write takes a temporary file of its own (see `AtomicWriteConcurrencyTest`), so
            // the check is that none of them survived — not one name in particular.
            val leftovers = exports.listFiles().orEmpty().map { it.name }.filter { it != target.name }
            assertEquals("unexpected files next to the export: $leftovers", emptyList<String>(), leftovers)
        } finally {
            exports.setWritable(true, false)
        }
    }
}
