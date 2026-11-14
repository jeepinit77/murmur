package app.murmur.text

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Flattens an HTML tree into readable paragraphs without duplicating nested blocks. */
object HtmlParagraphs {

    private val BLOCK = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "aside", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dt", "dd", "pre",
        "table", "tr", "td", "th", "tbody", "thead", "figure", "figcaption", "hr", "br",
        "body", "html", "nav",
    )
    private val SKIP = setOf(
        "script", "style", "noscript", "svg", "nav", "head", "title", "iframe", "button",
        "form", "input", "select", "textarea", "math", "sup", "rt", "rp",
    )
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    fun extract(root: Element): List<String> {
        val out = ArrayList<String>()
        val inline = StringBuilder()
        walk(root, out, inline)
        flush(out, inline)
        return out
    }

    /** First heading text inside [root], useful as a chapter title. */
    fun firstHeading(root: Element): String? =
        root.select(HEADINGS.joinToString(", ")).firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private fun walk(node: Node, out: MutableList<String>, inline: StringBuilder) {
        for (child in node.childNodes()) {
            when (child) {
                is TextNode -> inline.append(child.text())
                is Element -> {
                    val tag = child.normalName()
                    when {
                        tag in SKIP -> Unit
                        tag == "br" -> inline.append(' ')
                        tag in BLOCK -> {
                            flush(out, inline)
                            walk(child, out, inline)
                            flush(out, inline)
                        }
                        tag == "img" -> Unit
                        else -> walk(child, out, inline)
                    }
                }
            }
        }
    }

    private fun flush(out: MutableList<String>, inline: StringBuilder) {
        val text = inline.toString().replace(Regex("\\s+"), " ").trim()
        inline.setLength(0)
        if (text.isNotEmpty() && text.any { it.isLetterOrDigit() }) out += text
    }
}
