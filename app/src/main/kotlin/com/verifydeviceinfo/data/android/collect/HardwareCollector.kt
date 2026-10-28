package com.verifydeviceinfo.data.android.collect

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import android.view.WindowManager
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DisplayFacts
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification
import com.verifydeviceinfo.core.util.Formatters
import java.io.File

data class HardwareResult(
    val display: DisplayFacts?,
    val ramTotalBytes: Long,
    val ramAvailableBytes: Long,
    val isLowRamDevice: Boolean,
    val memoryClassMb: Int,
    val storageTotalBytes: Long,
    val storageAvailableBytes: Long,
    val cpuCores: Int,
    val cpuAbis: List<String>,
    val cpuMaxFreqKhz: Long?,
    val gpuRenderer: String?,
    val gpuVendor: String?,
    val glesVersion: String?,
    val vulkanSupported: Boolean?,
    val fields: List<SpecField>,
    val errors: List<String>
)

/** CPU, GPU, memory, storage and display — all read from public framework APIs. */
class HardwareCollector(private val context: Context) {

    fun collect(): HardwareResult {
        val fields = mutableListOf<SpecField>()
        val errors = mutableListOf<String>()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        // ---- Memory ----
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val ramTotal = memoryInfo.totalMem
        val ramAvailable = memoryInfo.availMem

        fields += FieldBuilder.value(
            "memory.totalRam", "Total RAM", Formatters.bytes(ramTotal), SpecGroup.MEMORY,
            "ActivityManager.MemoryInfo.totalMem"
        )
        fields += FieldBuilder.value(
            "memory.availableRam", "Available RAM", Formatters.bytes(ramAvailable), SpecGroup.MEMORY,
            "ActivityManager.MemoryInfo.availMem"
        )
        fields += FieldBuilder.value(
            "memory.lowRamDevice", "Low-RAM device flag",
            if (activityManager.isLowRamDevice) "Yes" else "No", SpecGroup.MEMORY,
            "ActivityManager.isLowRamDevice"
        )
        fields += FieldBuilder.value(
            "memory.memoryClass", "Per-app heap limit", "${activityManager.memoryClass} MB", SpecGroup.MEMORY,
            "ActivityManager.memoryClass"
        )
        fields += FieldBuilder.value(
            "memory.javaHeapMax", "Runtime heap maximum",
            Formatters.bytes(Runtime.getRuntime().maxMemory()), SpecGroup.MEMORY,
            "Runtime.maxMemory()", Confidence.MEDIUM
        )
        readProcMemInfo()?.let { (totalKb, availableKb) ->
            fields += FieldBuilder.value(
                "memory.procMemTotal", "/proc/meminfo MemTotal", Formatters.bytes(totalKb * 1024),
                SpecGroup.MEMORY, "/proc/meminfo", Confidence.MEDIUM,
                Verification.DIRECTLY_VERIFIED,
                note = "Cross-check against ActivityManager; small differences are normal."
            )
            fields += FieldBuilder.value(
                "memory.procMemAvailable", "/proc/meminfo MemAvailable", Formatters.bytes(availableKb * 1024),
                SpecGroup.MEMORY, "/proc/meminfo", Confidence.MEDIUM
            )
        }

        // ---- Storage ----
        var storageTotal = 0L
        var storageAvailable = 0L
        try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            storageTotal = stat.blockCountLong * stat.blockSizeLong
            storageAvailable = stat.availableBlocksLong * stat.blockSizeLong
            fields += FieldBuilder.value(
                "storage.total", "Total internal storage", Formatters.bytes(storageTotal), SpecGroup.STORAGE,
                "StatFs on the data partition",
                note = "Usable capacity is lower than the marketed capacity because of formatting and " +
                    "system partitions."
            )
            fields += FieldBuilder.value(
                "storage.available", "Available storage", Formatters.bytes(storageAvailable), SpecGroup.STORAGE,
                "StatFs on the data partition"
            )
        } catch (e: Exception) {
            errors += "Storage statistics unavailable: ${e.message}"
        }
        fields += FieldBuilder.value(
            "storage.encryptionState", "Storage encryption",
            SystemPropertySafe.get("ro.crypto.state"), SpecGroup.STORAGE, "ro.crypto.state",
            Confidence.MEDIUM
        )

