package com.verifydeviceinfo.core.util

/**
 * Orders overlapping reads so that only the newest one may publish its result.
 *
 * A read that the user has already moved away from can still finish last: the history screen
 * expands a stored report by reading it off disk, and tapping a second entry before the first read
 * returns leaves two reads in flight whose completion order nothing controls. Whichever finished
 * last wrote the expanded text, so the slower read for the entry the user had left could land under
 * the entry now open and show one stored report in another entry's card.
 *
 * A caller takes a ticket before it starts a read and asks [isCurrent] before it publishes the
 * result. Taking a ticket is synchronous, so the ticket a caller holds is decided by the order the
 * requests were *made*, not by the order their reads finish; a ticket older than the newest one
 * never becomes current again, however long the newer read takes or whether it fails.
 *
 * This deliberately does not launch or cancel anything — [ScanCoordinator] owns that for scans,
 * where a superseded request has a job to cancel. Here the work is a read the caller has already
 * started in its own scope, and the only question is whether its answer is still wanted.
 */
internal class LatestRequest {

    private var latest = 0L

    /** Takes the next ticket; every earlier ticket is superseded from this moment. */
    @Synchronized
    fun begin(): Long {
        latest++
        return latest
    }

    /** True while [ticket] is still the newest ticket taken. */
    @Synchronized
    fun isCurrent(ticket: Long): Boolean = ticket == latest
}
