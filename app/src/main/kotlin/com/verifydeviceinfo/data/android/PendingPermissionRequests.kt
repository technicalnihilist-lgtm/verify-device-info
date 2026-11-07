package com.verifydeviceinfo.data.android

import com.verifydeviceinfo.core.model.PermissionState
import kotlinx.coroutines.CompletableDeferred

/**
 * The permission requests that have been raised and not yet answered.
 *
 * A request is identified by the integer code the platform echoes back in
 * `onRequestPermissionsResult`, and each code owns one deferred that the awaiting caller suspends
 * on. The three things that can happen to a code — it is answered, its caller goes away, or the
 * request could not be raised at all — all have to end with the entry gone:
 *
 *  * an answer arrives once, from the platform ([complete]);
 *  * a caller that is cancelled never reads an answer, so its entry is dropped ([abandon]);
 *  * a request that threw before reaching the platform will never be answered, so its entry is
 *    dropped as well ([abandon]).
 *
 * Without the last two the map keeps an entry per abandoned request for the life of the process,
 * and a later answer for that code completes a deferred nobody will ever read.
 *
 * Every method is synchronized because [AndroidPermissionManager.request] is a suspend function:
 * it can be resumed on any dispatcher, while the platform's answer always arrives on the main
 * thread. Allocating the code here, rather than in the caller, also means two requests racing each
 * other cannot be handed the same code.
 */
internal class PendingPermissionRequests(private val firstCode: Int = FIRST_REQUEST_CODE) {

    private val entries = HashMap<Int, CompletableDeferred<PermissionState>>()
    private var nextCode = firstCode

    /** Reserves the next code and the deferred its answer will complete. */
    @Synchronized
    fun begin(): PendingPermissionRequest {
        val code = nextCode++
        val deferred = CompletableDeferred<PermissionState>()
        entries[code] = deferred
        return PendingPermissionRequest(code, deferred)
    }

    /**
     * Completes the request with [code] and forgets it. False when nothing is waiting on that code
     * — an answer for a request that was abandoned, or one that has already been answered.
     */
    @Synchronized
    fun complete(code: Int, state: PermissionState): Boolean {
        val deferred = entries.remove(code) ?: return false
        deferred.complete(state)
        return true
    }

    /**
     * Forgets a request that will never be answered. The caller's deferred is deliberately left
     * incomplete: a caller that was cancelled has nobody to resume, and inventing an answer for it
     * would be reporting a decision the user never made.
     */
    @Synchronized
    fun abandon(code: Int) {
        entries.remove(code)
    }

    /** How many requests are still outstanding. Exists so the lifecycle above can be asserted. */
    @Synchronized
    fun size(): Int = entries.size

    companion object {
        /** Where request codes start. The value is arbitrary; it only has to stay out of the way. */
        const val FIRST_REQUEST_CODE: Int = 4100
    }
}

/** One reserved request code and the deferred its answer completes. */
internal class PendingPermissionRequest(
    val code: Int,
    val deferred: CompletableDeferred<PermissionState>
)
