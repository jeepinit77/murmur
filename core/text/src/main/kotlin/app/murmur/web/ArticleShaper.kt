package app.murmur.web

import app.murmur.text.Chapter
import app.murmur.text.HtmlParagraphs
import org.jsoup.nodes.Element

/**
 * Shapes cleaned article HTML into chapters for listening: applies the table, code and caption
 * settings, splits at section headings, and filters page furniture out of the paragraphs.
 */
internal object ArticleShaper {

    /** Marks a paragraph that came from a section heading; never appears in real text. */
    private const val HEADING_MARK = ''
    /** Marks paragraphs Murmur wrote itself (table rows, captions, notices) so clutter rules leave them alone. */
    private const val GENERATED_MARK = ''
    private val IMAGE_FILE = Regex("""\.(jpe?g|png|gif|webp|svg|avif)$""", RegexOption.IGNORE_CASE)
    private val GENERIC_ALT = setOf("image", "photo", "picture", "img", "graphic", "illustration", "thumbnail", "logo", "icon")

    /** @param tables describes tables in [TableMode.SUMMARIZE]; [TableMode.READ] always uses [TableReader]. */
    fun applyOptions(root: Element, options: ExtractOptions, tables: TableSummarizer = TableReader) {
        applyCode(root, options.code)
        applyTables(root, options.tables, tables)
        applyCaptions(root, options.readCaptions)
    }

    /**
     * Splits [root] into chapters. The first chapter starts with [lead] (title, intro lines);
     * each section heading starts a new chapter whose first paragraph is the heading itself.
     */
    fun chapters(root: Element, options: ExtractOptions, title: String, lead: List<String>): List<Chapter> {
        if (options.headingsAsChapters) markHeadings(root)
        val paragraphs = clean(HtmlParagraphs.extract(root))
            .dropWhile { it.trimStart(HEADING_MARK).equals(title, ignoreCase = true) }
        return build(paragraphs, lead)
    }

    /** Groups already-extracted paragraphs, where heading paragraphs carry [HEADING_MARK]. */
    private fun build(paragraphs: List<String>, lead: List<String>): List<Chapter> {
        val out = ArrayList<Chapter>()
        var currentTitle: String? = null
        var current = ArrayList(lead)
        fun close() {
            val hasBody = if (currentTitle == null) current.isNotEmpty() else current.size > 1
            if (hasBody) out += Chapter(currentTitle, current)
        }
        for (p in paragraphs) {
            if (p.firstOrNull() == HEADING_MARK) {
                close()
                val heading = p.drop(1).trim()
                currentTitle = heading
                current = arrayListOf(heading)
            } else {
                current += p
            }
        }
        close()
        return out
    }

    /** Builds a chapter list from sections that are already separate (Wikipedia, comment threads). */
    fun section(root: Element, title: String?): Chapter? {
        val paragraphs = clean(HtmlParagraphs.extract(root))
        if (paragraphs.isEmpty()) return null
        val body = if (title != null && paragraphs.first().equals(title, ignoreCase = true)) paragraphs else listOfNotNull(title) + paragraphs
        return Chapter(title, body)
    }

    /** Removes the generated mark from paragraphs, for sources that skip the web clutter rules. */
    fun unmark(raw: List<String>): List<String> =
        raw.map { p -> if (p.firstOrNull() == GENERATED_MARK) p.drop(1).trim() else p }.filter { it.isNotEmpty() }

    /** Drops clutter and pronunciation notes; keeps heading marks, removes generated marks. */
    fun clean(raw: List<String>): List<String> = raw.mapNotNull { p ->
        when (p.firstOrNull()) {
            HEADING_MARK -> p
            GENERATED_MARK -> p.drop(1).trim().takeIf { it.isNotEmpty() }
            else -> BoilerplateStripper.cleanParagraph(p).takeIf { it.isNotBlank() && !BoilerplateStripper.isClutter(it) }
        }
    }

    /** Replaces [target] with one generated paragraph per entry of [lines]. */
    internal fun replaceWithParagraphs(target: Element, lines: List<String>) {
        lines.forEach { target.before(Element("p").text("$GENERATED_MARK$it")) }
        target.remove()
    }

    private fun markHeadings(root: Element) {
        val level = when {
            root.selectFirst("h2") != null -> "h2"
            root.selectFirst("h3") != null -> "h3"
            else -> return
        }
        root.select(level).forEach { h ->
            val text = h.text().trim()
            if (text.isEmpty()) h.remove() else h.replaceWith(Element("p").text("$HEADING_MARK$text"))
        }
    }

    private fun applyCode(root: Element, mode: CodeMode) {
        val blocks = root.select("pre") + root.select("code").filter { it.closest("pre") == null && it.wholeText().contains('\n') }
        blocks.forEach { block ->
            if (block.parent() == null) return@forEach
            when (mode) {
                CodeMode.SKIP -> block.remove()
                CodeMode.NOTICE -> replaceWithParagraphs(block, listOf(ExtractOptions.CODE_NOTICE))
                CodeMode.READ -> replaceWithParagraphs(block, block.wholeText().lines().map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        // Several code blocks in a row only need one notice.
        if (mode == CodeMode.NOTICE) {
            root.select("p").forEach { p ->
                val notice = "$GENERATED_MARK${ExtractOptions.CODE_NOTICE}"
                if (p.parent() != null && p.text() == notice && p.previousElementSibling()?.text() == notice) p.remove()
            }
        }
    }

    private fun applyTables(root: Element, mode: TableMode, summarizer: TableSummarizer) =
        TableShaping.apply(root, mode, summarizer)

    private fun applyCaptions(root: Element, read: Boolean) {
        if (!read) {
            root.select("figcaption").remove()
            root.select("[class*=caption], [class*=credit]").forEach { if (it.parent() != null && it.text().length < 400) it.remove() }
            return
        }
        root.select("figcaption").forEach { cap ->
            val text = cap.text().trim()
            if (text.isEmpty()) cap.remove() else replaceWithParagraphs(cap, listOf("Image: ${text.trimEnd('.')}."))
        }
        root.select("img[alt]").forEach { img ->
            if (img.closest("figure")?.selectFirst("p, figcaption") != null) return@forEach
            val alt = img.attr("alt").trim()
            val meaningful = alt.split(Regex("\\s+")).size >= 3 && !IMAGE_FILE.containsMatchIn(alt) && alt.lowercase() !in GENERIC_ALT
            if (meaningful) replaceWithParagraphs(img, listOf("Image: ${alt.trimEnd('.')}."))
        }
    }
}

/** Applies the Tables setting to data tables in any HTML: web pages, pasted Markdown, HTML files, EPUBs. */
object TableShaping {
    /**
     * Skips, reads or summarizes each data table in [root] according to [mode]. Layout tables are
     * left as ordinary text. Generated paragraphs carry a mark; remove it with [ArticleShaper.unmark]
     * or [ArticleShaper.clean] after [app.murmur.text.HtmlParagraphs.extract].
     */
    fun apply(root: Element, mode: TableMode, summarizer: TableSummarizer = TableReader) {
        root.select("table").forEach { table ->
            if (table.parent() == null || TableReader.isLayout(table)) return@forEach
            // Nested inside a data table that has already been handled.
            if (table.parents().any { it.normalName() == "table" && !TableReader.isLayout(it) }) return@forEach
            when (mode) {
                TableMode.SKIP -> table.remove()
                TableMode.READ -> ArticleShaper.replaceWithParagraphs(table, TableReader.describe(table))
                TableMode.SUMMARIZE -> ArticleShaper.replaceWithParagraphs(table, summarizer.describe(table))
            }
        }
    }
}
