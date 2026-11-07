package com.verifydeviceinfo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verifydeviceinfo.core.model.RiskLevel
import com.verifydeviceinfo.data.android.parseHistoryIndex
import com.verifydeviceinfo.data.android.staleReportFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The history index is one JSON file holding up to fifty entries, and it is rewritten from whatever
 * the previous read produced. A single unusable entry used to throw out of the whole parse, which
 * made the index read as empty: the next save then wrote an index containing only the new report and
 * pruned every stored report file, so one bad entry destroyed the entire history.
 *
 * These tests pin the entry-by-entry tolerance and the rule that an index that cannot be understood
 * at all is never treated as an index that keeps nothing.
 *
 * Robolectric is used for its real `org.json` implementation, the same one the app runs against on
 * a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryIndexParseTest {

    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val third = "33333333-3333-4333-8333-333333333333"

    private fun entry(id: String, label: String = "Pixel 8 Pro") = """
        {
          "id": "$id",
          "timestampMillis": 1730000000000,
          "modelLabel": "$label",
          "riskLevel": "LOW",
          "hardwareScore": 92,
          "authenticityScore": 88,
          "issueCount": 1
        }
    """.trimIndent()

    @Test
    fun `an entry missing its id does not take the other entries with it`() {
        val text = """[
            ${entry(first)},
            {"timestampMillis": 1730000000001, "modelLabel": "Broken"},
            ${entry(third)}
        ]"""

        val parsed = parseHistoryIndex(text)

        assertEquals(listOf(first, third), parsed?.map { it.id })
    }

    @Test
    fun `a blank id makes only that entry unusable`() {
        val text = """[${entry(first)}, {"id": "   ", "modelLabel": "Blank"}, ${entry(second)}]"""

        assertEquals(listOf(first, second), parseHistoryIndex(text)?.map { it.id })
    }

    @Test
    fun `non-object elements are skipped`() {
        val text = """["a string", 42, null, [], ${entry(second)}]"""

        assertEquals(listOf(second), parseHistoryIndex(text)?.map { it.id })
    }

    @Test
    fun `a wrong-typed field is defaulted rather than dropping the entry`() {
        val text = """[
            {"id": "$first", "timestampMillis": "1730000000000", "modelLabel": "Numeric timestamp",
             "riskLevel": 7, "hardwareScore": "high"}
        ]"""

        val parsed = parseHistoryIndex(text)
        val only = parsed?.single()

        assertEquals(first, only?.id)
        assertEquals(1730000000000L, only?.timestampMillis)
        assertEquals(RiskLevel.UNKNOWN, only?.riskLevel)
    }

    @Test
    fun `every field written by the index writer reads back unchanged`() {
        val text = """[
            {"id": "$first", "timestampMillis": 1730000000000, "modelLabel": "Google Pixel 8 Pro",
             "riskLevel": "ELEVATED", "hardwareScore": 92, "authenticityScore": 88,
             "issueCount": 2, "reportPath": "/data/report.json"}
        ]"""

        val only = parseHistoryIndex(text)?.single()

        assertEquals(first, only?.id)
        assertEquals(1730000000000L, only?.timestampMillis)
        assertEquals("Google Pixel 8 Pro", only?.modelLabel)
        assertEquals(RiskLevel.ELEVATED, only?.riskLevel)
        assertEquals(92, only?.hardwareScore)
        assertEquals(88, only?.authenticityScore)
        assertEquals(2, only?.issueCount)
        assertEquals("/data/report.json", only?.reportPath)
    }

    @Test
    fun `an absent score stays absent instead of becoming zero`() {
        val text = """[{"id": "$first", "timestampMillis": 1, "modelLabel": "x", "riskLevel": "LOW"}]"""

        val only = parseHistoryIndex(text)?.single()

        assertNull(only?.hardwareScore)
        assertNull(only?.authenticityScore)
        assertNull(only?.reportPath)
    }

    @Test
    fun `an unreadable document is unknown, not an empty history`() {
        assertNull(parseHistoryIndex(""))
        assertNull(parseHistoryIndex("not json at all"))
        assertNull(parseHistoryIndex("""{"id": "$first"}"""))
        assertNull(parseHistoryIndex("""[{"id": "$first"}"""))
    }

    @Test
    fun `an empty array is an empty history`() {
        assertEquals(emptyList<String>(), parseHistoryIndex("[]")?.map { it.id })
    }

    @Test
    fun `nothing is pruned when the index could not be read`() {
        val stale = staleReportFiles(
            keptIds = emptySet(),
            existingNames = listOf("$first.json", "$second.json"),
            indexReadable = false
        )

        assertEquals(emptyList<String>(), stale)
    }

    @Test
    fun `pruning is unchanged when the index was read`() {
        val stale = staleReportFiles(
            keptIds = setOf(first),
            existingNames = listOf("$first.json", "$second.json", "index.json"),
            indexReadable = true
        )

        assertEquals(listOf("$second.json"), stale)
        assertTrue(staleReportFiles(setOf(first), listOf("$first.json")).isEmpty())
    }
}
