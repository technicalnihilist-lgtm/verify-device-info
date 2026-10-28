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
 */
class SystemPropertyReader {

    private val cache = ConcurrentHashMap<String, String>()
    private var loaded = false

    @Synchronized
    private fun loadDump() {
        if (loaded) return
        loaded = true
        try {
            val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    // Format: [key]: [value]
                    val closing = line.indexOf("]: [")
                    if (line.startsWith("[") && closing > 1) {
                        val key = line.substring(1, closing)
                        val value = line.substring(closing + 4, line.length - 1)
                        cache[key] = value
                    }
                    line = reader.readLine()
                }
            }
            process.waitFor()
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
}