        // ---- CPU ----
        val cores = Runtime.getRuntime().availableProcessors()
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val maxFreq = readCpuMaxFreq()
        fields += FieldBuilder.value(
            "cpu.cores", "CPU cores", cores.toString(), SpecGroup.PROCESSOR, "Runtime.availableProcessors()"
        )
        fields += FieldBuilder.value(
            "cpu.abis", "Supported ABIs", abis.joinToString(", "), SpecGroup.PROCESSOR,
            "Build.SUPPORTED_ABIS"
        )
        fields += FieldBuilder.value(
            "cpu.bitness", "Architecture",
            when {
                abis.any { it.contains("64") } -> "64-bit"
                abis.isEmpty() -> "Unknown"
                else -> "32-bit"
            },
            SpecGroup.PROCESSOR, "Build.SUPPORTED_ABIS"
        )
        fields += FieldBuilder.value(
            "cpu.maxFreq", "Maximum CPU frequency",
            maxFreq?.let { String.format(java.util.Locale.US, "%.2f GHz", it / 1_000_000.0) },
            SpecGroup.PROCESSOR, "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
            Confidence.MEDIUM,
            note = if (maxFreq == null) "This kernel does not expose CPU frequency scaling to apps." else null
        )
        fields += FieldBuilder.value(
            "cpu.hardware", "Hardware name", Build.HARDWARE, SpecGroup.PROCESSOR, "android.os.Build.HARDWARE"
        )

        // ---- GPU ----
        val configurationInfo = activityManager.deviceConfigurationInfo
        val gles = configurationInfo.glEsVersion
        val renderer = readGpuRenderer()
        val vulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        fields += FieldBuilder.value(
            "gpu.glesVersion", "OpenGL ES version", gles, SpecGroup.GRAPHICS,
            "ActivityManager.deviceConfigurationInfo.glEsVersion"
        )
        fields += FieldBuilder.value(
            "gpu.renderer", "GPU model", renderer, SpecGroup.GRAPHICS, "Kernel GPU driver nodes",
            Confidence.MEDIUM,
            note = if (renderer == null) "The GPU model is not exposed through a public API on this device." else null
        )
        fields += FieldBuilder.value(
            "gpu.vulkan", "Vulkan support",
            if (vulkan) "Supported" else "Not reported", SpecGroup.GRAPHICS,
            "PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL"
        )

        // ---- Display ----
        val display = readDisplay()
        if (display != null) {
            fields += FieldBuilder.value(
                "display.resolution", "Display resolution",
                "${display.widthPx} x ${display.heightPx} px", SpecGroup.DISPLAY, "DisplayMetrics"
            )
            fields += FieldBuilder.value(
                "display.density", "Pixel density", "${display.densityDpi} dpi", SpecGroup.DISPLAY,
                "DisplayMetrics.densityDpi"
            )
            fields += FieldBuilder.value(
                "display.diagonal", "Approximate screen size",
                String.format(java.util.Locale.US, "%.2f in", display.diagonalInches), SpecGroup.DISPLAY,
                "Derived from resolution and physical dpi", Confidence.LOW,
                Verification.INFERRED,
                note = "Calculated from the reported xdpi/ydpi, which some vendors round."
            )
            fields += FieldBuilder.value(
                "display.refreshRate", "Current refresh rate",
                String.format(java.util.Locale.US, "%.0f Hz", display.refreshRateHz), SpecGroup.DISPLAY,
                "Display.refreshRate"
            )
            fields += FieldBuilder.value(
                "display.supportedRefreshRates", "Supported refresh rates",
                display.supportedRefreshRates.joinToString(", ") {
                    String.format(java.util.Locale.US, "%.0f", it)
                } + " Hz",
                SpecGroup.DISPLAY, "Display.supportedModes"
            )
            fields += FieldBuilder.value(
                "display.hdr", "HDR formats",
                display.hdrTypes.joinToString(", ").ifBlank { "None reported" }, SpecGroup.DISPLAY,
                "Display.hdrCapabilities"
            )
            fields += FieldBuilder.value(
                "display.wideColor", "Wide colour gamut",
                if (display.wideColorGamut) "Supported" else "Not reported", SpecGroup.DISPLAY,
                "Configuration.isScreenWideColorGamut"
            )
        } else {
            fields += FieldBuilder.unsupported(
                "display.resolution", "Display resolution", SpecGroup.DISPLAY, "DisplayMetrics",
                "The display configuration could not be read in this context."
            )
        }

