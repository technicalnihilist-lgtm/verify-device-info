package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.collect.CpuFrequency
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The reported "Maximum CPU frequency" must be the part's ceiling, not core 0's.
 *
 * Every case is a fixture directory shaped like `/sys/devices/system/cpu`, so nothing here depends
 * on the host kernel or on a device.
 */
class CpuFrequencyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder("cpu")

    /** Creates `<root>/<core>/cpufreq/cpuinfo_max_freq` with [content]; null omits the file. */
    private fun core(root: File, core: String, content: String?) {
        val dir = File(root, core)
        dir.mkdirs()
        val cpufreq = File(dir, "cpufreq")
        if (content == null) {
            cpufreq.mkdirs()
            return
        }
        cpufreq.mkdirs()
        File(cpufreq, "cpuinfo_max_freq").writeText(content)
    }

    @Test
    fun homogeneousPartReportsItsOnlyCluster() {
        val root = root()
        core(root, "cpu0", "1800000\n")
        core(root, "cpu1", "1800000\n")

        assertEquals(1_800_000L, CpuFrequency.maxFreqKhz(root))
    }

    @Test
    fun bigLittlePartReportsThePrimeCoreNotCpu0() {
        // The regression this pins: the old cpu0-only lookup answered 1.80 GHz here.
        val root = root()
        core(root, "cpu0", "1800000\n")
        core(root, "cpu3", "1800000\n")
        core(root, "cpu4", "2400000\n")
        core(root, "cpu7", "2841600\n")

        assertEquals(2_841_600L, CpuFrequency.maxFreqKhz(root))
    }

    @Test
    fun maximumIsIndependentOfDirectoryOrder() {
        val root = root()
        core(root, "cpu7", "2841600\n")
        core(root, "cpu4", "2400000\n")
        core(root, "cpu0", "1800000\n")

        assertEquals(2_841_600L, CpuFrequency.maxFreqKhz(root))
    }

    @Test
    fun offlineCoreWithoutCpufreqDoesNotDiscardTheOthers() {
        val root = root()
        core(root, "cpu0", "1800000\n")
        core(root, "cpu1", null) // present but not brought up: no cpufreq node at all

        assertEquals(1_800_000L, CpuFrequency.maxFreqKhz(root))
    }

    @Test
    fun blankZeroAndNonNumericValuesAreIgnored() {
        val root = root()
        core(root, "cpu0", "   \n")
        core(root, "cpu1", "0\n")
        core(root, "cpu2", "not-a-frequency\n")
        core(root, "cpu3", "2016000\n")

        assertEquals(2_016_000L, CpuFrequency.maxFreqKhz(root))
    }

    @Test
    fun noUsableValueAnywhereIsNull() {
        val root = root()
        core(root, "cpu0", "\n")
        core(root, "cpu1", "0\n")

        assertNull(CpuFrequency.maxFreqKhz(root))
    }

    @Test
    fun missingRootIsNull() {
        assertNull(CpuFrequency.maxFreqKhz(File(root(), "absent")))
    }

    @Test
    fun nonCoreEntriesAreIgnored() {
        // A real cpu root also holds cpufreq, cpuidle, cpuonline and friends.
        val root = root()
        File(root, "cpufreq").mkdirs()
        File(root, "cpuidle").mkdirs()
        File(root, "cpuonline").writeText("0-7\n")
        File(root, "online").writeText("0-7\n")
        core(root, "cpu0", "1800000\n")

        assertEquals(1_800_000L, CpuFrequency.maxFreqKhz(root))
    }

    @Test
    fun aDirectoryNamedLikeTheNodeIsNotAValue() {
        val root = root()
        val dir = File(root, "cpu0")
        File(dir, "cpufreq/cpuinfo_max_freq").mkdirs()

        assertNull(CpuFrequency.maxFreqKhz(root))
    }
}
