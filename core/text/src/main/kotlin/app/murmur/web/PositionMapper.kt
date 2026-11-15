package app.murmur.web

import app.murmur.text.Document
import app.murmur.text.Segment

/**
 * Keeps the listening position when an article is reloaded and its segments shift: remembers
 * the current paragraph's text and finds the same paragraph again in the new version.
 */
object PositionMapper {

    data class Anchor(val paragraph: String, val offset: Int, val fraction: Float)

    fun anchor(document: Document, segments: List<Segment>, index: Int): Anchor? {
        val seg = segments.getOrNull(index) ?: return null
        val paragraph = document.chapters.getOrNull(seg.chapter)?.paragraphs?.getOrNull(seg.paragraph) ?: return null
        return Anchor(paragraph, seg.start, if (segments.isEmpty()) 0f else index.toFloat() / segments.size)
    }

    /** The segment index in the new version that best matches [anchor]. */
    fun map(anchor: Anchor, document: Document, segments: List<Segment>): Int {
        if (segments.isEmpty()) return 0
        val paragraphs = document.chapters.flatMapIndexed { c, ch -> ch.paragraphs.mapIndexed { p, text -> Triple(c, p, text) } }
        val exact = paragraphs.firstOrNull { it.third == anchor.paragraph }
        val match = exact ?: run {
            val target = tokens(anchor.paragraph)
            if (target.isEmpty()) return@run null
            paragraphs.map { it to similarity(target, tokens(it.third)) }
                .filter { it.second >= 0.6 }
                .maxByOrNull { it.second }?.first
        }
        if (match != null) {
            val (c, p, _) = match
            val inParagraph = segments.filter { it.chapter == c && it.paragraph == p }
            if (inParagraph.isNotEmpty()) {
                return (inParagraph.lastOrNull { it.start <= anchor.offset } ?: inParagraph.first()).index
            }
        }
        return (anchor.fraction * segments.size).toInt().coerceIn(0, segments.size - 1)
    }

    private fun tokens(s: String): Set<String> = Regex("""[\p{L}\p{N}]+""").findAll(s.lowercase()).map { it.value }.toSet()

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }
}
