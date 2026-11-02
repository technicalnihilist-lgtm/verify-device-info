package com.verifydeviceinfo.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs at most one scan at a time, and lets the newest request win.
 *
 * The scan used to be guarded by a plain `if (scanning) return` in the view model, so a scan that
 * never returned — a Binder call into a camera another app holds, a `getprop` process that does not
 * exit, a collector waiting on a device that stopped answering — left the app unable to start
 * another one for the rest of the process's life. Now a request that arrives while a scan is
 * running cancels that scan and starts the new one straight away.
 *
 * Cancellation is cooperative: a collector blocked inside a Java call does not notice `cancel()`
 * until it next reaches a suspension point, so a superseded scan can still be winding down while
 * its replacement is already running. Each request therefore carries a generation number, and the
 * caller gates its state writes and side effects on [isCurrent] — anything a superseded scan tries
 * to publish is dropped instead of overwriting the progress or the result of the scan that
 * replaced it.
 */
class ScanCoordinator(private val scope: CoroutineScope) {

    private var generation = 0L
    private var job: Job? = null

    /**
     * Cancels whatever is running and starts [block] with a new generation number, which the block
     * must pass to [isCurrent] before it publishes anything or touches storage.
     */
    fun start(block: suspend (Long) -> Unit) {
        val next = generation + 1
        generation = next
        job?.cancel()
        job = scope.launch { block(next) }
    }

    /** False once a newer request has been started: a superseded scan must publish nothing. */
    fun isCurrent(generation: Long): Boolean = generation == this.generation
}
