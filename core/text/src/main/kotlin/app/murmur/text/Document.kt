package app.murmur.text

import kotlinx.serialization.Serializable

/** A readable document: an article, a pasted snippet, a PDF or an EPUB book. */
@Serializable
data class Document(
    val title: String,
    val author: String? = null,
    val chapters: List<Chapter>,
    /** Web articles: the page address the text came from. */
    val url: String? = null,
    /** Web articles: the publication, e.g. "The Atlantic". */
    val siteName: String? = null,
    /** Web articles: publish date as an ISO-8601 string, when the page states one. */
    val published: String? = null,
    /** Primary language subtag ("en", "fr"), when known. */
    val language: String? = null,
    /** True when the article looks cut off by a paywall or sign-in prompt. */
    val incomplete: Boolean = false,
    /** Tables read row by row for now, to be summarized when the on-device model can run. */
    val pendingTables: List<PendingTable> = emptyList(),
) {
    val isEmpty: Boolean get() = chapters.all { it.paragraphs.isEmpty() }

    /**
     * Replaces the first run of paragraphs equal to [old] with [new], or returns null when no
     * chapter contains that run.
     */
    fun replaceParagraphs(old: List<String>, new: List<String>): Document? {
        if (old.isEmpty()) return null
        chapters.forEachIndexed { c, chapter ->
            val p = chapter.paragraphs
            for (start in 0..p.size - old.size) {
                if (p.subList(start, start + old.size) == old) {
                    val updated = chapter.copy(paragraphs = p.subList(0, start) + new + p.subList(start + old.size, p.size))
                    return copy(chapters = chapters.toMutableList().also { it[c] = updated })
                }
            }
        }
        return null
    }

    fun plainText(): String = chapters.joinToString("\n\n") { it.paragraphs.joinToString("\n\n") }

    fun wordCount(): Int = chapters.sumOf { c -> c.paragraphs.sumOf { ListeningTime.words(it) } }
}

/**
 * A table that couldn't be summarized at import time, for example because the on-device model
 * only runs while Murmur is on screen.
 */
@Serializable
data class PendingTable(
    /** The table as plain text for the model. */
    val text: String,
    val caption: String? = null,
    /** The row-by-row paragraphs in the document now, replaced by the summary later. */
    val paragraphs: List<String>,
)

@Serializable
data class Chapter(
    val title: String?,
    val paragraphs: List<String>,
)

/**
 * The unit of speech. Offsets refer to `chapters[chapter].paragraphs[paragraph]`,
 * so the reader UI can highlight exactly what is being spoken.
 */
@Serializable
data class Segment(
    val index: Int,
    val chapter: Int,
    val paragraph: Int,
    val start: Int,
    val end: Int,
    val text: String,
    /** True for the last segment of a paragraph; the player adds a slightly longer pause. */
    val endsParagraph: Boolean,
)
