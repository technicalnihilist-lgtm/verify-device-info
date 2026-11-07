package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.writePdfFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The file lifecycle of the PDF export.
 *
 * `PdfDocument` refuses to write while a page is still current, and the layout only finishes the
 * pages it breaks away from: the page still open at the end of the report was finished by nobody,
 * because the writer's `close()` had no callers. Every export therefore failed at `writeTo` with the
 * report half-built, and the native document was never closed on that path either — nor was a
 * half-written file removed.
 *
 * Robolectric cannot stand in for `PdfDocument` here (its native handle is always 0, so the very
 * first `startPage` throws), so the drawing and the file lifecycle are separated: these tests drive
 * the lifecycle with fakes and pin the ordering and the cleanup that the real document depends on.
 */
class PdfWriteLifecycleTest {

    private lateinit var directory: File
    private val calls = mutableListOf<String>()

    private val file: File
        get() = File(directory, "verify-device-info-report.pdf")

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("pdf-write").toFile()
        calls.clear()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun export(
        build: () -> Unit = { calls += "build" },
        finishLastPage: () -> Unit = { calls += "finish" },
        writeTo: (java.io.OutputStream) -> Unit = { out ->
            calls += "write"
            out.write("%PDF-1.4".toByteArray())
        },
        closeDocument: () -> Unit = { calls += "close" }
    ): File = writePdfFile(file, build, finishLastPage, writeTo, closeDocument)

    @Test
    fun `the last page is finished before the document is written`() {
        export()

        assertTrue("nothing was written", calls.contains("write"))
        assertTrue(
            "the last page must be finished before writeTo, or PdfDocument refuses to write: $calls",
            calls.indexOf("finish") < calls.indexOf("write")
        )
    }

    @Test
    fun `a completed export returns the file it wrote`() {
        val result = export()

        assertEquals("the export must return the file it wrote", file, result)
        assertTrue("the export produced no file", file.exists())
        assertEquals("%PDF-1.4", file.readText())
    }

    @Test
    fun `the document is closed on the success path`() {
        export()

        assertEquals("the document must be closed exactly once", 1, calls.count { it == "close" })
        assertEquals("close must come last", "close", calls.last())
    }

    @Test
    fun `a build that throws still closes the document`() {
        val failure = IllegalStateException("layout failed")

        try {
            export(build = { throw failure })
            fail("the export should have rethrown the build failure")
        } catch (thrown: IllegalStateException) {
            assertSame("the original failure must reach the caller", failure, thrown)
        }

        assertEquals("the document must be closed exactly once", 1, calls.count { it == "close" })
    }

    @Test
    fun `a build that throws leaves no report file behind`() {
        file.writeText("stale report from an earlier export")

        try {
            export(build = { throw IllegalStateException("layout failed") })
            fail("the export should have rethrown the build failure")
        } catch (expected: IllegalStateException) {
            assertFalse("a failed export left ${file.absolutePath} behind", file.exists())
        }
    }

    @Test
    fun `a write that throws leaves no partial file behind`() {
        try {
            export(writeTo = { calls += "write"; throw IOException("no space left on device") })
            fail("the export should have rethrown the write failure")
        } catch (expected: IOException) {
            assertFalse("a half-written report was left at ${file.absolutePath}", file.exists())
        }

        assertEquals("the document must be closed exactly once", 1, calls.count { it == "close" })
    }

    @Test
    fun `cleanup cannot replace the failure being reported`() {
        val original = IllegalStateException("layout failed")
        val cleanup = IllegalStateException("page still open")

        try {
            export(
                build = { throw original },
                // On the error path a page is still open, so finishPage/close throw in the cleanup.
                finishLastPage = { calls += "finish"; throw cleanup },
                closeDocument = { throw cleanup }
            )
            fail("the export should have rethrown the build failure")
        } catch (thrown: Throwable) {
            assertSame("cleanup replaced the original failure", original, thrown)
        }
    }
}
