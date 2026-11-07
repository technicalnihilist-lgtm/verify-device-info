package com.verifydeviceinfo.data.android

import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
 *
 * The dump is read on a worker thread and abandoned at a deadline ([DUMP_DEADLINE_MS]): a `getprop`
 * that stops answering must not hold the calling thread — and its process — for the life of the app,
 * which is what an unbounded `readLine()`/`waitFor()` pair does on a rescan.
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

        /**
         * How long the `getprop` dump may take before the process is killed. A healthy dump is a
         * read-only query that answers in well under a second; two seconds leaves room for a loaded
         * device while keeping a wedged `getprop` from blocking a scan.
         */
        internal const val DUMP_DEADLINE_MS = 2_000L

        /** Grace given to the reader thread, and to the process, to finish once killed. */
        private const val DUMP_DRAIN_GRACE_MS = 250L

        /** Runs `getprop` once and parses its dump. Returns an empty map if it cannot run. */
        fun readProcessDump(): Map<String, String> = readProcessDump(listOf("getprop"), DUMP_DEADLINE_MS)

        /**
         * Runs [command] and parses its stdout as a property dump, giving up after [deadlineMillis].
         *
         * The read happens on a worker thread because `readLine()` blocks until the process closes
         * stdout: without the deadline a `getprop` that hangs blocks this thread, and the process
         * with it, forever. At the deadline the process is killed forcibly and whatever was parsed
         * before the cut is returned — a property after the cut is simply absent, and [get] then
         * falls back to reflection for it, exactly as for a key the dump does not publish.
         */
        internal fun readProcessDump(command: List<String>, deadlineMillis: Long): Map<String, String> {
            val process = try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (ignored: Throwable) {
                return emptyMap()
            }

            val lines = Collections.synchronizedList(ArrayList<String>(1024))
            val reader = Thread({
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { input ->
                        while (true) {
                            lines.add(input.readLine() ?: break)
                        }
                    }
                } catch (ignored: Throwable) {
                    // Killed at the deadline, or the pipe broke: keep the lines already read.
                }
            }, "getprop-dump")
            reader.isDaemon = true

            try {
                reader.start()
                reader.join(deadlineMillis)
                if (reader.isAlive) {
                    process.destroyForcibly()
                    reader.join(DUMP_DRAIN_GRACE_MS)
                }
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                // Bounded reap: waitFor() alone is the unbounded wait this method exists to avoid.
                try {
                    if (!process.waitFor(DUMP_DRAIN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                    }
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            val dump = synchronized(lines) { ArrayList(lines) }
            return parseDump(dump.asSequence())
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
