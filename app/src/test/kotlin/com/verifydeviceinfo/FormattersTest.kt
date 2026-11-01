package com.verifydeviceinfo

import com.verifydeviceinfo.core.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * Pins the exact strings [Formatters.bytes] and [Formatters.bytesGigabytes] produced before the
 * `String.format` removal, so the hand-rolled formatting cannot drift from `%.2f` / `%.1f`.
 */
class FormattersTest {

    /** The previous implementation, kept verbatim as the oracle for the sweep below. */
    private fun referenceBytes(value: Long): String {
        if (value <= 0L) return "Unavailable"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024.0 && unit < units.lastIndex) {
            size /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.2f %s", size, units[unit])
    }

    private fun referenceGigabytes(value: Long): String =
        String.format(Locale.US, "%.1f GB", value / 1_073_741_824.0)

    @Test
    fun bytesFormatsPinnedStrings() {
        assertEquals("Unavailable", Formatters.bytes(0L))
        assertEquals("Unavailable", Formatters.bytes(-1L))
        assertEquals("1.00 B", Formatters.bytes(1L))
        assertEquals("1023.00 B", Formatters.bytes(1023L))
        assertEquals("1.00 KB", Formatters.bytes(1024L))
        assertEquals("1.50 KB", Formatters.bytes(1536L))
        assertEquals("1024.00 KB", Formatters.bytes(1_048_575L))
        assertEquals("1.00 MB", Formatters.bytes(1_048_576L))
        assertEquals("1.00 GB", Formatters.bytes(1_073_741_824L))
        assertEquals("1.00 TB", Formatters.bytes(1_099_511_627_776L))
        assertEquals("8388608.00 TB", Formatters.bytes(Long.MAX_VALUE))
        assertEquals("3.98 GB", Formatters.bytes(4_276_000_000L))
    }

    @Test
    fun bytesGigabytesFormatsPinnedStrings() {
        assertEquals("0.0 GB", Formatters.bytesGigabytes(0L))
        assertEquals("0.5 GB", Formatters.bytesGigabytes(536_870_912L))
        assertEquals("1.0 GB", Formatters.bytesGigabytes(1_073_741_824L))
        assertEquals("1.3 GB", Formatters.bytesGigabytes(1_342_177_280L))
        assertEquals("1.5 GB", Formatters.bytesGigabytes(1_610_612_736L))
        assertEquals("-1.5 GB", Formatters.bytesGigabytes(-1_610_612_736L))
        assertEquals("-8589934592.0 GB", Formatters.bytesGigabytes(Long.MIN_VALUE))
    }

    @Test
    fun bytesMatchesStringFormat() {
        for (value in 0L..200_000L) {
            assertEquals("bytes($value)", referenceBytes(value), Formatters.bytes(value))
        }
        for (power in 0..4) {
            val base = 1L shl (10 * power)
            for (delta in -2_000L..2_000L) {
                val value = base + delta
                if (value < 0L) continue
                assertEquals("bytes($value)", referenceBytes(value), Formatters.bytes(value))
            }
        }
        val random = Random(7)
        repeat(20_000) {
            val value = random.nextLong(1L, Long.MAX_VALUE)
            assertEquals("bytes($value)", referenceBytes(value), Formatters.bytes(value))
        }
    }

    @Test
    fun bytesGigabytesMatchesStringFormat() {
        for (value in -50_000L..200_000L) {
            assertEquals("gb($value)", referenceGigabytes(value), Formatters.bytesGigabytes(value))
        }
        for (power in 0..40) {
            val base = 1L shl power
            for (delta in -300L..300L) {
                val value = base + delta
                assertEquals("gb($value)", referenceGigabytes(value), Formatters.bytesGigabytes(value))
            }
        }
        val random = Random(11)
        repeat(20_000) {
            val value = random.nextLong()
            assertEquals("gb($value)", referenceGigabytes(value), Formatters.bytesGigabytes(value))
        }
    }
}
