package app.murmur.text

import java.text.Normalizer

/** One occurrence of a query: `chapters[chapter].paragraphs[paragraph]`, characters `start until end`. */
data class TextMatch(val chapter: Int, val paragraph: Int, val start: Int, val end: Int)

/** A short excerpt around a match, with the match's position inside [text]. */
data class Snippet(val text: String, val start: Int, val end: Int)

/**
 * Plain substring search that ignores case, accents and curly-vs-straight quotes, so "cafe"
 * finds "Café" and "don't" finds "don’t". Folding maps each character to exactly one
 * character, so match offsets index straight into the original text.
 */
object TextSearch {
    /** Folds [text] for comparison; the result has the same length as [text]. */
    fun fold(text: String): String {
        val out = CharArray(text.length)
        for (i in text.indices) out[i] = foldChar(text[i])
        return String(out)
    }

    private fun foldChar(c: Char): Char {
        when (c) {
            '‘', '’', '‚', '′' -> return '\''
            '“', '”', '„', '″' -> return '"'
            '‐', '‑', '‒', '–', '—' -> return '-'
            ' ' -> return ' '
        }
        if (c.code < 128) return c.lowercaseChar()
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
        return base.lowercaseChar()
    }

    /** The folded query, or null when there's nothing to search for. */
    fun prepare(query: String): String? = fold(query.trim()).takeIf { it.isNotEmpty() }

    /** Start offsets of [folded] in [text], without overlaps. */
    fun indicesIn(text: String, folded: String): List<Int> {
        if (folded.isEmpty() || text.length < folded.length) return emptyList()
        val hay = fold(text)
        val found = ArrayList<Int>()
        var from = 0
        while (true) {
            val i = hay.indexOf(folded, from)
            if (i < 0) break
            found += i
            from = i + folded.length
        }
        return found
    }

    fun contains(text: String, folded: String): Boolean =
        folded.isNotEmpty() && fold(text).contains(folded)

    /** Every match of [query] in the document's paragraphs, in reading order. */
    fun find(document: Document, query: String, limit: Int = Int.MAX_VALUE): List<TextMatch> {
        val folded = prepare(query) ?: return emptyList()
        val matches = ArrayList<TextMatch>()
        document.chapters.forEachIndexed { c, chapter ->
            chapter.paragraphs.forEachIndexed { p, text ->
                for (start in indicesIn(text, folded)) {
                    matches += TextMatch(c, p, start, start + folded.length)
                    if (matches.size >= limit) return matches
                }
            }
        }
        return matches
    }

    /** About [radius] characters either side of a match, cut at word boundaries, with ellipses. */
    fun snippet(text: String, start: Int, end: Int, radius: Int = 60): Snippet {
        var from = (start - radius).coerceAtLeast(0)
        var to = (end + radius).coerceAtMost(text.length)
        if (from > 0) text.indexOf(' ', from).takeIf { it in 0 until start }?.let { from = it + 1 }
        if (to < text.length) text.lastIndexOf(' ', to).takeIf { it >= end }?.let { to = it }
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        val body = text.substring(from, to).replace('\n', ' ')
        return Snippet(prefix + body + suffix, start - from + prefix.length, end - from + prefix.length)
    }
}
