package app.murmur.text

/** Builds documents from shared/pasted text. */
object PlainText {

    private val SINGLE_URL = Regex("""^\s*(https?://\S+)\s*$""", RegexOption.IGNORE_CASE)
    private val ANY_URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /**
     * Returns the URL if the shared text is essentially just a link (as Chrome and most apps
     * share pages, sometimes as "Title\nhttps://…"), so the caller can fetch the article.
     */
    fun sharedUrl(text: String): String? {
        SINGLE_URL.find(text)?.let { return it.groupValues[1] }
        val urls = ANY_URL.findAll(text).toList()
        if (urls.size != 1) return null
        val rest = text.replace(urls[0].value, "").trim()
        // "Page title https://…" style shares: the remainder is short, single-line.
        return if (rest.length <= 200 && !rest.contains('\n')) urls[0].value.trimEnd('.', ',', ')') else null
    }

    fun toDocument(text: String, title: String? = null): Document {
        val paragraphs = text
            .replace("\r\n", "\n")
            .split(Regex("\\n\\s*\\n"))
            .flatMap { block ->
                // Single newlines inside a block: keep list-like lines separate, join wrapped prose.
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.size > 1 && (lines.all { LIST_ITEM.containsMatchIn(it) } || lines.all { it.length < 40 })) lines
                else listOf(lines.joinToString(" "))
            }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        val docTitle = title?.takeIf { it.isNotBlank() }?.trim()
            ?: paragraphs.firstOrNull()?.let { if (it.length <= 80) it else it.take(60).substringBeforeLast(' ') + "…" }
            ?: "Untitled"
        return Document(title = docTitle, chapters = listOf(Chapter(null, paragraphs)))
    }

    private val LIST_ITEM = Regex("""^([-*•]|\d+[.)])\s""")
}
