package app.murmur.text

import java.text.BreakIterator
import java.util.Locale

/**
 * Splits a [Document] into speakable [Segment]s: one sentence each, with long
 * sentences broken at clause boundaries so neural models (which have a token
 * limit and whose latency grows with input length) get manageable chunks.
 */
class SentenceSegmenter(
    private val locale: Locale = Locale.US,
    private val maxChars: Int = 280,
) {

    fun segment(document: Document): List<Segment> {
        val out = ArrayList<Segment>()
        document.chapters.forEachIndexed { c, chapter ->
            chapter.paragraphs.forEachIndexed { p, paragraph ->
                val ranges = sentenceRanges(paragraph)
                ranges.forEachIndexed { i, r ->
                    out += Segment(
                        index = out.size,
                        chapter = c,
                        paragraph = p,
                        start = r.first,
                        end = r.last + 1,
                        text = paragraph.substring(r.first, r.last + 1),
                        endsParagraph = i == ranges.lastIndex,
                    )
                }
            }
        }
        return out
    }

    /** Character ranges (inclusive) of each speakable piece of [text], trimmed of whitespace. */
    fun sentenceRanges(text: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        val raw = ArrayList<IntRange>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            raw += start until end
            start = end
            end = it.next()
        }
        val merged = mergeAbbreviations(text, raw)
        return merged.flatMap { splitLong(text, it) }.mapNotNull { trim(text, it) }
    }

    private fun mergeAbbreviations(text: String, ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return ranges
        val out = ArrayList<IntRange>()
        var current = ranges[0]
        for (i in 1 until ranges.size) {
            val piece = text.substring(current.first, current.last + 1).trimEnd()
            val lastWord = piece.substringAfterLast(' ').lowercase(locale)
            if (lastWord in ABBREVIATIONS || INITIAL.matches(lastWord)) {
                current = current.first..ranges[i].last
            } else {
                out += current
                current = ranges[i]
            }
        }
        out += current
        return out
    }

    private fun splitLong(text: String, range: IntRange): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = range.first
        val end = range.last + 1
        while (end - start > maxChars) {
            val window = text.substring(start, start + maxChars)
            val cut = findCut(window, minPos = maxChars / 3)
            out += start until start + cut
            start += cut
        }
        if (start < end) out += start until end
        return out
    }

    private fun trim(text: String, range: IntRange): IntRange? {
        var s = range.first
        var e = range.last
        while (s <= e && text[s].isWhitespace()) s++
        while (e >= s && text[e].isWhitespace()) e--
        if (s > e) return null
        // Skip pieces with nothing pronounceable (stray bullets, lone punctuation).
        if ((s..e).none { text[it].isLetterOrDigit() }) return null
        return s..e
    }

    companion object {
        private val INITIAL = Regex("^\\(?[a-z]\\.$")
        private val ABBREVIATIONS = setOf(
            "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.", "vs.", "etc.",
            "e.g.", "i.e.", "no.", "fig.", "approx.", "dept.", "inc.", "ltd.", "co.",
            "jan.", "feb.", "mar.", "apr.", "jun.", "jul.", "aug.", "sep.", "sept.",
            "oct.", "nov.", "dec.", "u.s.", "u.k.", "gen.", "gov.", "sen.", "rep.", "mt.",
        )

        private val CLAUSE_PUNCT = charArrayOf(';', ':', ',', '—', '–', ')')

        /**
         * Picks a cut position (exclusive) inside [window]: preferably right after the last
         * clause punctuation at or beyond [minPos], else at the last space, else hard cut.
         */
        internal fun findCut(window: String, minPos: Int): Int {
            for (i in window.length - 1 downTo minPos) {
                if (window[i] in CLAUSE_PUNCT && (i + 1 == window.length || window[i + 1].isWhitespace())) {
                    return i + 1
                }
            }
            val space = window.lastIndexOf(' ')
            return if (space >= minPos) space + 1 else window.length
        }
    }
}
