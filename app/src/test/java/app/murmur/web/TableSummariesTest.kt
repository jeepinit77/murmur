package app.murmur.web

import app.murmur.text.PlainText
import app.murmur.text.WebArticleExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableSummariesTest {

    private val prose = "This sentence is ordinary article prose with enough words to look like real content to the extractor."
    private fun para(n: Int, prefix: String = "Paragraph") = (1..n).joinToString("") { "<p>$prefix $it. $prose $prose</p>" }

    private fun table(caption: String, rows: Int) = "<table><caption>$caption</caption><tr><th>City</th><th>Rainfall</th></tr>" +
        (1..rows).joinToString("") { "<tr><td>$caption city $it</td><td>${it * 3} in</td></tr>" } + "</table>"

    private val html = "<html><head><title>Rain</title></head><body><article>" +
        para(2) + table("Big", 12) + para(1, "Middle") + table("Small", 2) + para(2, "After") + "</article></body></html>"

    private val summarize = ExtractOptions(tables = TableMode.SUMMARIZE)

    private class FakeAi(private val answer: (String) -> String?) : TableAi {
        val asked = ArrayList<String>()
        override suspend fun status() = TableAiStatus.READY
        override suspend fun summarize(table: String): String? {
            asked += table
            return answer(table)
        }
    }

    private fun run(options: ExtractOptions, ai: TableAi?): List<String> = runBlocking {
        TableSummaries.extract(options, ai) { tables ->
            WebArticleExtractor.extract("https://a.com/x", html, options, tables = tables)
        }.document.chapters.flatMap { it.paragraphs }
    }

    private val rowByRow by lazy { run(ExtractOptions(tables = TableMode.READ), null) }

    @Test fun noModelFallsBackToRuleBasedReading() {
        assertEquals(rowByRow, run(summarize, null))
    }

    @Test fun modelThatIsNotReadyFallsBack() {
        val ai = FakeAi { null }
        assertEquals(rowByRow, run(summarize, ai))
        assertEquals(1, ai.asked.size)
    }

    @Test fun modelErrorsFallBack() {
        assertEquals(rowByRow, run(summarize, FakeAi { error("AICore is busy") }))
    }

    @Test fun unusableOutputFallsBack() {
        assertEquals(rowByRow, run(summarize, FakeAi { "**" }))
    }

    @Test fun largeTablesAreSummarizedAndSmallOnesReadByRow() {
        val ai = FakeAi { "Rainfall rises steadily from city 1 to city 12. City 12 is the wettest at 36 inches." }
        val out = run(summarize, ai)
        assertEquals(1, ai.asked.size)
        assertTrue(ai.asked.single().startsWith("Caption: Big\nCity | Rainfall\n"))
        assertTrue("Table summary: Big." in out)
        assertTrue("Rainfall rises steadily from city 1 to city 12. City 12 is the wettest at 36 inches." in out)
        assertFalse(out.any { it.startsWith("Table with 12 rows") })
        // The small table is still read row by row.
        assertTrue("City: Small city 1. Rainfall: 3 in." in out)
    }

    @Test fun summariesCanBeLabeledAsAi() {
        val out = run(summarize.copy(labelAiSummaries = true), FakeAi { "City 12 is the wettest and city 1 the driest." })
        assertTrue("Table summary by AI: Big." in out)
        assertFalse("Table summary: Big." in out)
    }

    private fun runDeferred(ai: TableAi): app.murmur.text.Document = runBlocking {
        TableSummaries.extract(
            summarize, ai,
            defer = { article, later -> article.copy(document = article.document.copy(pendingTables = later)) },
        ) { tables -> WebArticleExtractor.extract("https://a.com/x", html, summarize, tables = tables) }.document
    }

    @Test fun blockedModelPostponesTablesAndReadsThemRowByRowForNow() {
        val doc = runDeferred(FakeAi { throw TableAiUnavailableNow("Background usage is blocked") })
        assertEquals(rowByRow, doc.chapters.flatMap { it.paragraphs })
        val pending = doc.pendingTables.single()
        assertEquals("Big", pending.caption)
        assertTrue(pending.text.startsWith("Caption: Big\nCity | Rainfall\n"))
        assertEquals("Table with 12 rows: Big.", pending.paragraphs.first())
    }

    @Test fun postponedTablesAreSummarizedLater() {
        val doc = runDeferred(FakeAi { throw TableAiUnavailableNow("Background usage is blocked") })
        val later = runBlocking {
            TableSummaries.completePending(doc, FakeAi { "City 12 is the wettest and city 1 the driest." }, byAi = true)
        }!!
        val out = later.chapters.flatMap { it.paragraphs }
        assertTrue(later.pendingTables.isEmpty())
        assertTrue("Table summary by AI: Big." in out)
        assertTrue("City 12 is the wettest and city 1 the driest." in out)
        assertFalse(out.any { it.startsWith("Table with 12 rows") || it.startsWith("City: Big city") })
        // Text around the table and the small table are unchanged.
        assertTrue("City: Small city 1. Rainfall: 3 in." in out)
        assertEquals(rowByRow.first(), out.first())
    }

    @Test fun stillBlockedChangesNothing() {
        val blocked = FakeAi { throw TableAiUnavailableNow("Background usage is blocked") }
        val doc = runDeferred(blocked)
        assertNull(runBlocking { TableSummaries.completePending(doc, blocked, byAi = false) })
    }

    @Test fun failedLaterSummaryKeepsRowByRowAndStopsWaiting() {
        val doc = runDeferred(FakeAi { throw TableAiUnavailableNow("Background usage is blocked") })
        val later = runBlocking { TableSummaries.completePending(doc, FakeAi { null }, byAi = false) }!!
        assertTrue(later.pendingTables.isEmpty())
        assertEquals(rowByRow, later.chapters.flatMap { it.paragraphs })
    }

    @Test fun statusSaysWhetherSummariesCanRun() {
        assertTrue(TableAiStatus.READY.usable)
        assertTrue(TableAiStatus.NEEDS_DOWNLOAD.usable)
        assertTrue(TableAiStatus.DOWNLOADING.usable)
        assertFalse(TableAiStatus.UNSUPPORTED.usable)
        assertFalse(TableAiStatus.ERROR.usable)
    }

    @Test fun otherModesNeverCallTheModel() {
        val ai = FakeAi { "Should not be used at all here." }
        run(ExtractOptions(tables = TableMode.READ), ai)
        run(ExtractOptions(tables = TableMode.SKIP), ai)
        assertTrue(ai.asked.isEmpty())
    }

    @Test fun pastedMarkdownTablesFallBackOrAreSummarized() {
        val md = "Rainfall by city.\n\n| City | Rainfall |\n| --- | --- |\n" + (1..6).joinToString("\n") { "| City $it | ${it * 3} in |" }
        fun paste(ai: TableAi?) = runBlocking {
            TableSummaries.extract(summarize, ai) { tables ->
                PlainText.toDocument(md, "Rain", TableMode.SUMMARIZE, tables)
            }.chapters.single().paragraphs
        }
        val rowByRow = PlainText.toDocument(md, "Rain", TableMode.READ).chapters.single().paragraphs
        assertTrue("City: City 1. Rainfall: 3 in." in rowByRow)
        assertEquals(rowByRow, paste(null))
        assertEquals(rowByRow, paste(FakeAi { null }))
        val summarized = paste(FakeAi { "City 6 gets the most rain and City 1 the least." })
        assertEquals(listOf("Rainfall by city.", "Table summary.", "City 6 gets the most rain and City 1 the least."), summarized)
    }

    @Test fun cleanRemovesMarkdownAndPreamble() {
        assertEquals(
            "City 12 is the wettest. City 1 is the driest.",
            TableSummaries.clean("Here's a summary of the table:\n\n- **City 12** is the wettest.\n- City 1 is the driest."),
        )
        assertEquals("Rain varies a lot between cities.", TableSummaries.clean("Rain varies a lot between cities"))
        assertNull(TableSummaries.clean("  ## "))
    }
}
