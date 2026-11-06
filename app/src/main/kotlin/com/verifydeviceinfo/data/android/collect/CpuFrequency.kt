package com.verifydeviceinfo.data.android.collect

import java.io.File

/**
 * The highest CPU frequency the kernel advertises, read from the per-core cpufreq nodes.
 *
 * Asking `cpu0` alone is wrong on any heterogeneous part: on ARM big.LITTLE and DynamIQ SoCs core 0
 * belongs to the LITTLE cluster, so `cpu0/cpufreq/cpuinfo_max_freq` is that cluster's ceiling — on a
 * 1.8 + 2.4 + 2.84 GHz part it reports 1.80 GHz for a chip that runs at 2.84 GHz. Every present core
 * is scanned and the largest value wins, which is the number the old lookup produced on a
 * homogeneous part and the correct one on a heterogeneous part.
 *
 * Cores whose `cpufreq` node is absent (an offline core, or a kernel that does not publish scaling
 * for it) are skipped rather than failing the whole read, and a value is only accepted when it is a
 * positive number — some kernels publish `0` or an empty file for a core they have not brought up.
 */
internal object CpuFrequency {

    /** Where a stock Android kernel publishes per-core frequency scaling. */
    private const val CPU_ROOT = "/sys/devices/system/cpu"

    private const val MAX_FREQ_NODE = "cpufreq/cpuinfo_max_freq"

    private val CPU_DIR = Regex("cpu[0-9]+")

    /**
     * Highest `cpuinfo_max_freq` in kHz across every `cpuN` directory under [root], or null when
     * none of them publishes a usable value.
     */
    fun maxFreqKhz(root: File = File(CPU_ROOT)): Long? {
        val cores = try {
            root.listFiles()
        } catch (e: Exception) {
            null
        } ?: return null

        var highest: Long? = null
        for (core in cores) {
            if (!core.isDirectory || !CPU_DIR.matches(core.name)) continue
            val khz = readMaxFreqKhz(File(core, MAX_FREQ_NODE)) ?: continue
            if (highest == null || khz > highest) highest = khz
        }
        return highest
    }

    /** One core's node. Missing, unreadable, blank, zero and non-numeric all mean "no value". */
    private fun readMaxFreqKhz(node: File): Long? = try {
        if (node.isFile && node.canRead()) node.readText().trim().toLongOrNull()?.takeIf { it > 0 } else null
    } catch (e: Exception) {
        null
    }
}
