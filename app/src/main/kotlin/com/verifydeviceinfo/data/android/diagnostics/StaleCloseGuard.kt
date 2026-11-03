package com.verifydeviceinfo.data.android.diagnostics

/**
 * Closes a resource that arrives after the object that opened it has been released.
 *
 * `CameraManager.openCamera` and `CameraDevice.createCaptureSession` are asynchronous: the callback
 * fires on a later main-thread message. A test screen can leave composition — back gesture, or the
 * user recording a result — before that callback runs, so the probe is closed while the open is
 * still in flight. Closing an empty probe does nothing, and the device then arrives and is kept
 * forever: the camera stays open (and the torch, if it was switched on) with no owner left to close
 * it, until the process dies.
 *
 * A token is taken when an open starts. [invalidate] is called by the owner's `close`, so every
 * token handed out before it goes stale. A resource that arrives carrying a stale token is closed
 * on the spot by [admit] instead of being kept.
 *
 * Tokens taken after [invalidate] are current again, so a probe can be reopened after it is closed.
 */
class StaleCloseGuard<T : AutoCloseable> {

    private var generation = 0L

    /** A token for an operation that is starting now. */
    fun token(): Long = generation

    /** Invalidates every token handed out so far. */
    fun invalidate() {
        generation++
    }

    /** True while [token] is still the newest one. */
    fun isCurrent(token: Long): Boolean = token == generation

    /**
     * True when [resource] may be kept. When [token] is stale the owner has been released, so the
     * resource is closed here and false is returned; a close that throws is ignored, exactly as the
     * probes ignore a close on a device that has already gone.
     */
    fun admit(token: Long, resource: T): Boolean {
        if (isCurrent(token)) return true
        closeQuietly(resource)
        return false
    }

    private fun closeQuietly(resource: T) {
        try {
            resource.close()
        } catch (ignored: Exception) {
        }
    }
}
