package app.murmur.text

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Pasted Markdown, such as answers copied from ChatGPT: turned into HTML so tables, lists and
 * headings read naturally and markup characters and image addresses aren't spoken.
 */
object MarkdownText {

    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).build()

    private val TABLE_DELIMITER = Regex("""(?m)^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$""")
    private val TABLE_ROW = Regex("""(?m)^\s*\|.*\|\s*$""")
    private val IMAGE = Regex("""!\[[^\]\n]*]\([^)\s]+\)""")
    private val LINK = Regex("""\[[^\]\n]+]\(https?://[^)\s]+\)""")
    private val HEADING = Regex("""(?m)^#{1,6}\s+\S""")
    private val BOLD = Regex("""\*\*[^*\n]+\*\*""")
    private val ESCAPE = Regex("""\\[*_#.\-\[\]()!`|]""")
    private val BULLET = Regex("""(?m)^\s*[*+-]\s+\S""")
    private val FENCE = Regex("""(?m)^\s*```""")

    /**
     * True when [text] is clearly Markdown: a pipe table, an image or link with an address, or at
     * least two other signs (headings, bold, backslash escapes, bullets, code fences). Ordinary
     * prose stays on the plain-text path.
     */
    fun looksLikeMarkdown(text: String): Boolean {
        if (TABLE_DELIMITER.containsMatchIn(text) && TABLE_ROW.containsMatchIn(text)) return true
        if (IMAGE.containsMatchIn(text) || LINK.containsMatchIn(text)) return true
        val signs = listOf(HEADING, BOLD, ESCAPE, BULLET, FENCE).count { it.containsMatchIn(text) }
        return signs >= 2
    }

    /** The Markdown as an HTML body, without images (their addresses shouldn't be read). */
    fun toHtml(text: String): Element {
        val html = renderer.render(parser.parse(text))
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("img").remove()
        return body
    }
}
