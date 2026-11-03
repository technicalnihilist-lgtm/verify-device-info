package com.verifydeviceinfo.core.util

import java.lang.ref.WeakReference

/**
 * Holds at most one object without keeping it alive.
 *
 * Used where a process-scoped object needs to reach a short-lived one — an `Activity`, typically —
 * so that a missed detach degrades to "nobody to ask" instead of retaining the object and everything
 * it owns. The strong reference lives only in the owner; this holder is a weak view of it.
 *
 * [clear] is deliberately identity-checked. Detach is not always the last word: a configuration
 * change destroys the old activity and creates the new one, and a late callback from the old one must
 * not take the holder away from the new one. Passing the instance that is being detached makes that
 * ordering harmless.
 */
class WeakRefHolder<T : Any> {

    private var ref: WeakReference<T>? = null

    /** Points the holder at [value], replacing whatever it held. */
    fun set(value: T) {
        ref = WeakReference(value)
    }

    /**
     * Drops the reference only if it still points at [expected]. A stale detach — an activity that has
     * already been replaced — leaves the current value in place.
     */
    fun clear(expected: T) {
        if (ref?.get() === expected) ref = null
    }

    /** The current value, or null when nothing is held or the referent has been collected. */
    fun get(): T? {
        val current = ref ?: return null
        val value = current.get()
        // Drop a dead reference so a collected referent does not keep the WeakReference alive.
        if (value == null) ref = null
        return value
    }
}
