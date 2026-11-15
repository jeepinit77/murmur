package app.murmur.web

import org.jsoup.nodes.Element

/**
 * Turns an HTML table into sentences that make sense when heard. [TableReader] is the rule-based
 * one; the play build adds an on-device AI summary (see [PrecomputedTableSummaries]).
 */
fun interface TableSummarizer {
    /** Paragraphs to speak in place of [table]. */
    fun describe(table: Element): List<String>
}

/**
 * Rule-based [TableSummarizer]: reads every row, each value after its column heading, up to
 * [MAX_READ_ROWS] rows.
 */
object TableReader : TableSummarizer {
    /** Tables with more rows announce their length first. */
    const val MAX_ROWS = 8
    /** Rows read before the rest are skipped with a notice. */
    const val MAX_READ_ROWS = 100

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
        val out = ArrayList<String>()

        out += buildString {
            append("Table")
            // Long tables say how long they are, so listeners know what they're in for.
            if (body.size > MAX_ROWS) append(" with ${body.size} rows")
            if (caption != null) append(": ").append(caption.trimEnd('.'))
            append('.')
        }
        body.take(MAX_READ_ROWS).forEach { row ->
            // Each value after its column heading: "Model: Lotos. Price: $249.98."
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
        if (body.size > MAX_READ_ROWS) out += "${body.size - MAX_READ_ROWS} more rows not read."
        out += "End of table."
        return out
    }

    /** Rows that hold at least one non-empty cell, header included. */
    fun rowCount(table: Element): Int = rows(table).count { r -> cells(r).any { it.text().isNotBlank() } }

    /**
     * The table as plain text for a language model: the caption, then one line per row with cells
     * separated by " | ". Stops at [maxChars] and says how many rows were left out.
     */
    fun toPlainText(table: Element, maxChars: Int = 3000, maxCellChars: Int = 80): String {
        val sb = StringBuilder()
        table.selectFirst("caption")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append("Caption: ").append(it).append('\n') }
        val lines = rows(table)
            .map { r -> cells(r).map { c -> c.text().trim().let { t -> if (t.length > maxCellChars) t.take(maxCellChars - 1) + "…" else t } } }
            .filter { r -> r.any { it.isNotEmpty() } }
            .map { it.joinToString(" | ") }
        for ((i, line) in lines.withIndex()) {
            if (sb.length + line.length + 1 > maxChars) {
                sb.append("(${lines.size - i} more rows not shown)")
                break
            }
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun rows(table: Element): List<Element> =
        table.select("tr").filter { it.closest("table") === table }

    private fun cells(row: Element): List<Element> = row.children().filter { it.normalName() == "td" || it.normalName() == "th" }
}

/**
 * Summaries worked out ahead of time, such as by an on-device language model, keyed by [key].
 * Tables without one are described by [fallback].
 */
class PrecomputedTableSummaries(
    private val summaries: Map<String, List<String>>,
    private val fallback: TableSummarizer = TableReader,
) : TableSummarizer {
    override fun describe(table: Element): List<String> = summaries[key(table)] ?: fallback.describe(table)

    companion object {
        private val SPACE = Regex("\\s+")

        /** Identifies a table by its text, so the same table matches across two extraction runs. */
        fun key(table: Element): String = table.text().replace(SPACE, " ").trim()
    }
}

/**
 * Describes tables with [fallback] and keeps a copy of each one, so they can be summarized
 * asynchronously and the page extracted again with [PrecomputedTableSummaries].
 */
class TableRecorder(private val fallback: TableSummarizer = TableReader) : TableSummarizer {
    private val _tables = ArrayList<Element>()
    val tables: List<Element> get() = _tables

    override fun describe(table: Element): List<String> {
        _tables += table.clone()
        return fallback.describe(table)
    }
}
