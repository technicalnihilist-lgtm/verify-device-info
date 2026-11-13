package com.verifydeviceinfo

import com.verifydeviceinfo.core.util.LatestRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket ordering for overlapping reads.
 *
 * The history screen reads a stored report off disk when a card is expanded, and a second tap
 * starts a second read while the first may still be running. Nothing ordered those completions, so
 * the read for the entry the user had left could finish last and be published under the entry now
 * open. [LatestRequest] is the guard the view model consults before publishing: it records the order
 * the requests were *made* in, not the order their reads answer in.
 */
class LatestRequestTest {

    @Test
    fun theFirstTicketIsCurrent() {
        val requests = LatestRequest()
        val first = requests.begin()
        assertTrue(requests.isCurrent(first))
    }

    @Test
    fun aNewerTicketSupersedesTheOlderOneImmediately() {
        val requests = LatestRequest()
        val first = requests.begin()
        val second = requests.begin()
        assertFalse(requests.isCurrent(first))
        assertTrue(requests.isCurrent(second))
    }

    @Test
    fun aSupersededTicketNeverBecomesCurrentAgain() {
        val requests = LatestRequest()
        val first = requests.begin()
        requests.begin()
        // However many later requests come and go, the old ticket stays dead: a read that answers
        // late must not publish, and nothing has to be "released" to keep it dead.
        repeat(5) { requests.begin() }
        assertFalse(requests.isCurrent(first))
    }

    @Test
    fun ticketsAreDistinctAndIncreasing() {
        val requests = LatestRequest()
        val tickets = List(50) { requests.begin() }
        assertEquals(tickets.size, tickets.toSet().size)
        assertEquals(tickets.sorted(), tickets)
    }

    @Test
    fun onlyTheNewestOfManyOutstandingTicketsIsCurrent() {
        val requests = LatestRequest()
        val tickets = List(4) { requests.begin() }
        val current = tickets.filter { requests.isCurrent(it) }
        assertEquals(listOf(tickets.last()), current)
    }

    @Test
    fun aTicketThatWasNeverIssuedIsNotCurrent() {
        val requests = LatestRequest()
        requests.begin()
        assertFalse(requests.isCurrent(0L))
        assertFalse(requests.isCurrent(-1L))
        assertFalse(requests.isCurrent(99L))
    }
}