        return HardwareResult(
            display = display,
            ramTotalBytes = ramTotal,
            ramAvailableBytes = ramAvailable,
            isLowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
            storageTotalBytes = storageTotal,
            storageAvailableBytes = storageAvailable,
            cpuCores = cores,
            cpuAbis = abis,
            cpuMaxFreqKhz = maxFreq,
            gpuRenderer = renderer,
            gpuVendor = renderer?.let { guessGpuVendor(it) },
            glesVersion = gles,
            vulkanSupported = vulkan,
            fields = fields,
            errors = errors
        )
    }

    private fun readDisplay(): DisplayFacts? = try {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display: Display = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val refresh = if (display.refreshRate > 0f) display.refreshRate else 60f
        val modes = try {
            display.supportedModes.map { it.refreshRate }.distinct().sorted()
        } catch (e: Exception) {
            listOf(refresh)
        }
        val hdr = try {
            display.hdrCapabilities.supportedHdrTypes.map {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> "Type $it"
                }
            }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
        val diagonal = if (metrics.xdpi > 0 && metrics.ydpi > 0) {
            val widthInches = metrics.widthPixels / metrics.xdpi
            val heightInches = metrics.heightPixels / metrics.ydpi
            kotlin.math.sqrt(widthInches * widthInches + heightInches * heightInches)
        } else 0f

        DisplayFacts(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            refreshRateHz = refresh,
            supportedRefreshRates = modes,
            hdrTypes = hdr,
            xdpi = metrics.xdpi,
            ydpi = metrics.ydpi,
            diagonalInches = diagonal,
            wideColorGamut = (context.resources.configuration.isScreenWideColorGamut)
        )
    } catch (e: Exception) {
        null
    }

    private fun readProcMemInfo(): Pair<Long, Long>? = try {
        var total = 0L
        var available = 0L
        File("/proc/meminfo").readLines().forEach { line ->
            when {
                line.startsWith("MemTotal:") -> total = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                line.startsWith("MemAvailable:") -> available = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
            }
        }
        if (total > 0) total to available else null
    } catch (e: Exception) {
        null
    }

    private fun readCpuMaxFreq(): Long? = try {
        File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
    } catch (e: Exception) {
        null
    }

    private fun readGpuRenderer(): String? {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/proc/gpuinfo",
            "/sys/kernel/gpu/gpu_model"
        )
        for (path in candidates) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val text = file.readText().trim().lineSequence().firstOrNull()
                    if (!text.isNullOrBlank() && !text.all { it.isDigit() }) return text
                }
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    private fun guessGpuVendor(renderer: String): String? {
        val lower = renderer.lowercase()
        return when {
            lower.contains("adreno") || lower.contains("kgsl") -> "Qualcomm"
            lower.contains("mali") -> "Arm"
            lower.contains("powervr") || lower.contains("rogue") -> "Imagination Technologies"
            lower.contains("xclipse") || lower.contains("rDNA") -> "AMD (Samsung Xclipse)"
            else -> null
        }
    }

    /** Tiny accessor so this file does not need to hold a reader instance. */
    private object SystemPropertySafe {
        private val reader = com.verifydeviceinfo.data.android.SystemPropertyReader()
        fun get(key: String): String? = reader.get(key)
    }
}
