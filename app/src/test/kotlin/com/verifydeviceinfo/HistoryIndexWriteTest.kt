package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.atomicWriteText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The history index is a single JSON file; if it is truncated by a crash mid-write, every saved
 * report becomes unreachable. These tests pin the crash-safe write: complete content on success,
 * previous content untouched on failure, and no temporary file left lying around.
 */
class HistoryIndexWriteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun indexFile(): File = File(folder.root, "history/index.json")

    @Test
    fun `writes content when the file does not exist yet`() {
        val target = indexFile()
        atomicWriteText(target, """[{"id":"a"}]""")
        assertEquals("""[{"id":"a"}]""", target.readText())
    }

    @Test
    fun `creates missing parent directories`() {
        val target = File(folder.root, "history/reports/deep/index.json")
        atomicWriteText(target, "x")
        assertTrue(target.exists())
        assertEquals("x", target.readText())
    }

    @Test
    fun `replaces a longer previous value without leaving a tail`() {
        val target = indexFile()
        folder.newFolder("history")
        target.writeText("[" + "1,".repeat(500) + "1]")
        atomicWriteText(target, "[2]")
        assertEquals("[2]", target.readText())
    }

    @Test
    fun `round-trips non-ascii content as utf-8`() {
        val target = indexFile()
        atomicWriteText(target, """{"modelLabel":"Pixel – 8 Pro ✓"}""")
        assertEquals("""{"modelLabel":"Pixel – 8 Pro ✓"}""", target.readText(Charsets.UTF_8))
    }

    @Test
    fun `leaves no temporary file behind on success`() {
        val target = indexFile()
        atomicWriteText(target, "one")
        atomicWriteText(target, "two")
        val leftovers = target.parentFile!!.listFiles()!!.map { it.name }.filter { it != "index.json" }
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun `a failed write keeps the previous contents intact`() {
        val target = indexFile()
        folder.newFolder("history")
        target.writeText("""[{"id":"kept"}]""")
        // Make the directory unwritable so the temporary file cannot be created at all. The
        // obstruction has to be the directory and not one temporary name: every write now takes a
        // temporary file of its own, so no single name can block it (see
        // `AtomicWriteConcurrencyTest`, which is the reason for that).
        val directory = target.parentFile!!
        assertTrue(directory.setWritable(false, false))

        try {
            atomicWriteText(target, "replacement")
            fail("expected the write to fail")
        } catch (expected: Exception) {
            // The point of the test is what the target looks like afterwards.
        } finally {
            directory.setWritable(true, false)
        }

        assertEquals("""[{"id":"kept"}]""", target.readText())
    }

    @Test
    fun `a write recovers once the obstruction is gone`() {
        val target = indexFile()
        folder.newFolder("history")
        target.writeText("old")
        val directory = target.parentFile!!
        assertTrue(directory.setWritable(false, false))
        runCatching { atomicWriteText(target, "new") }

        assertTrue(directory.setWritable(true, false))
        atomicWriteText(target, "new")

        assertEquals("new", target.readText())
        val leftovers = directory.listFiles()!!.map { it.name }.filter { it != "index.json" }
        assertEquals(emptyList<String>(), leftovers)
    }
}
