package com.verifydeviceinfo

import com.verifydeviceinfo.core.util.Formatters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins the strings [Formatters.date], [Formatters.dateOnly] and [Formatters.utcDate] render, and
 * the two properties the per-thread formatter cache has to keep: it must follow a change of the
 * JVM default time zone, and it must never hand one thread another thread's date.
 *
 * Every expected string below was produced by the previous implementation (a fresh
 * `SimpleDateFormat` per call) — the cache is a pure performance change, so any difference here
 * is a regression.
 */
class FormattersDateTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun fixDefaultZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun dateRendersPinnedStrings() {
        assertEquals("15 Nov 2023, 03:43", Formatters.date(1_700_000_000_000L))
        assertEquals("13 Sep 2020, 17:56", Formatters.date(1_600_000_000_000L))
        assertEquals("01 Jan 2021, 05:30", Formatters.date(1_609_459_200_000L))
        assertEquals("14 Dec 2022, 12:10", Formatters.date(1_671_000_000_000L))
    }

    @Test
    fun dateOnlyRendersPinnedStrings() {
        assertEquals("15 Nov 2023", Formatters.dateOnly(1_700_000_000_000L))
        assertEquals("13 Sep 2020", Formatters.dateOnly(1_600_000_000_000L))
        assertEquals("01 Jan 2021", Formatters.dateOnly(1_609_459_200_000L))
        assertEquals("14 Dec 2022", Formatters.dateOnly(1_671_000_000_000L))
    }

    @Test
    fun utcDateRendersPinnedStrings() {
        assertEquals("14 Nov 2023 22:13 UTC", Formatters.utcDate(1_700_000_000_000L))
        assertEquals("13 Sep 2020 12:26 UTC", Formatters.utcDate(1_600_000_000_000L))
        assertEquals("01 Jan 2021 00:00 UTC", Formatters.utcDate(1_609_459_200_000L))
        assertEquals("14 Dec 2022 06:40 UTC", Formatters.utcDate(1_671_000_000_000L))
    }

    @Test
    fun utcDateIgnoresTheDefaultZone() {
        val expected = "14 Nov 2023 22:13 UTC"
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        assertEquals(expected, Formatters.utcDate(1_700_000_000_000L))
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals(expected, Formatters.utcDate(1_700_000_000_000L))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals(expected, Formatters.utcDate(1_700_000_000_000L))
    }

    @Test
    fun missingTimestampsAreUnavailable() {
        for (millis in listOf(null, 0L, -1L, Long.MIN_VALUE)) {
            assertEquals("Unavailable", Formatters.date(millis))
            assertEquals("Unavailable", Formatters.dateOnly(millis))
            assertEquals("Unavailable", Formatters.utcDate(millis))
        }
    }

    /**
     * A cached formatter freezes the zone it was constructed with, so this is the case a naive
     * cache breaks: the user travels, or changes the zone in Settings, and every date on screen
     * keeps rendering in the old zone until the process restarts.
     */
    @Test
    fun localDatesFollowAChangeOfDefaultZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        assertEquals("15 Nov 2023, 03:43", Formatters.date(1_700_000_000_000L))
        assertEquals("15 Nov 2023", Formatters.dateOnly(1_700_000_000_000L))

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals("14 Nov 2023, 17:13", Formatters.date(1_700_000_000_000L))
        assertEquals("14 Nov 2023", Formatters.dateOnly(1_700_000_000_000L))

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("14 Nov 2023, 22:13", Formatters.date(1_700_000_000_000L))
        assertEquals("14 Nov 2023", Formatters.dateOnly(1_700_000_000_000L))
    }

    /** A reused formatter must not carry the previous call's date into the next one. */
    @Test
    fun consecutiveCallsDoNotLeakState() {
        val first = Formatters.date(1_600_000_000_000L)
        val other = Formatters.date(1_700_000_000_000L)
        assertNotEquals(first, other)
        assertEquals(first, Formatters.date(1_600_000_000_000L))
        assertEquals(other, Formatters.date(1_700_000_000_000L))
        assertEquals("13 Sep 2020", Formatters.dateOnly(1_600_000_000_000L))
        assertEquals("15 Nov 2023", Formatters.dateOnly(1_700_000_000_000L))
        assertEquals("13 Sep 2020", Formatters.dateOnly(1_600_000_000_000L))
    }

    /**
     * [java.text.SimpleDateFormat] is not thread-safe, so the cache is per thread. Eight threads
     * format their own timestamp at the same moment, repeatedly; a shared formatter shows up here
     * as a thread reading back another thread's date.
     */
    @Test
    fun concurrentCallsReturnTheirOwnTimestamp() {
        val threadCount = 8
        val iterations = 2_000
        val timestamps = LongArray(threadCount) { 1_600_000_000_000L + it * 86_400_000L * 13L }
        val expected = Array(threadCount) { i ->
            Triple(
                Formatters.date(timestamps[i]),
                Formatters.dateOnly(timestamps[i]),
                Formatters.utcDate(timestamps[i]),
            )
        }
        assertEquals(threadCount, expected.map { it.first }.toSet().size)

        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val failure = AtomicReference<String?>(null)
        val threads = (0 until threadCount).map { index ->
            Thread {
                try {
                    start.await()
                    repeat(iterations) {
                        val actual = Triple(
                            Formatters.date(timestamps[index]),
                            Formatters.dateOnly(timestamps[index]),
                            Formatters.utcDate(timestamps[index]),
                        )
                        if (actual != expected[index]) {
                            failure.compareAndSet(
                                null,
                                "thread $index got $actual, expected ${expected[index]}",
                            )
                            return@Thread
                        }
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, "thread $index threw $error")
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true; name = "formatters-test-$index" }
        }
        threads.forEach { it.start() }
        start.countDown()
        assertTrue("worker threads did not finish", done.await(60, TimeUnit.SECONDS))
        threads.forEach { it.join() }
        assertNull(failure.get())
    }

    /** The rendered month name must not follow the device locale, only `Locale.US`. */
    @Test
    fun monthNamesAreLocaleIndependent() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("15 Nov 2023, 03:43", Formatters.date(1_700_000_000_000L))
            assertEquals("14 Nov 2023 22:13 UTC", Formatters.utcDate(1_700_000_000_000L))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
