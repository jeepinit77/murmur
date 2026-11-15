package app.murmur.text

import app.murmur.web.PrecomputedTableSummaries
import app.murmur.web.TableMode
import app.murmur.web.TableRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MarkdownTextTest {
    @get:Rule val tmp = TemporaryFolder()

    /** An answer copied from ChatGPT (abridged). */
    private val chatGpt = """
        For entry-level plasma cutters, I'd concentrate on the ${'$'}200–${'$'}400 range.

        ### Top 4 entry-level plasma cutters — November 2026

        | Model                   | Current price | Max amps | Clean cut capacity* | Pilot arc |
        | ----------------------- | ------------- | -------- | ------------------- | --------- |
        | Lotos LTP5505D          | ${'$'}249.98       | 55A      | 5/8"                | Yes       |
        | YesWelder CUT-55DS Plus | ${'$'}309.00       | 55A      | 1/2" max            | Yes       |
        | PrimeWeld CUT50DP       | ${'$'}399.00       | 50A      | 1/2"                | Yes       |
        | VEVOR CUT-65            | ${'$'}259.90       | 65A      | Not verified        | Yes       |

        \*Manufacturer-advertised cutting capacities, generally at 220–240V.

        ![LOTOS LTP5505D 55Amp Plasma Cutter](https://images.openai.com/static-rsc-1/8AW9Ec0FwznLIRuq5LxSk)

        1\. Lotos LTP5505D

        My pick for farm use

        * Pilot arc: Worth having.

        * 240V power: Strongly preferred for cutting 3/8" and 1/2" steel.
    """.trimIndent()

    private fun paragraphs(text: String, mode: TableMode) = PlainText.toDocument(text, tables = mode).chapters.single().paragraphs

    @Test fun chatGptAnswerIsDetectedAsMarkdown() {
        assertTrue(MarkdownText.looksLikeMarkdown(chatGpt))
        assertFalse(MarkdownText.looksLikeMarkdown("Just a note.\n\n- one item\nwith *emphasis* and 3 - 2 = 1."))
    }

    @Test fun markdownTableIsReadRowByRow() {
        val out = paragraphs(chatGpt, TableMode.READ)
        assertTrue(out.toString(), "Table." in out)
        assertTrue(
            out.toString(),
            "Model: Lotos LTP5505D. Current price: \$249.98. Max amps: 55A. Clean cut capacity*: 5/8\". Pilot arc: Yes." in out,
        )
        assertTrue("End of table." in out)
        assertTrue("Top 4 entry-level plasma cutters — November 2026" in out)
        assertTrue("*Manufacturer-advertised cutting capacities, generally at 220–240V." in out)
        assertTrue("1. Lotos LTP5505D" in out)
        assertTrue("Pilot arc: Worth having." in out)
        out.forEach { p ->
            assertFalse(p, "|" in p || "---" in p || "https://" in p || "\\" in p || p.startsWith("#"))
        }
    }

    @Test fun markdownTableCanBeSkipped() {
        val out = paragraphs(chatGpt, TableMode.SKIP)
        assertFalse(out.any { "PrimeWeld CUT50DP" in it })
        assertTrue("My pick for farm use" in out)
    }

    @Test fun markdownTableCanBeSummarized() {
        val recorder = TableRecorder()
        PlainText.toDocument(chatGpt, tables = TableMode.SUMMARIZE, summarizer = recorder)
        val key = PrecomputedTableSummaries.key(recorder.tables.single())
        val summary = "The Lotos is the cheapest at about 250 dollars, and the PrimeWeld costs the most."
        val doc = PlainText.toDocument(
            chatGpt,
            tables = TableMode.SUMMARIZE,
            summarizer = PrecomputedTableSummaries(mapOf(key to listOf("Table summary.", summary))),
        )
        val out = doc.chapters.single().paragraphs
        assertTrue(summary in out)
        assertFalse(out.any { "Max amps" in it })
    }

    @Test fun replaceParagraphsSwapsTheFirstMatchingRun() {
        val doc = Document("T", chapters = listOf(Chapter(null, listOf("a")), Chapter("C", listOf("C", "x", "y", "z", "x", "y"))))
        val out = doc.replaceParagraphs(listOf("x", "y"), listOf("S"))!!
        assertEquals(listOf("C", "S", "z", "x", "y"), out.chapters[1].paragraphs)
        assertEquals(listOf("a"), out.chapters[0].paragraphs)
        assertEquals(null, doc.replaceParagraphs(listOf("q"), listOf("S")))
    }

    @Test fun plainProseIsUnchanged() {
        val text = "This is a wrapped line that goes on for a while and\ncontinues here.\n\nShort one\nShort two\n\n- a\n- b"
        assertEquals(
            listOf("This is a wrapped line that goes on for a while and continues here.", "Short one", "Short two", "- a", "- b"),
            paragraphs(text, TableMode.READ),
        )
    }

    @Test fun tabSeparatedTablesFollowTheSetting() {
        val text = "Prices below.\n\nModel\tPrice\nLotos\t\$249.98\nVEVOR\t\$259.90\n\nThat's all."
        assertEquals(
            listOf("Prices below.", "Table.", "Model: Lotos. Price: \$249.98.", "Model: VEVOR. Price: \$259.90.", "End of table.", "That's all."),
            paragraphs(text, TableMode.READ),
        )
        assertEquals(listOf("Prices below.", "That's all."), paragraphs(text, TableMode.SKIP))
    }

    @Test fun htmlTablesFollowTheSetting() {
        val html = "<p>Intro.</p><table><tr><th>Cat</th><th>Hertz</th></tr><tr><td>House cat</td><td>25</td></tr></table>"
        val read = HtmlText.toDocument(org.jsoup.Jsoup.parse(html).body(), "T", TableMode.READ).chapters.single().paragraphs
        assertEquals(listOf("Intro.", "Table.", "Cat: House cat. Hertz: 25.", "End of table."), read)
        val skipped = HtmlText.toDocument(org.jsoup.Jsoup.parse(html).body(), "T", TableMode.SKIP).chapters.single().paragraphs
        assertEquals(listOf("Intro."), skipped)
    }

    @Test fun epubTablesFollowTheSetting() {
        val file = File(tmp.root, "t.epub")
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="b.opf"/></rootfiles></container>""")
            put(
                "b.opf",
                """<package><metadata><title>Book</title></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
            )
            put("c1.xhtml", "<html><body><h1>One</h1><p>Text.</p><table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table></body></html>")
        }
        assertEquals(listOf("One", "Text.", "Table.", "A: 1. B: 2.", "End of table."), EpubParser.parse(file, TableMode.READ).chapters.single().paragraphs)
        assertEquals(listOf("One", "Text."), EpubParser.parse(file, TableMode.SKIP).chapters.single().paragraphs)
    }
}
