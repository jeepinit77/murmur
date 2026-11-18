package app.murmur.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun detectsSharedUrls() {
        assertEquals("https://a.com/x", PlainText.sharedUrl("https://a.com/x"))
        assertEquals("https://a.com/x", PlainText.sharedUrl("Great article https://a.com/x"))
        assertNull(PlainText.sharedUrl("A long note.\n" + "With lots of lines and more text. ".repeat(40) + " https://a.com"))
        assertNull(PlainText.sharedUrl("No links here at all."))
    }

    @Test fun plainTextJoinsWrappedLines() {
        val doc = PlainText.toDocument("This is a wrapped line that goes on for a while and\ncontinues here.\n\nSecond para.")
        assertEquals(
            listOf("This is a wrapped line that goes on for a while and continues here.", "Second para."),
            doc.chapters[0].paragraphs,
        )
    }

    @Test fun htmlParagraphsDoNotDuplicateNestedBlocks() {
        val body = org.jsoup.Jsoup.parse(
            "<div><p>One <b>bold</b>.</p><div>Loose text<p>Two.</p></div><script>x()</script><ul><li>Item</li></ul></div>",
        ).body()
        assertEquals(listOf("One bold.", "Loose text", "Two.", "Item"), HtmlParagraphs.extract(body))
    }

    @Test fun webArticleExtractsMainContent() {
        val html = """
            <html><head><title>Big News</title></head><body>
            <nav><a href="/">Home</a><a href="/about">About</a></nav>
            <article><h1>Big News</h1>
            ${(1..6).joinToString("") { "<p>Paragraph $it of the story has plenty of words so that readability treats it as real content, which it is, of course.</p>" }}
            </article>
            <footer>Copyright footer</footer></body></html>
        """.trimIndent()
        val doc = WebArticleExtractor.extract("https://news.example/big", html).document
        assertEquals("Big News", doc.title)
        val paras = doc.chapters[0].paragraphs
        assertEquals("Big News", paras.first())
        assertTrue(paras.any { it.startsWith("Paragraph 1 ") })
        assertFalse(paras.any { "Home" in it || "Copyright" in it })
    }

    @Test fun parsesEpubInSpineOrder() {
        val file = File(tmp.root, "book.epub")
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
                <metadata><dc:title>My Book</dc:title><dc:creator>Ann Author</dc:creator></metadata>
                <manifest>
                  <item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="css" href="style.css" media-type="text/css"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
            put("OEBPS/text/ch1.xhtml", "<html><body><h1>Chapter One</h1><p>It begins.</p></body></html>")
            put("OEBPS/text/ch 2.xhtml", "<html><body><h2>Chapter Two</h2><p>It ends.</p></body></html>")
        }
        val doc = EpubParser.parse(file)
        assertEquals("My Book", doc.title)
        assertEquals("Ann Author", doc.author)
        assertEquals(listOf("Chapter One", "Chapter Two"), doc.chapters.map { it.title })
        assertEquals(listOf("Chapter One", "It begins."), doc.chapters[0].paragraphs)
    }

    @Test fun resolvesRelativePaths() {
        assertEquals("OEBPS/images/a.png", EpubParser.resolve("OEBPS/text", "../images/a.png"))
        assertEquals("a.xhtml", EpubParser.resolve("", "a.xhtml#frag"))
    }

    @Test fun pdfCleanerRemovesHeadersAndJoinsLines() {
        val pages = (1..4).map { n ->
            """
            Journal of Things
            This is the body text of page $n which wraps across several lines in the
            original document ${"and ".repeat(n)}contains a hyphen-
            ated word that should be joined back together on page $n, item ${"x".repeat(n)}.
            Page $n
            """.trimIndent()
        }
        val doc = PdfTextCleaner.clean("Paper", pages)
        val paras = doc.chapters[0].paragraphs
        assertFalse(paras.any { "Journal of Things" in it })
        assertTrue(paras[0].contains("hyphenated word"))
        assertTrue(paras[0].startsWith("This is the body text of page 1 which wraps"))
        assertEquals(4, paras.size)
    }

    @Test fun onePararaphPerLineTextKeepsItsParagraphs() {
        val book = listOf(
            "It was a bright cold day in April, and the clocks were striking thirteen.",
            "Winston slipped quickly through the glass doors, though not quickly enough to prevent a swirl of gritty dust from entering along with him.",
            "The hallway smelt of boiled cabbage.",
            "At one end of it a coloured poster, too large for indoor display, had been tacked to the wall.",
        ).joinToString("\n")
        assertEquals(4, PlainText.toDocument(book).chapters.single().paragraphs.size)
    }

    @Test fun hardWrappedProseIsStillJoined() {
        val wrapped = "This paragraph was wrapped by an editor so that\n" +
            "every line is about the same length, and the\n" +
            "sentence keeps going across the line breaks.\n" +
            "It should come out as a single paragraph here."
        assertEquals(1, PlainText.toDocument(wrapped).chapters.single().paragraphs.size)
    }
}
