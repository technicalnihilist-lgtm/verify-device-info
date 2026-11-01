package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.staleReportFiles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The history index is capped at 50 entries, so older report files have to be removed explicitly.
 * These tests pin which files are considered removable.
 */
class HistoryPruneTest {

    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val third = "33333333-3333-4333-8333-333333333333"

    @Test
    fun `reports still in the index are kept`() {
        val stale = staleReportFiles(setOf(first, second), listOf("$first.json", "$second.json"))
        assertEquals(emptyList<String>(), stale)
    }

    @Test
    fun `report dropped from the index is deleted`() {
        val stale = staleReportFiles(setOf(first), listOf("$first.json", "$second.json"))
        assertEquals(listOf("$second.json"), stale)
    }

    @Test
    fun `an empty index makes every report removable`() {
        val stale = staleReportFiles(emptySet(), listOf("$first.json", "$second.json"))
        assertEquals(listOf("$first.json", "$second.json"), stale)
    }

    @Test
    fun `files that are not reports are left alone`() {
        val existing = listOf("index.json", "notes.txt", "1234.json", "$first.json")
        val stale = staleReportFiles(emptySet(), existing)
        assertEquals(listOf("$first.json"), stale)
    }

    @Test
    fun `uppercase report names are recognised and matched case-sensitively`() {
        val upper = third.uppercase()
        val stale = staleReportFiles(setOf(upper), listOf("$upper.json"))
        assertEquals(emptyList<String>(), stale)
        assertEquals(listOf("$upper.json"), staleReportFiles(emptySet(), listOf("$upper.json")))
    }

    @Test
    fun `an entry whose file was never written does not throw`() {
        val stale = staleReportFiles(setOf(first), listOf("$second.json"))
        assertEquals(listOf("$second.json"), stale)
    }
}
