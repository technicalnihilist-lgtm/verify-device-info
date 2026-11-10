package com.verifydeviceinfo

import com.verifydeviceinfo.core.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins [Formatters.hexColon] to the exact string the Security screen used to show.
 *
 * The APK signing certificate digest is a value the user reads and compares, so the hand-rolled
 * hex encoding has to be byte-identical to the `joinToString(":") { "%02X".format(it) }` it
 * replaced — including for negative bytes, where `%02X` formats the *unsigned* 8-bit value and a
 * sign-extending implementation would print `FFFFFF80` instead of `80`.
 */
class FormattersHexTest {

    /** The previous implementation, kept verbatim as the oracle for the sweeps below. */
    private fun oracle(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it) }

    @Test
    fun everyByteValueFormatsLikeTheStringFormatItReplaced() {
        for (value in 0..255) {
            val single = byteArrayOf(value.toByte())
            assertEquals("byte $value", oracle(single), Formatters.hexColon(single))
        }
    }

    @Test
    fun aDigestSizedArrayMatchesTheOracle() {
        val random = Random(20261110)
        repeat(200) {
            val bytes = ByteArray(32) { random.nextInt(256).toByte() }
            assertEquals(oracle(bytes), Formatters.hexColon(bytes))
        }
    }

    @Test
    fun otherLengthsMatchTheOracle() {
        val random = Random(7)
        for (length in listOf(1, 2, 15, 31, 33, 64, 65)) {
            val bytes = ByteArray(length) { random.nextInt(256).toByte() }
            assertEquals("length $length", oracle(bytes), Formatters.hexColon(bytes))
        }
    }

    @Test
    fun anEmptyArrayFormatsAsAnEmptyString() {
        assertEquals("", Formatters.hexColon(ByteArray(0)))
        assertEquals(oracle(ByteArray(0)), Formatters.hexColon(ByteArray(0)))
    }

    @Test
    fun aDigestHasThirtyTwoUppercaseGroupsSeparatedByColons() {
        val digest = ByteArray(32) { (it * 7 + 0x80).toByte() }
        val text = Formatters.hexColon(digest)
        assertEquals(32 * 3 - 1, text.length)
        val groups = text.split(':')
        assertEquals(32, groups.size)
        assertTrue(groups.all { it.length == 2 && it.all { char -> char in "0123456789ABCDEF" } })
        // No leading or trailing separator, and no empty group in the middle.
        assertTrue(text.first() != ':' && text.last() != ':')
    }

    @Test
    fun theHighestAndLowestByteAreTwoDigitsEach() {
        assertEquals("00", Formatters.hexColon(byteArrayOf(0)))
        assertEquals("7F", Formatters.hexColon(byteArrayOf(Byte.MAX_VALUE)))
        assertEquals("80", Formatters.hexColon(byteArrayOf(Byte.MIN_VALUE)))
        assertEquals("FF", Formatters.hexColon(byteArrayOf(-1)))
    }
}
