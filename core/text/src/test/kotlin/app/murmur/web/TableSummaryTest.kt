package app.murmur.web

import app.murmur.text.WebArticleExtractor
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableSummaryTest {

    private val prose = "This sentence is ordinary article prose with enough words to look like real content to the extractor."
    private fun para(n: Int, prefix: String = "Paragraph") = (1..n).joinToString("") { "<p>$prefix $it. $prose $prose</p>" }
    private fun page(body: String) = "<html><head><title>Cats</title></head><body><article>$body</article></body></html>"

    private val table = "<table><caption>Purr frequencies</caption><thead><tr><th>Cat</th><th>Hertz</th></tr></thead>" +
        "<tbody><tr><td>House cat</td><td>25</td></tr><tr><td>Cheetah</td><td>20</td></tr></tbody></table>"
    private val html = page(para(2) + table + para(2, "After"))
    private val summarize = ExtractOptions(tables = TableMode.SUMMARIZE)

    private fun read(options: ExtractOptions, tables: TableSummarizer = TableReader) =
        WebArticleExtractor.extract("https://a.com/x", html, options, tables = tables).document.chapters.flatMap { it.paragraphs }

    @Test fun summarizeWithoutAModelReadsRowByRow() {
        assertEquals(read(ExtractOptions(tables = TableMode.READ)), read(summarize))
    }

    @Test fun precomputedSummaryReplacesTheTable() {
        val recorder = TableRecorder()
        read(summarize, recorder)
        assertEquals(1, recorder.tables.size)
        val key = PrecomputedTableSummaries.key(recorder.tables.single())
        val out = read(summarize, PrecomputedTableSummaries(mapOf(key to listOf("Table summary.", "House cats purr faster than cheetahs."))))
        assertTrue("House cats purr faster than cheetahs." in out)
        assertFalse(out.any { "End of table" in it })
    }

    @Test fun tablesWithoutASummaryFallBackToTableReader() {
        val out = read(summarize, PrecomputedTableSummaries(mapOf("some other table" to listOf("Wrong."))))
        assertTrue("Cat: House cat. Hertz: 25." in out)
        assertFalse("Wrong." in out)
    }

    @Test fun readModeIgnoresTheSummarizer() {
        val out = read(ExtractOptions(tables = TableMode.READ), PrecomputedTableSummaries(emptyMap(), fallback = { listOf("AI") }))
        assertFalse("AI" in out)
        assertTrue("Cat: House cat. Hertz: 25." in out)
    }

    @Test fun plainTextKeepsRowsAndCapsLength() {
        val rows = (1..200).joinToString("") { "<tr><td>Row $it</td><td>$it</td></tr>" }
        val t = Jsoup.parse("<table><caption>Big</caption><tr><th>Name</th><th>Value</th></tr>$rows</table>").selectFirst("table")!!
        val text = TableReader.toPlainText(t, maxChars = 500)
        assertTrue(text.startsWith("Caption: Big\nName | Value\nRow 1 | 1\n"))
        assertTrue(text.endsWith("more rows not shown)"))
        assertTrue(text.length < 560)
        assertEquals(201, TableReader.rowCount(t))
    }
}
