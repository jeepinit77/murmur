package app.murmur.text

/**
 * Turns raw per-page PDF text (hard-wrapped lines, running headers, page numbers,
 * hyphenated line breaks) into flowing paragraphs suitable for listening.
 */
object PdfTextCleaner {

    private val PAGE_NUMBER = Regex("""^(page\s*)?\d{1,4}(\s*(of|/)\s*\d{1,4})?$""", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("""[.!?:"”’)]$""")

    fun clean(title: String, pages: List<String>): Document {
        val pageLines = pages.map { page -> page.replace("\r", "").lines().map { it.trim() } }
        val repeated = repeatedLines(pageLines)
        val lines = pageLines.flatMap { page ->
            page.filterNot { it in repeated || PAGE_NUMBER.matches(it) } + ""
        }
        val typical = lines.filter { it.length > 20 }.map { it.length }.sorted()
            .let { if (it.isEmpty()) 80 else it[it.size * 3 / 4] }

        val paragraphs = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            val p = current.toString().replace(Regex("\\s+"), " ").trim()
            if (p.isNotEmpty() && p.any { it.isLetterOrDigit() }) paragraphs += p
            current.setLength(0)
        }
        for (line in lines) {
            if (line.isEmpty()) {
                // A blank line only ends a paragraph if the text so far reads as finished;
                // otherwise it is a page break in the middle of a sentence.
                if (SENTENCE_END.containsMatchIn(current.trimEnd())) flush()
                continue
            }
            if (current.endsWith("-") && current.length > 1 && current[current.length - 2].isLetter() &&
                line.first().isLowerCase()
            ) {
                current.setLength(current.length - 1) // de-hyphenate "exam-\nple"
                current.append(line)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(line)
            }
            // A short line that ends a sentence is the end of a paragraph.
            if (line.length < typical * 0.7 && SENTENCE_END.containsMatchIn(line)) flush()
        }
        flush()
        return Document(title = title, chapters = listOf(Chapter(null, paragraphs)))
    }

    /** Lines that appear at the top/bottom of at least half the pages (running headers/footers). */
    private fun repeatedLines(pages: List<List<String>>): Set<String> {
        if (pages.size < 3) return emptySet()
        val counts = HashMap<String, Int>()
        for (page in pages) {
            val nonEmpty = page.filter { it.isNotEmpty() && !PAGE_NUMBER.matches(it) }
            val edges = (nonEmpty.take(1) + nonEmpty.takeLast(1)).map { it.replace(Regex("\\d+"), "#") }.toSet()
            edges.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val frequent = counts.filter { it.value >= pages.size / 2 && it.key.length <= 80 }.keys
        return pages.flatMap { page ->
            val nonEmpty = page.filter { it.isNotEmpty() && !PAGE_NUMBER.matches(it) }
            nonEmpty.take(1) + nonEmpty.takeLast(1)
        }.filter { it.replace(Regex("\\d+"), "#") in frequent }.toSet()
    }
}
