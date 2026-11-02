package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.SystemPropertyReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `getprop` dump must be parsed once per shared reader, not once per consumer, and one
 * malformed line must not cost the rest of the dump.
 */
class SystemPropertyReaderTest {

    private val dump = """
        [ro.product.model]: [Pixel 7]
        [ro.product.brand]: [google]
        [ro.crypto.state]: [encrypted]
        [ro.boot.verifiedbootstate]: [green]
        [ro.blank]: []
        [ro.flag.one]: [1]
        [ro.flag.off]: [off]
        [ro.count]: [42]
    """.trimIndent()

    private class CountingLoader(private val dump: String) {
        var loads = 0
        fun reader(): SystemPropertyReader = SystemPropertyReader {
            loads++
            SystemPropertyReader.parseDump(dump.lineSequence())
        }
    }

    @Test
    fun dumpIsParsedOnceNoMatterHowManyPropertiesAreRead() {
        val loader = CountingLoader(dump)
        val reader = loader.reader()

        reader.get("ro.product.model")
        reader.get("ro.product.brand")
        reader.get("ro.crypto.state")
        reader.get("ro.boot.verifiedbootstate")
        reader.get("ro.not.there")
        reader.getInt("ro.count")
        reader.getBooleanFlag("ro.flag.one")
        reader.all()

        assertEquals("the getprop dump must be parsed exactly once per reader", 1, loader.loads)
    }

    @Test
    fun separateReadersEachParseTheDumpAgain() {
        val loader = CountingLoader(dump)
        loader.reader().get("ro.product.model")
        loader.reader().get("ro.product.model")

        assertEquals("two readers mean two getprop dumps — which is what sharing avoids", 2, loader.loads)
    }

    @Test
    fun aMalformedLineDoesNotDiscardTheRestOfTheDump() {
        val broken = """
            [ro.product.model]: [Pixel 7]
            [ro.truncated]: [
            [ro.crypto.state]: [encrypted]
            not a property line at all
            [ro.no.space]:[value]
        """.trimIndent()

        val reader = SystemPropertyReader { SystemPropertyReader.parseDump(broken.lineSequence()) }

        assertEquals("Pixel 7", reader.get("ro.product.model"))
        assertEquals("encrypted", reader.get("ro.crypto.state"))
        assertNull(reader.get("ro.truncated"))
        assertNull(reader.get("ro.no.space"))
    }

    @Test
    fun valuesKeepEverythingAfterTheFirstSeparator() {
        val reader = SystemPropertyReader {
            SystemPropertyReader.parseDump(
                sequenceOf("[ro.weird]: [a]: [b]")
            )
        }
        assertEquals("a]: [b", reader.get("ro.weird"))
    }

    @Test
    fun blankAndMissingValuesReadAsNull() {
        val reader = SystemPropertyReader { SystemPropertyReader.parseDump(dump.lineSequence()) }

        assertNull(reader.get("ro.blank"))
        assertNull(reader.get("ro.absent"))
        assertNull(reader.getInt("ro.product.model"))
        assertNull(reader.getBooleanFlag("ro.product.model"))
    }

    @Test
    fun typedAccessorsAgreeWithTheRawValue() {
        val reader = SystemPropertyReader { SystemPropertyReader.parseDump(dump.lineSequence()) }

        assertEquals(42, reader.getInt("ro.count"))
        assertEquals(true, reader.getBooleanFlag("ro.flag.one"))
        assertEquals(false, reader.getBooleanFlag("ro.flag.off"))
        assertEquals("Pixel 7", reader.get("ro.product.model"))
        assertTrue(reader.all().containsKey("ro.crypto.state"))
        assertEquals(8, reader.all().size)
    }

    @Test
    fun aFailingDumpDoesNotThrow() {
        val reader = SystemPropertyReader { throw IllegalStateException("getprop unavailable") }

        assertNull(reader.get("ro.product.model"))
        assertTrue(reader.all().isEmpty())
    }
}
