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
) {
    val isEmpty: Boolean get() = chapters.all { it.paragraphs.isEmpty() }

    fun plainText(): String = chapters.joinToString("\n\n") { it.paragraphs.joinToString("\n\n") }

    fun wordCount(): Int = chapters.sumOf { c -> c.paragraphs.sumOf { ListeningTime.words(it) } }
}

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
