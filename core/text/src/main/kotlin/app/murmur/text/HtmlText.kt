package app.murmur.text

import app.murmur.web.ArticleShaper
import app.murmur.web.TableMode
import app.murmur.web.TableReader
import app.murmur.web.TableShaping
import app.murmur.web.TableSummarizer
import org.jsoup.nodes.Element

/** Builds a single-chapter document from HTML that isn't a web article: shared HTML, HTML files, pasted Markdown. */
object HtmlText {

    fun toDocument(
        root: Element,
        title: String? = null,
        tables: TableMode = TableMode.READ,
        summarizer: TableSummarizer = TableReader,
    ): Document = PlainText.fromParagraphs(paragraphs(root, tables, summarizer), title)

    /** The readable paragraphs of [root] after applying the Tables setting. */
    fun paragraphs(root: Element, tables: TableMode, summarizer: TableSummarizer = TableReader): List<String> {
        TableShaping.apply(root, tables, summarizer)
        return ArticleShaper.unmark(HtmlParagraphs.extract(root))
    }
}
