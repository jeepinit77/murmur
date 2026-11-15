package app.murmur.web

import org.jsoup.nodes.Element

/**
 * Turns an HTML table into sentences that make sense when heard.
 *
 * TODO: Add an on-device AI implementation (ML Kit GenAI / Gemini Nano, on supported phones)
 *  that summarizes large tables in plain language, and use it in place of [TableReader] when available.
 */
interface TableSummarizer {
    /** Paragraphs to speak in place of [table]. */
    fun describe(table: Element): List<String>
}

/** Rule-based [TableSummarizer]: small tables row by row, large ones as a short description. */
object TableReader : TableSummarizer {
    const val MAX_ROWS = 8
    const val MAX_COLUMNS = 5

    /** True for tables used for page layout rather than data; their contents are ordinary text. */
    fun isLayout(table: Element): Boolean {
        if (table.attr("role").equals("presentation", ignoreCase = true)) return true
        if (table.selectFirst("table table") != null || table.select("table").size > 1) return true
        val rows = rows(table)
        if (rows.isEmpty()) return true
        if (rows.maxOf { cells(it).size } <= 1) return true
        return rows.any { r -> cells(r).any { c -> c.text().length > 300 || c.selectFirst("p, div > p, ul, ol") != null } }
    }

    override fun describe(table: Element): List<String> {
        val rows = rows(table).map { r -> cells(r).map { it.text().trim() } }.filter { r -> r.any { it.isNotEmpty() } }
        if (rows.isEmpty()) return emptyList()
        val caption = table.selectFirst("caption")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val first = rows(table).firstOrNull()
        val hasHeader = table.selectFirst("thead") != null ||
            (first != null && cells(first).isNotEmpty() && cells(first).all { it.normalName() == "th" })
        val header = if (hasHeader) rows.first() else null
        val body = if (hasHeader) rows.drop(1) else rows
        val columns = rows.maxOf { it.size }
        val out = ArrayList<String>()

        if (body.size <= MAX_ROWS && columns <= MAX_COLUMNS) {
            out += buildString {
                append("Table")
                if (caption != null) append(": ").append(caption.trimEnd('.'))
                append('.')
            }
            body.forEach { row ->
                val line = if (header != null) {
                    row.mapIndexedNotNull { i, v ->
                        val h = header.getOrNull(i).orEmpty()
                        when {
                            v.isEmpty() -> null
                            h.isEmpty() -> v
                            else -> "$h: $v"
                        }
                    }.joinToString(". ")
                } else {
                    row.filter { it.isNotEmpty() }.joinToString(", ")
                }
                if (line.isNotBlank()) out += line.trimEnd('.') + "."
            }
            out += "End of table."
        } else {
            out += buildString {
                append("Table with ${body.size} rows and $columns columns")
                if (caption != null) append(": ").append(caption.trimEnd('.'))
                append('.')
            }
            header?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { out += "Columns: ${it.joinToString(", ")}." }
        }
        return out
    }

    private fun rows(table: Element): List<Element> =
        table.select("tr").filter { it.closest("table") === table }

    private fun cells(row: Element): List<Element> = row.children().filter { it.normalName() == "td" || it.normalName() == "th" }
}
