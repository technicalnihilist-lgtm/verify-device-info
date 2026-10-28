package com.verifydeviceinfo.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Formatters {

    fun bytes(value: Long): String {
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

    fun bytesGigabytes(value: Long): String = String.format(Locale.US, "%.1f GB", value / 1_073_741_824.0)

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
