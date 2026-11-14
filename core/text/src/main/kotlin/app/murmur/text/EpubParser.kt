package app.murmur.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Minimal EPUB 2/3 reader: container.xml → OPF → spine order → paragraphs per chapter. */
object EpubParser {

    fun parse(file: File): Document = ZipFile(file).use { zip ->
        fun read(path: String): String? =
            zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

        val container = read("META-INF/container.xml") ?: error("Not an EPUB: missing container.xml")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
            .select("rootfile").firstOrNull()?.attr("full-path")
            ?.takeIf { it.isNotBlank() } ?: error("EPUB has no rootfile")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = Jsoup.parse(read(opfPath) ?: error("Missing $opfPath"), "", Parser.xmlParser())

        val title = opf.selectAny("dc|title", "title").firstOrNull()?.text()?.trim().orEmpty()
        val author = opf.selectAny("dc|creator", "creator").firstOrNull()?.text()?.trim()

        val manifest = opf.selectAny("manifest > item", "opf|manifest > opf|item")
            .associate { it.attr("id") to it }
        val spine = opf.selectAny("spine > itemref", "opf|spine > opf|itemref").map { it.attr("idref") }

        val chapters = spine.mapNotNull { idref ->
            val item = manifest[idref] ?: return@mapNotNull null
            if (item.attr("linear") == "no") return@mapNotNull null
            val type = item.attr("media-type")
            if (type.isNotEmpty() && "html" !in type) return@mapNotNull null
            val href = resolve(opfDir, item.attr("href"))
            val html = read(href) ?: return@mapNotNull null
            val body = Jsoup.parse(html).body()
            val paragraphs = HtmlParagraphs.extract(body)
            if (paragraphs.isEmpty()) null else Chapter(HtmlParagraphs.firstHeading(body), paragraphs)
        }
        Document(
            title = title.ifBlank { file.nameWithoutExtension },
            author = author?.takeIf { it.isNotBlank() },
            chapters = chapters,
        )
    }

    internal fun resolve(baseDir: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts += baseDir.split('/')
        for (p in clean.split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += p
            }
        }
        return parts.joinToString("/")
    }

    private fun Element.selectAny(vararg queries: String): List<Element> {
        for (q in queries) {
            val r = runCatching { select(q) }.getOrNull()
            if (!r.isNullOrEmpty()) return r
        }
        return emptyList()
    }
}
