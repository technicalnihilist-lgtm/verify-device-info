package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.atomicWriteText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Two writers of the same file must not be able to spoil it.
 *
 * `atomicWriteText` writes a sibling temporary file and renames it over the target, so a reader
 * sees the complete old contents or the complete new ones. That promise only held for one writer
 * at a time: the temporary file's name was derived from the target's (`<name>.tmp`), so a second
 * writer of the same target opened *the same* temporary file. Whichever writer renamed first moved
 * the inode out from under the other, and the other kept writing through its open descriptor into
 * what was now the finished target — appending its remaining bytes past the end of a complete
 * document — and then failed its own rename with "could not replace". The export a user shared was
 * then a truncated document with a second document's tail stuck on it.
 *
 * This is reachable from the app: `AppViewModel.exportJson` launches a coroutine per tap and both
 * taps write the same cache path, so a double tap on "Export JSON" is two writers of one file.
 *
 * The writers below are released together by a barrier and each writes a large, distinct payload,
 * so the overlap is real rather than theoretical. A pass means every writer finished, no writer
 * threw, the target is exactly one writer's payload end to end, and no temporary file was left
 * behind.
 */
class AtomicWriteConcurrencyTest {

    private lateinit var directory: File

    private val target: File
        get() = File(directory, "verify-device-info-report.json")

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("atomic-write").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun concurrentWritersOfOneFileNeverInterleave() {
        val writers = 6
        val payloads = (0 until writers).map { index ->
            // Distinct first and last characters, so an interleaved file cannot match any of them
            // and a truncated one cannot either.
            val body = "${('a' + index)}".repeat(PAYLOAD_CHARS)
            "{\"writer\":$index,\"body\":\"$body\",\"end\":\"${('A' + index)}\"}"
        }
        repeat(ROUNDS) { round ->
            target.delete()
            directory.listFiles()?.forEach { it.delete() }

            val barrier = CyclicBarrier(writers)
            val failures = CopyOnWriteArrayList<Throwable>()
            val threads = payloads.map { payload ->
                Thread {
                    try {
                        barrier.await(30, TimeUnit.SECONDS)
                        atomicWriteText(target, payload)
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(60_000) }

            assertTrue(
                "round $round: a writer of the same file failed: ${failures.firstOrNull()}",
                failures.isEmpty()
            )
            val written = target.readText()
            assertTrue(
                "round $round: the target is not any writer's payload — ${written.length} chars, " +
                    "interleaved or truncated",
                payloads.any { it == written }
            )
            val leftovers = directory.listFiles()?.map { it.name }.orEmpty().filter { it != target.name }
            assertEquals("round $round: a temporary file was left behind: $leftovers", emptyList<String>(), leftovers)
        }
    }

    @Test
    fun aSingleWriterStillProducesExactlyItsBytes() {
        val payload = "{\"only\":\"writer\"}"
        atomicWriteText(target, payload)
        assertEquals(payload, target.readText())
        assertNull(
            "no temporary file may remain beside the target",
            directory.listFiles()?.firstOrNull { it.name != target.name }
        )
    }

    private companion object {
        const val PAYLOAD_CHARS = 200_000
        const val ROUNDS = 10
    }
}
