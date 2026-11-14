package app.murmur.text

import kotlinx.serialization.Serializable

/** A readable document: an article, a pasted snippet, a PDF or an EPUB book. */
@Serializable
data class Document(
    val title: String,
    val author: String? = null,
    val chapters: List<Chapter>,
) {
    val isEmpty: Boolean get() = chapters.all { it.paragraphs.isEmpty() }

    fun plainText(): String = chapters.joinToString("\n\n") { it.paragraphs.joinToString("\n\n") }
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
