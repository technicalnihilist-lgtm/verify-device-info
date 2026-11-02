package com.verifydeviceinfo.data.android

import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads Android system properties.
 *
 * Primary path: one `getprop` dump (a read-only, unprivileged query) parsed into a map, which
 * is far cheaper than one process per property. Fallback: reflection on
 * android.os.SystemProperties, which the platform may block depending on target SDK.
 * Every accessor is optional — callers must handle null.
 *
 * The dump is parsed at most once per instance, so a single instance must be shared by every
 * caller in a scan (see [com.verifydeviceinfo.AppContainer]): a second instance means a second
 * `getprop` process and a second parse of the same ~900 lines.
 *
 * [dumpLoader] exists so the parse-once behaviour and the line parser can be unit-tested without
 * a device; production code uses the default, which shells out to `getprop`.
 */
class SystemPropertyReader(
    private val dumpLoader: () -> Map<String, String> = { readProcessDump() }
) {

    private val cache = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    @Synchronized
    private fun loadDump() {
        if (loaded) return
        loaded = true
        try {
            cache.putAll(dumpLoader())
        } catch (ignored: Throwable) {
            // getprop unavailable — reflection below may still work.
        }
    }

    private val reflectGet: Method? by lazy {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getMethod("get", String::class.java)
        } catch (ignored: Throwable) {
            null
        }
    }

    fun get(key: String): String? {
        loadDump()
        cache[key]?.let { if (it.isNotBlank()) return it }
        return try {
            (reflectGet?.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
        } catch (ignored: Throwable) {
            null
        }
    }

    fun getInt(key: String): Int? = get(key)?.trim()?.toIntOrNull()

    fun getBooleanFlag(key: String): Boolean? = when (get(key)?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }

    fun all(): Map<String, String> {
        loadDump()
        return cache.toMap()
    }

    companion object {

        /** Runs `getprop` once and parses its dump. Returns an empty map if it cannot run. */
        fun readProcessDump(): Map<String, String> {
            val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    return parseDump(generateSequence { reader.readLine() })
                }
            } finally {
                try {
                    process.waitFor()
                } catch (ignored: Throwable) {
                }
                process.destroy()
            }
        }

        /**
         * Parses lines of the form `[key]: [value]`. A malformed line is skipped rather than
         * aborting the whole dump, so one odd property cannot cost every other property.
         */
        fun parseDump(lines: Sequence<String>): Map<String, String> {
            val parsed = HashMap<String, String>(1024)
            for (line in lines) {
                if (!line.startsWith("[")) continue
                val closing = line.indexOf("]: [")
                if (closing <= 1) continue
                if (!line.endsWith("]")) continue
                if (line.length < closing + 5) continue
                parsed[line.substring(1, closing)] = line.substring(closing + 4, line.length - 1)
            }
            return parsed
        }
    }
}
