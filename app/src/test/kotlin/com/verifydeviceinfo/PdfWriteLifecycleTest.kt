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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        try {
            export(build = { throw IllegalStateException("layout failed") })
            fail("the export should have rethrown the build failure")
        } catch (expected: IllegalStateException) {
            assertFalse("a failed export left ${file.absolutePath} behind", file.exists())
            assertEquals("a failed export left a temporary file behind", emptyList<String>(), leftovers())
        }
    }

    @Test
    fun `a write that throws leaves no partial file behind`() {
        try {
            export(writeTo = { calls += "write"; throw IOException("no space left on device") })
            fail("the export should have rethrown the write failure")
        } catch (expected: IOException) {
            assertFalse("a half-written report was left at ${file.absolutePath}", file.exists())
            assertEquals("a failed export left a temporary file behind", emptyList<String>(), leftovers())
        }

        assertEquals("the document must be closed exactly once", 1, calls.count { it == "close" })
    }

    @Test
    fun `a completed export leaves no temporary file beside the target`() {
        export()

        assertEquals("%PDF-1.4", file.readText())
        assertEquals("a completed export left a temporary file behind", emptyList<String>(), leftovers())
    }

    /**
     * The failure path used to delete the target itself, so an export that failed took the previous
     * complete report with it. The bytes now reach the target only by a rename of a complete
     * document, so a failure has nothing at the target to clean up.
     */
    @Test
    fun `a failed export keeps the previous complete report`() {
        val previous = "%PDF-1.4 a complete earlier export"
        file.writeText(previous)

        try {
            export(writeTo = { throw IOException("no space left on device") })
            fail("the export should have rethrown the write failure")
        } catch (expected: IOException) {
            assertEquals(
                "a failed export must leave the earlier complete report alone",
                previous,
                file.readText()
            )
            assertEquals("a failed export left a temporary file behind", emptyList<String>(), leftovers())
        }
    }

    /**
     * Two taps on "Export PDF" are two exports of one path: the button is not disabled while an
     * export runs, and the view model launches a coroutine per tap. Each export now streams into its
     * own temporary file and renames it over the target, so the target is always either absent or
     * exactly one export's document.
     *
     * The old path handed the target straight to a `FileOutputStream`, which made it visible and
     * empty at the first byte and then grew in place: a share sheet that opened it in that window
     * read a truncated report, and a second export truncating under the first left the two
     * documents interleaved. The reader below watches the target for exactly that window, and each
     * export emits its bytes in chunks with a pause between them so the window is wide enough to be
     * observed rather than merely possible.
     */
    @Test
    fun concurrentExportsOfOneFileNeverInterleave() {
        val writers = 4
        val payloads = (0 until writers).map { index ->
            // Distinct first and last bytes and distinct lengths, so neither an interleaved
            // document nor a truncated one can match any payload.
            "%PDF-1.4 ${('a' + index)}".toByteArray() +
                ByteArray(PAYLOAD_BYTES + index * 4_096) { ('0' + index).code.toByte() } +
                "${('A' + index)}".toByteArray()
        }
        repeat(ROUNDS) { round ->
            directory.listFiles()?.forEach { it.delete() }

            val barrier = CyclicBarrier(writers)
            val failures = CopyOnWriteArrayList<Throwable>()
            val incomplete = CopyOnWriteArrayList<String>()
            val done = AtomicBoolean(false)
            val reader = Thread {
                while (!done.get()) {
                    val seen = if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
                    if (seen != null && seen.isNotEmpty() && payloads.none { it.contentEquals(seen) }) {
                        incomplete += "${seen.size} bytes, which is no export's document"
                        break
                    }
                    Thread.sleep(1)
                }
            }
            val threads = payloads.map { payload ->
                Thread {
                    try {
                        barrier.await(30, TimeUnit.SECONDS)
                        // No shared `calls` list here: the default lambdas would append to it from
                        // four threads at once, and the point of this test is the file.
                        writePdfFile(file, {}, {}, { out ->
                            var offset = 0
                            while (offset < payload.size) {
                                val length = minOf(CHUNK_BYTES, payload.size - offset)
                                out.write(payload, offset, length)
                                out.flush()
                                offset += length
                                Thread.sleep(2)
                            }
                        }, {})
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            }
            reader.start()
            threads.forEach { it.start() }
            threads.forEach { it.join(60_000) }
            done.set(true)
            reader.join(30_000)

            assertTrue(
                "round $round: an export of the same file failed: ${failures.firstOrNull()}",
                failures.isEmpty()
            )
            assertTrue(
                "round $round: the target was readable as a partial document: ${incomplete.firstOrNull()}",
                incomplete.isEmpty()
            )
            val written = file.readBytes()
            assertTrue(
                "round $round: the target is not any export's document — ${written.size} bytes, " +
                    "interleaved or truncated",
                payloads.any { it.contentEquals(written) }
            )
            assertEquals(
                "round $round: a temporary file was left behind: ${leftovers()}",
                emptyList<String>(),
                leftovers()
            )
        }
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

    /** Every file in the directory except the target: any of them is a temporary file. */
    private fun leftovers(): List<String> =
        directory.listFiles()?.map { it.name }.orEmpty().filter { it != file.name }.sorted()

    private companion object {
        const val PAYLOAD_BYTES = 200_000
        const val CHUNK_BYTES = 32 * 1024
        const val ROUNDS = 6
    }
}
