package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.SystemPropertyReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The `getprop` dump must not be able to block its caller for the life of the app.
 *
 * These drive the real process path against `sh` standing in for a `getprop` that stops answering:
 * a `getprop` that never closes stdout used to leave both the calling thread and the child process
 * alive forever, one pair leaked per abandoned scan. A plain JUnit test, not Robolectric — nothing
 * here touches the Android framework, and spawning a process is what is under test.
 */
class SystemPropertyReaderDeadlineTest {

    private fun elapsedMillis(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun aDumpThatFinishesInsideTheDeadlineKeepsEveryLine() {
        var parsed = emptyMap<String, String>()
        elapsedMillis {
            parsed = SystemPropertyReader.readProcessDump(
                listOf("sh", "-c", "printf '[ro.a]: [1]\\n[ro.b]: [2]\\n[ro.c]: [3]\\n'"),
                deadlineMillis = 10_000L
            )
        }

        assertEquals(mapOf("ro.a" to "1", "ro.b" to "2", "ro.c" to "3"), parsed)
    }

    @Test
    fun aDumpThatNeverClosesIsCutAtTheDeadline() {
        var parsed = emptyMap<String, String>()
        val elapsed = elapsedMillis {
            parsed = SystemPropertyReader.readProcessDump(
                listOf("sh", "-c", "printf '[ro.first]: [1]\\n'; exec sleep 30"),
                deadlineMillis = 400L
            )
        }

        assertEquals(
            "the line written before the deadline is kept; the process is not waited out",
            mapOf("ro.first" to "1"),
            parsed
        )
        assertTrue("the read must return at the deadline, not after the process ends: $elapsed ms", elapsed < 5_000L)
    }

    @Test
    fun aDumpWithNoOutputAtAllReturnsAnEmptyMapAtTheDeadline() {
        var parsed = mapOf("unexpected" to "value")
        val elapsed = elapsedMillis {
            parsed = SystemPropertyReader.readProcessDump(
                listOf("sh", "-c", "exec sleep 30"),
                deadlineMillis = 300L
            )
        }

        assertTrue("a silent process must not be reported as a dump: $parsed", parsed.isEmpty())
        assertTrue("the read must return at the deadline: $elapsed ms", elapsed < 5_000L)
    }

    @Test
    fun theStalledProcessIsGoneWhenTheCallReturns() {
        val pidFile = File(Files.createTempDirectory("getprop-deadline").toFile(), "pid")
        // `exec` keeps the shell's pid, so the pid written here is the process that must be killed.
        val script = "echo " + "\$\$" + " > '" + pidFile.absolutePath + "'; exec sleep 30"

        SystemPropertyReader.readProcessDump(listOf("sh", "-c", script), deadlineMillis = 400L)

        assertTrue("the stub process never started", pidFile.isFile)
        val pid = pidFile.readText().trim().toLong()
        val procEntry = File("/proc/$pid")
        assumeTrue("/proc is needed to observe the process from the test JVM", File("/proc").isDirectory)

        // destroyForcibly signals the process and the call reaps it; give the kernel a moment.
        var alive = true
        var waited = 0L
        while (alive && waited < 3_000L) {
            alive = procEntry.exists()
            if (alive) {
                Thread.sleep(100L)
                waited += 100L
            }
        }

        assertFalse("the getprop process $pid is still running after the deadline", alive)
    }

    @Test
    fun aCommandThatCannotStartYieldsAnEmptyMap() {
        val parsed = SystemPropertyReader.readProcessDump(
            listOf("/nonexistent/getprop-for-tests"),
            deadlineMillis = 1_000L
        )

        assertTrue("a dump that cannot run must not throw or invent properties: $parsed", parsed.isEmpty())
    }

    @Test
    fun theDefaultDeadlineIsBounded() {
        assertTrue(
            "a healthy dump answers in well under a second; the ceiling must still be finite",
            SystemPropertyReader.DUMP_DEADLINE_MS in 500L..10_000L
        )
    }
}
