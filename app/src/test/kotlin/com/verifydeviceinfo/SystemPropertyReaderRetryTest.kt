package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.SystemPropertyReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `getprop` attempt that produced nothing must not be cached as the permanent answer.
 *
 * `AppContainer` builds one [SystemPropertyReader] for the whole process, so the old behaviour —
 * marking the dump "loaded" before running the loader — meant that one transient failure (a
 * `getprop` killed at its deadline, or a loader that threw) left every later property read on the
 * reflection fallback for the life of the app. These tests pin the retry, and the cap that keeps a
 * permanently broken `getprop` from being re-run on every property read.
 */
class SystemPropertyReaderRetryTest {

    private val dump = """
        [ro.product.model]: [Pixel 7]
        [ro.product.brand]: [google]
    """.trimIndent()

    private fun parsed() = SystemPropertyReader.parseDump(dump.lineSequence())

    @Test
    fun aThrowingLoaderIsRetriedOnALaterRead() {
        var loads = 0
        val reader = SystemPropertyReader {
            loads++
            if (loads == 1) throw IllegalStateException("getprop unavailable") else parsed()
        }

        assertNull("the first attempt failed, so the first read has nothing", reader.get("ro.product.model"))
        assertEquals("the dump must be retried after a failed attempt", "Pixel 7", reader.get("ro.product.model"))
        assertEquals(2, loads)
    }

    @Test
    fun anEmptyDumpIsRetriedOnALaterRead() {
        var loads = 0
        val reader = SystemPropertyReader {
            loads++
            if (loads == 1) emptyMap() else parsed()
        }

        assertTrue("nothing parsed, so nothing to report yet", reader.all().isEmpty())
        assertEquals("google", reader.get("ro.product.brand"))
        assertEquals(2, loads)
    }

    @Test
    fun aPermanentlyBrokenLoaderIsTriedAtMostTheCapAndNoMore() {
        var loads = 0
        val reader = SystemPropertyReader {
            loads++
            throw IllegalStateException("getprop unavailable")
        }

        repeat(10) { reader.get("ro.product.model") }

        assertEquals(
            "a getprop that never answers must not cost a deadline on every property read",
            SystemPropertyReader.MAX_LOAD_ATTEMPTS,
            loads
        )
    }

    @Test
    fun aSuccessfulDumpIsStillParsedExactlyOnce() {
        var loads = 0
        val reader = SystemPropertyReader {
            loads++
            parsed()
        }

        repeat(5) { reader.get("ro.product.model") }
        reader.get("ro.product.brand")
        reader.getInt("ro.product.model")
        reader.getBooleanFlag("ro.product.brand")
        reader.all()

        assertEquals("a dump that arrived must not be re-read", 1, loads)
    }

    @Test
    fun recoveryIsVisibleThroughEveryAccessor() {
        var loads = 0
        val reader = SystemPropertyReader {
            loads++
            if (loads == 1) emptyMap() else parsed()
        }

        reader.get("ro.product.model")
        assertEquals("Pixel 7", reader.get("ro.product.model"))
        assertEquals("the recovered dump is cached like any other", 2, reader.all().size)
        assertEquals("further reads reuse the recovered dump", 2, loads)
    }
}
