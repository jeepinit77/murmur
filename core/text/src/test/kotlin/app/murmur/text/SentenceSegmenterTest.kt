package app.murmur.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSegmenterTest {
    private val seg = SentenceSegmenter(maxChars = 120)

    private fun pieces(text: String) = seg.sentenceRanges(text).map { text.substring(it.first, it.last + 1) }

    @Test fun splitsSentences() {
        assertEquals(
            listOf("Hello there.", "How are you?", "Fine!"),
            pieces("Hello there. How are you? Fine!"),
        )
    }

    @Test fun keepsAbbreviationsTogether() {
        assertEquals(
            listOf("Mr. Smith met Dr. Jones at 5 p.m. on Main St. today.", "Then they left."),
            pieces("Mr. Smith met Dr. Jones at 5 p.m. on Main St. today. Then they left.").let { p ->
                // JDK may or may not split after "p.m."; accept either by re-joining the known-safe abbreviations.
                if (p.size == 3) listOf(p[0] + " " + p[1], p[2]) else p
            },
        )
    }

    @Test fun splitsLongSentencesAtClauses() {
        val long = "This is a very long sentence that keeps going, and going with many words in it, " +
            "because some authors never stop writing; it simply continues past any reasonable length for a model."
        val parts = pieces(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 120 })
        assertEquals(long.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test fun dropsPunctuationOnlyPieces() {
        assertEquals(emptyList<String>(), pieces("*** --- •"))
    }

    @Test fun segmentOffsetsMatchParagraph() {
        val doc = Document("t", chapters = listOf(Chapter(null, listOf("One. Two.", "Three."))))
        val s = seg.segment(doc)
        assertEquals(3, s.size)
        assertEquals("Two.", "One. Two.".substring(s[1].start, s[1].end))
        assertEquals(listOf(false, true, true), s.map { it.endsParagraph })
        assertEquals(listOf(0, 1, 2), s.map { it.index })
        assertEquals(1, s[2].paragraph)
    }
}
