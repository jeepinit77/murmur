package app.murmur.web

import app.murmur.text.Document

enum class TableMode { SKIP, READ }

enum class CodeMode { SKIP, NOTICE, READ }

/** How a web page is turned into something to listen to. Mirrors the "Web articles" settings. */
data class ExtractOptions(
    /** Split at the article's section headings so chapter navigation works. */
    val headingsAsChapters: Boolean = true,
    /** Read the subtitle, author, site and date after the title. */
    val spokenIntro: Boolean = false,
    /** Read figure captions and meaningful image descriptions. */
    val readCaptions: Boolean = false,
    val tables: TableMode = TableMode.SKIP,
    val code: CodeMode = CodeMode.SKIP,
) {
    companion object {
        /** What is spoken in place of a code block in [CodeMode.NOTICE]. */
        const val CODE_NOTICE = "A code block appears here."
    }
}

/** The result of extracting one web page. */
data class WebArticle(
    val document: Document,
    /** The page's own canonical address, when it declares one. */
    val canonicalUrl: String? = null,
    /** The next page of a multi-page article, on the same site. */
    val nextPageUrl: String? = null,
    val quality: ExtractionQuality = ExtractionQuality(document.wordCount(), needsRender = false, reason = null),
)

/** How trustworthy an extraction looks; decides whether to retry with a rendering browser. */
data class ExtractionQuality(
    val wordCount: Int,
    /** The page probably needs JavaScript (or a consent click) before its text appears. */
    val needsRender: Boolean,
    val reason: String?,
) {
    /** Higher is better; used to pick between the fetched and the rendered result. */
    val score: Int get() = if (needsRender) wordCount / 4 else wordCount
}
