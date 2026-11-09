package com.verifydeviceinfo

import com.verifydeviceinfo.data.android.wrapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report's word wrap.
 *
 * The layout used to rebuild the line being built as a new `String` for every word and then copy
 * it again into a new `StringBuilder`, so a paragraph of W words allocated O(W²) characters — sixty
 * throw-away copies of a sixty-word paragraph, hundreds of times over in one export. The wrap now
 * appends to a single `StringBuilder` and measures it in place.
 *
 * Two things have to hold for that to be safe, and both are pinned here: the lines must be exactly
 * the ones the old code produced (a different break moves every glyph after it in the exported
 * document), and the work must actually be smaller. The old algorithm is reproduced verbatim below
 * as the reference, and both are driven through the same injected measurement, so any difference is
 * a difference in the wrapping and not in the text metrics.
 */
class PdfTextWrapTest {

    /** The implementation this replaced, kept only as the reference the new one is checked against. */
    private fun previousWrap(
        text: String,
        maxWidth: Float,
        measure: (CharSequence) -> Float
    ): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /**
     * The implementation this replaced: the same in-place line building, but the words come from
     * `text.split(" ")` — one substring per word, plus the list that holds them, before any line
     * is laid out. The current version reads the same words as ranges of the paragraph, so this is
     * the reference that pins "same words, no substrings": including the empty words a run of two
     * spaces and a trailing space produce.
     */
    private fun splitReferenceWrap(
        text: String,
        maxWidth: Float,
        measure: (CharSequence) -> Float
    ): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            val beforeWord = current.length
            if (beforeWord > 0) current.append(' ')
            current.append(word)
            if (measure(current) <= maxWidth) continue
            if (beforeWord > 0) {
                current.setLength(beforeWord)
                lines += current.toString()
                current.setLength(0)
                current.append(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /** Fixed advance per character: the shape of a monospaced font, and the strictest test of breaks. */
    private fun perCharacter(advance: Float = 6f): (CharSequence) -> Float = { it.length * advance }

    /**
     * A measurer that is not a pure function of length: a line's width depends on which words it
     * holds, so a wrap that loses or re-adds a word cannot accidentally measure the same.
     */
    private fun perWord(): (CharSequence) -> Float = { line ->
        var width = 0f
        for (word in line.toString().split(" ")) {
            width += 4f + word.length * 3f + (word.hashCode() and 7)
        }
        width
    }

    private val corpus: List<String> = listOf(
        "The fingerprint's brand/product/device triple matches the reported brand, product and " +
            "device properties.",
        "One",
        "A",
        "  leading and   repeated   spaces  ",
        "Trailing space ",
        "supercalifragilisticexpialidocious-and-then-some-more-letters-than-any-line-can-hold",
        "word " + "supercalifragilisticexpialidocious ".repeat(3) + "tail words that follow it",
        "Ünïcödé — em dash, ° degree, µm, ² superscript, and 日本語のテキスト mixed in",
        "Line\nbreak inside the paragraph is just another character here",
        (1..120).joinToString(" ") { "word$it" },
        (1..40).joinToString(" ") { "x" },
        "a b c d e f g h i j k l m n o p q r s t u v w x y z",
        "",
        "   ",
        // The whitespace shapes the word scan has to reproduce exactly: a trailing space adds an
        // empty word, a leading one adds an empty first word, and a run adds one per extra space.
        "a ",
        " a",
        "a  b",
        " a  b ",
        "\ttab-separated\ttext stays one word",
        "word ".repeat(30)
    )

    private val widths = listOf(1f, 10f, 40f, 120f, 300f, 555f, 10_000f)

    @Test
    fun `every paragraph wraps to exactly the lines the previous implementation produced`() {
        val measurers = listOf(perCharacter(), perCharacter(advance = 2.5f), perWord())
        for (text in corpus) {
            for (maxWidth in widths) {
                for (measure in measurers) {
                    assertEquals(
                        "text='${text.take(40)}' maxWidth=$maxWidth",
                        previousWrap(text, maxWidth, measure),
                        wrapText(text, maxWidth, measure)
                    )
                }
            }
        }
    }

    @Test
    fun `the word scan produces the same words as splitting the paragraph, whitespace included`() {
        // The words used to come from `text.split(" ")`. Reading them as ranges must find exactly
        // the same ones — an extra, missing or re-ordered word would change a line.
        val measurers = listOf(perCharacter(), perCharacter(advance = 2.5f), perWord())
        for (text in corpus) {
            for (maxWidth in widths) {
                for (measure in measurers) {
                    assertEquals(
                        "text='${text.take(40)}' maxWidth=$maxWidth",
                        splitReferenceWrap(text, maxWidth, measure),
                        wrapText(text, maxWidth, measure)
                    )
                }
            }
        }
    }

    @Test
    fun `a paragraph made only of spaces wraps to no lines, as it always did`() {
        // `split(" ")` on "  " yields three empty words, and the previous wrap still placed none
        // of them: the whole text is blank, so nothing is laid out. The range scan must agree.
        val measure: (CharSequence) -> Float = { it.length * 6f }
        for (text in listOf(" ", "  ", "    ", "\t")) {
            assertTrue(splitReferenceWrap(text, 100f, measure).isEmpty())
            assertTrue(wrapText(text, 100f, measure).isEmpty())
        }
    }

    @Test
    fun `the number of measurements is unchanged, so no extra work reaches the paint`() {
        for (text in corpus) {
            for (maxWidth in widths) {
                var oldCalls = 0
                var newCalls = 0
                val oldLines = previousWrap(text, maxWidth) { oldCalls++; it.length * 6f }
                val newLines = wrapText(text, maxWidth) { newCalls++; it.length * 6f }
                assertEquals(oldLines, newLines)
                assertEquals("text='${text.take(40)}' maxWidth=$maxWidth", oldCalls, newCalls)
            }
        }
    }

    @Test
    fun `the measured text is the same text, character for character`() {
        val oldSeen = mutableListOf<String>()
        val newSeen = mutableListOf<String>()
        val text = corpus[0]
        previousWrap(text, 300f) { oldSeen += it.toString(); it.length * 6f }
        wrapText(text, 300f) { newSeen += it.toString(); it.length * 6f }
        assertEquals(oldSeen, newSeen)
    }

    @Test
    fun `a word wider than the line is not split, and the wrap around it is unchanged`() {
        // Nothing in the export hyphenates: an over-long word overflows the line rather than being
        // broken, and the word after it starts a new line instead of being squeezed in.
        val measurer: (CharSequence) -> Float = { it.length * 6f }
        assertEquals(
            listOf("abcdefghij", "k"),
            wrapText("abcdefghij k", 10f, measurer)
        )
        assertEquals(previousWrap("abcdefghij k", 10f, measurer), wrapText("abcdefghij k", 10f, measurer))
        assertEquals(
            listOf("abcdefghij k"),
            wrapText("abcdefghij k", 100f, measurer)
        )
    }

    @Test
    fun `blank text produces no lines`() {
        assertTrue(wrapText("", 100f) { it.length * 6f }.isEmpty())
        assertTrue(wrapText("   ", 100f) { it.length * 6f }.isEmpty())
    }

    @Test
    fun `laying out a report's worth of paragraphs is faster than the copies it replaced`() {
        // A report is a few hundred short lines plus a handful of long ones. The old wrap builds
        // and throws away one String and one StringBuilder copy of the line per word, so this is
        // where that shows up as time as well as garbage.
        val paragraphs = List(300) { corpus[0] } + List(50) { corpus[9] } + listOf(corpus[6])
        val measure: (CharSequence) -> Float = { it.length * 6f }

        fun bestOf(rounds: Int, run: () -> Unit): Long {
            var best = Long.MAX_VALUE
            repeat(rounds) {
                val started = System.nanoTime()
                run()
                best = minOf(best, System.nanoTime() - started)
            }
            return best
        }

        val old = { paragraphs.forEach { previousWrap(it, 515f, measure) } }
        val new = { paragraphs.forEach { wrapText(it, 515f, measure) } }

        // Warm both paths: the first rounds are class loading and the interpreter, not layout.
        repeat(5) { old(); new() }

        val before = bestOf(5, old)
        val after = bestOf(5, new)
        println("wrap layout over ${paragraphs.size} paragraphs: before=${before / 1000}us after=${after / 1000}us")

        assertEquals(
            paragraphs.map { previousWrap(it, 515f, measure) },
            paragraphs.map { wrapText(it, 515f, measure) }
        )
        assertTrue(
            "expected the copy-free wrap to be faster, before=${before}ns after=${after}ns",
            after < before
        )
    }
}
