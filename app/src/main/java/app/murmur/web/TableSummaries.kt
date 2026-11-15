package app.murmur.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import app.murmur.text.Document
import app.murmur.text.PendingTable
import org.jsoup.nodes.Element

/**
 * An on-device language model that can summarize a table. The play build provides one on phones
 * that support Gemini Nano ([tableAi]); the foss build has none.
 */
interface TableAi {
    /** Whether the phone can summarize tables now, after a download, or not at all. */
    suspend fun status(): TableAiStatus

    /** Starts downloading the model if the phone needs it. */
    fun prepare() {}

    /**
     * A short spoken summary of a table given as plain text ([TableReader.toPlainText]), or null
     * when the model couldn't answer. Throws [TableAiUnavailableNow] when the model can't run at
     * the moment but may later.
     */
    suspend fun summarize(table: String): String?
}

/** The model can't run right now (Murmur isn't on screen, or the model is downloading); try again later. */
class TableAiUnavailableNow(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class TableAiStatus {
    READY,
    /** The model is supported but isn't on the phone yet; [TableAi.prepare] downloads it. */
    NEEDS_DOWNLOAD,
    DOWNLOADING,
    /** This phone can't run the model. */
    UNSUPPORTED,
    /** The status check failed; treated like [UNSUPPORTED]. */
    ERROR;

    /** The model runs now or will after its download. */
    val usable: Boolean get() = this == READY || this == NEEDS_DOWNLOAD || this == DOWNLOADING
}

/**
 * Runs the extraction for [TableMode.SUMMARIZE]: a first pass collects the article's tables, the
 * model summarizes them, and a second pass puts the summaries in place. Any table the model
 * doesn't summarize is read by [TableReader], and without a model the first pass is the result.
 */
object TableSummaries {
    /** Tables summarized per page; the rest are read by [TableReader]. */
    const val MAX_TABLES = 10
    /** Tables with fewer rows (header included) are read row by row; a summary wouldn't be shorter. */
    const val MIN_ROWS = 4
    /** Model failures after which the remaining tables are left to [TableReader]. */
    private const val MAX_FAILURES = 2
    private const val TIMEOUT_MS = 30_000L
    private const val MAX_SENTENCES = 5

    private val MARKDOWN = Regex("""[*_#`>]+""")
    private val BULLET = Regex("""(?m)^\s*(?:[-•]|\d+[.)])\s+""")
    private val PREAMBLE = Regex("""^(?:here(?:'s| is) (?:a |the )?(?:short |brief )?summary[^:]*:|summary:)\s*""", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")

    /**
     * @param onSummarizing called before the model starts, for progress text.
     * @param defer attaches tables the model can't summarize right now (Murmur isn't on screen, or
     *  the model is still downloading) to the result, so [completePending] can summarize them later.
     */
    suspend fun <T> extract(
        options: ExtractOptions,
        ai: TableAi?,
        onSummarizing: () -> Unit = {},
        defer: (T, List<PendingTable>) -> T = { result, _ -> result },
        extract: suspend (TableSummarizer) -> T,
    ): T {
        if (options.tables != TableMode.SUMMARIZE || ai == null) return extract(TableReader)
        val recorder = TableRecorder()
        val first = extract(recorder)
        if (recorder.tables.none { TableReader.rowCount(it) >= MIN_ROWS }) return first
        onSummarizing()
        val result = summarize(recorder.tables, ai, options.labelAiSummaries)
        val shaped = if (result.summaries.isEmpty()) first else extract(PrecomputedTableSummaries(result.summaries))
        return if (result.deferred.isEmpty()) shaped else defer(shaped, result.deferred)
    }

    /** Summaries keyed by [PrecomputedTableSummaries.key], and the tables to try again later. */
    class Result(val summaries: Map<String, List<String>>, val deferred: List<PendingTable>)

    suspend fun summarize(tables: List<Element>, ai: TableAi, byAi: Boolean = false): Result {
        val out = LinkedHashMap<String, List<String>>()
        val deferred = ArrayList<PendingTable>()
        var failures = 0
        val candidates = tables.filter { TableReader.rowCount(it) >= MIN_ROWS }.distinctBy(PrecomputedTableSummaries::key).take(MAX_TABLES)
        for ((i, table) in candidates.withIndex()) {
            val text = when (val answer = ask(ai, TableReader.toPlainText(table))) {
                Answer.Later -> {
                    // Every remaining table would hit the same wall; keep them all for later.
                    deferred += candidates.drop(i).map(::pending)
                    break
                }
                is Answer.Text -> answer.text
            }
            val summary = text?.let(::clean)
            if (summary == null) {
                if (++failures >= MAX_FAILURES) break
                continue
            }
            out[PrecomputedTableSummaries.key(table)] = spoken(caption(table), summary, byAi)
        }
        return Result(out, deferred)
    }

    /**
     * Summarizes [document]'s pending tables in place. Returns the updated document, or null when
     * nothing changed (the model still can't run). Tables the model can't summarize at all stay
     * as they are, read row by row.
     */
    suspend fun completePending(document: Document, ai: TableAi, byAi: Boolean): Document? {
        if (document.pendingTables.isEmpty()) return null
        var doc = document
        val remaining = ArrayList<PendingTable>()
        for ((i, table) in document.pendingTables.withIndex()) {
            when (val answer = ask(ai, table.text)) {
                Answer.Later -> {
                    remaining += document.pendingTables.drop(i)
                    break
                }
                is Answer.Text -> {
                    val summary = answer.text?.let(::clean) ?: continue
                    doc = doc.replaceParagraphs(table.paragraphs, spoken(table.caption, summary, byAi)) ?: doc
                }
            }
        }
        if (remaining.size == document.pendingTables.size) return null
        return doc.copy(pendingTables = remaining)
    }

    private sealed interface Answer {
        /** The model can't run now; try again when Murmur is on screen. */
        data object Later : Answer
        /** The model's answer, or null when it failed. */
        data class Text(val text: String?) : Answer
    }

    private suspend fun ask(ai: TableAi, table: String): Answer = try {
        Answer.Text(withTimeoutOrNull(TIMEOUT_MS) { ai.summarize(table) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: TableAiUnavailableNow) {
        Answer.Later
    } catch (e: Exception) {
        Answer.Text(null)
    }

    private fun caption(table: Element): String? =
        table.selectFirst("caption")?.text()?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }

    private fun pending(table: Element) =
        PendingTable(text = TableReader.toPlainText(table), caption = caption(table), paragraphs = TableReader.describe(table))

    /**
     * What is read in place of the table: an announcement, then the summary. With [byAi] the
     * announcement says the summary was written by AI.
     */
    fun spoken(caption: String?, summary: String, byAi: Boolean = false): List<String> {
        val label = if (byAi) "Table summary by AI" else "Table summary"
        return listOf(if (caption != null) "$label: ${caption.trimEnd('.')}." else "$label.", summary)
    }

    /** Plain sentences from model output: no markdown, list markers or preamble. Null when nothing usable is left. */
    fun clean(raw: String): String? {
        val text = raw
            .replace(BULLET, "")
            .replace(MARKDOWN, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replace(PREAMBLE, "")
            .trim()
        if (text.length < 20 || text.none(Char::isLetter)) return null
        val sentences = text.split(SENTENCE_END)
        val kept = sentences.take(MAX_SENTENCES).joinToString(" ")
        return if (kept.last() in ".!?") kept else "$kept."
    }
}
