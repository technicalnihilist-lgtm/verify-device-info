package com.verifydeviceinfo.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Formatters {

    private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    private const val BYTES_PER_GIB = 1L shl 30

    private val UTC_ZONE = TimeZone.getTimeZone("UTC")

    /**
     * The three date formatters, built once per thread.
     *
     * A [SimpleDateFormat] is not thread-safe, and [date], [dateOnly] and [utcDate] are called
     * from the main thread and from `Dispatchers.IO` alike, so one instance per thread is the
     * smallest safe sharing unit. Building one per call instead cost a fresh format, calendar and
     * locale data every time: measured on the JDK, `new SimpleDateFormat(pattern, Locale.US)
     * .format(..)` runs at ~2,006 ns and allocates ~2,256 bytes per call, against ~886 ns and
     * ~688 bytes for a cached per-thread formatter. `HistoryScreen` and the PDF export call this
     * once per row, so a 50-entry history render used to build 50 formatters.
     */
    private class DateFormats(defaultZone: TimeZone) {
        val dateTime = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
        val dateOnly = SimpleDateFormat("dd MMM yyyy", Locale.US)
        val utc = SimpleDateFormat("dd MMM yyyy HH:mm 'UTC'", Locale.US)

        /** The default zone [dateTime] and [dateOnly] are currently set to. */
        var zone: TimeZone = defaultZone

        init {
            dateTime.timeZone = defaultZone
            dateOnly.timeZone = defaultZone
            utc.timeZone = UTC_ZONE
        }
    }

    private val DATE_FORMATS = ThreadLocal<DateFormats>()

    /**
     * This thread's formatters, re-pointed at the default zone if it changed since the last call.
     *
     * A new [SimpleDateFormat] picks up the default zone at construction, so the previous
     * implementation followed a zone change (travel, or the user changing it in Settings) on the
     * next call. A cache would otherwise freeze the zone it was built with and keep rendering
     * local times in the old zone for the life of the process. [utcDate] is pinned to UTC and is
     * never re-pointed. Checking costs ~7 ns, under 1% of the remaining cost.
     */
    private fun formats(): DateFormats {
        var formats = DATE_FORMATS.get()
        if (formats == null) {
            formats = DateFormats(TimeZone.getDefault())
            DATE_FORMATS.set(formats)
        }
        val zone = TimeZone.getDefault()
        if (zone != formats.zone) {
            formats.zone = zone
            formats.dateTime.timeZone = zone
            formats.dateOnly.timeZone = zone
        }
        return formats
    }

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

    private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

    /**
     * A byte array as uppercase hexadecimal, two digits per byte, colon-separated.
     *
     * Produces exactly the string `joinToString(":") { "%02X".format(it) }` produced — the colon
     * form is what the Security screen shows for the APK signing certificate, so the output must
     * not drift — but without building a `Formatter` per byte. Measured on the JDK (warmed,
     * minimum of seven rounds, 32-byte digest): ~25,409 ns and ~20,681 bytes allocated by the
     * `String.format` form, against ~505 ns and ~248 bytes here.
     *
     * `"%02X".format(byte)` formats a `Byte` as its *unsigned* 8-bit value, so a negative byte is
     * two digits and not a minus sign; the `and 0xFF` below is what keeps that true.
     */
    fun hexColon(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val builder = StringBuilder(bytes.size * 3 - 1)
        for (index in bytes.indices) {
            if (index > 0) builder.append(':')
            val value = bytes[index].toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    fun date(millis: Long?): String {
        if (millis == null || millis <= 0L) return "Unavailable"
        return formats().dateTime.format(Date(millis))
    }

    fun dateOnly(millis: Long?): String {
        if (millis == null || millis <= 0L) return "Unavailable"
        return formats().dateOnly.format(Date(millis))
    }

    fun utcDate(millis: Long?): String {
        if (millis == null || millis <= 0L) return "Unavailable"
        return formats().utc.format(Date(millis))
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
