package com.verifydeviceinfo.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Formatters {

    private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    private const val BYTES_PER_GIB = 1L shl 30

    /**
     * Human-readable byte count, e.g. `1.50 KB`.
     *
     * Formatted with integer arithmetic rather than `String.format`: the latter allocates a
     * [java.util.Formatter], a `StringBuilder` and a `String[]` per call, and this runs once per
     * memory/storage row on every scan and list render. Rounding is half-up on the exact
     * quotient, which is what `%.2f` did, and it cannot differ because a quotient of the form
     * `value / 1024^n` never lands exactly on a `.005` boundary.
     */
    fun bytes(value: Long): String {
        if (value <= 0L) return "Unavailable"
        var unit = 0
        var divisor = 1L
        while (unit < BYTE_UNITS.lastIndex && value >= (divisor shl 10)) {
            divisor = divisor shl 10
            unit++
        }
        val whole = value / divisor
        val remainder = value % divisor
        var hundredths = whole * 100L
        if (divisor != 1L) {
            hundredths += (remainder * 100L + (divisor shr 1)) / divisor
        }
        val integerPart = hundredths / 100L
        val fraction = hundredths % 100L
        val builder = StringBuilder(12)
        builder.append(integerPart).append('.')
        if (fraction < 10L) builder.append('0')
        builder.append(fraction).append(' ').append(BYTE_UNITS[unit])
        return builder.toString()
    }

    /** Gigabyte count with one decimal, e.g. `7.4 GB`. Same half-up rounding as `%.1f`. */
    fun bytesGigabytes(value: Long): String {
        val whole = value / BYTES_PER_GIB
        val remainder = value % BYTES_PER_GIB
        // Work on magnitudes: Long.MIN_VALUE has no positive counterpart, but its quotient and
        // remainder both fit, so negating them is always safe.
        val wholeMagnitude = if (whole < 0L) -whole else whole
        val remainderMagnitude = if (remainder < 0L) -remainder else remainder
        val tenths = wholeMagnitude * 10L +
            (remainderMagnitude * 10L + (BYTES_PER_GIB shr 1)) / BYTES_PER_GIB
        val builder = StringBuilder(14)
        if (value < 0L) builder.append('-')
        builder.append(tenths / 10L).append('.').append(tenths % 10L).append(" GB")
        return builder.toString()
    }

    fun date(millis: Long?): String {
        if (millis == null || millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
        return fmt.format(Date(millis))
    }

    fun dateOnly(millis: Long?): String {
        if (millis == null || millis <= 0L) return "Unavailable"
        return SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(millis))
    }

    fun utcDate(millis: Long?): String {
        if (millis == null || millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm 'UTC'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }

    fun duration(days: Long?): String {
        if (days == null || days < 0) return "Unknown"
        val years = days / 365
        val months = (days % 365) / 30
        val remainingDays = days % 30
        return buildString {
            if (years > 0) append("$years y ")
            if (months > 0) append("$months mo ")
            if (remainingDays > 0 || (years == 0L && months == 0L)) append("$remainingDays d")
        }.trim()
    }

    fun uptime(millis: Long): String {
        val totalMinutes = millis / 60_000
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            append("${minutes}m")
        }
    }

    fun daysBetween(fromMillis: Long, toMillis: Long): Long =
        ((toMillis - fromMillis) / 86_400_000L).coerceAtLeast(0L)
}
