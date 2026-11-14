package app.murmur.text

import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup

/** Turns a downloaded web page into a clean, readable [Document] (Mozilla Readability port). */
object WebArticleExtractor {

    fun extract(url: String, html: String): Document {
        val article = runCatching { Readability4J(url, html).parse() }.getOrNull()
        val content = article?.content
        val title = article?.title?.takeIf { it.isNotBlank() }
            ?: Jsoup.parse(html).title().takeIf { it.isNotBlank() }
            ?: url
        val paragraphs = if (!content.isNullOrBlank()) {
            HtmlParagraphs.extract(Jsoup.parseBodyFragment(content, url).body())
        } else {
            HtmlParagraphs.extract(Jsoup.parse(html, url).body())
        }
        val body = paragraphs.dropWhile { it.equals(title, ignoreCase = true) }
        return Document(
            title = title.trim(),
            author = article?.byline?.takeIf { it.isNotBlank() }?.trim(),
            chapters = listOf(Chapter(title = null, paragraphs = listOf(title.trim()) + body)),
        )
    }
}
