package app.murmur.text

import app.murmur.web.TableMode
import app.murmur.web.TableReader
import app.murmur.web.TableSummarizer
import org.jsoup.nodes.Element

/** Builds documents from shared/pasted text. */
object PlainText {

    /**
     * Returns the URL if the shared text is essentially a link share (as Chrome and most apps
     * share pages, sometimes as "Title\nhttps://…"), so the caller can fetch the article.
     */
    fun sharedUrl(text: String): String? = app.murmur.web.SharedLink.parse(text)?.url

    /**
     * Pasted or shared text. Markdown (as copied from ChatGPT) and tab-separated tables (as copied
     * from a rendered table) have their tables skipped, read or summarized according to [tables].
     */
    fun toDocument(
        text: String,
        title: String? = null,
        tables: TableMode = TableMode.READ,
        summarizer: TableSummarizer = TableReader,
    ): Document {
        val normalized = text.replace("\r\n", "\n")
        val paragraphs = when {
            MarkdownText.looksLikeMarkdown(normalized) -> HtmlText.paragraphs(MarkdownText.toHtml(normalized), tables, summarizer)
            TAB_ROW.containsMatchIn(normalized) -> withTabTables(normalized, tables, summarizer)
            else -> paragraphs(normalized)
        }
        return fromParagraphs(paragraphs, title)
    }

    /** A single-chapter document, titled [title] or after its first paragraph. */
    fun fromParagraphs(paragraphs: List<String>, title: String? = null): Document {
        val docTitle = title?.takeIf { it.isNotBlank() }?.trim()
            ?: paragraphs.firstOrNull()?.let { if (it.length <= 80) it else it.take(60).substringBeforeLast(' ') + "…" }
            ?: "Untitled"
        return Document(title = docTitle, chapters = listOf(Chapter(null, paragraphs)))
    }

    private fun paragraphs(text: String): List<String> = text
        .split(Regex("\\n\\s*\\n"))
        .flatMap { block ->
            // Single newlines inside a block: keep list-like lines separate, join wrapped prose.
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size > 1 && (lines.all { LIST_ITEM.containsMatchIn(it) } || lines.all { it.length < 40 })) lines
            else listOf(lines.joinToString(" "))
        }
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }

    /** Turns runs of two or more lines with the same number of tabs into tables; the rest is ordinary text. */
    private fun withTabTables(text: String, tables: TableMode, summarizer: TableSummarizer): List<String> {
        val body = Element("body")
        val lines = text.lines()
        val pending = StringBuilder()
        fun flushText() {
            paragraphs(pending.toString()).forEach { body.appendElement("p").text(it) }
            pending.setLength(0)
        }
        var i = 0
        while (i < lines.size) {
            val tabs = lines[i].count { it == '\t' }
            var end = i
            if (tabs > 0) while (end + 1 < lines.size && lines[end + 1].count { it == '\t' } == tabs) end++
            if (tabs > 0 && end > i) {
                flushText()
                val table = body.appendElement("table")
                for ((n, line) in lines.subList(i, end + 1).withIndex()) {
                    val row = (if (n == 0) table.appendElement("thead") else table).appendElement("tr")
                    line.split('\t').forEach { row.appendElement(if (n == 0) "th" else "td").text(it.trim()) }
                }
                i = end + 1
            } else {
                pending.append(lines[i]).append('\n')
                i++
            }
        }
        flushText()
        return HtmlText.paragraphs(body, tables, summarizer)
    }

    private val LIST_ITEM = Regex("""^([-*•]|\d+[.)])\s""")
    private val TAB_ROW = Regex("""(?m)^[^\t\n]*\t[^\n]*\n[^\t\n]*\t""")
}
