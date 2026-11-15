package app.murmur.text

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class TextSearchTest {
    private val doc = Document(
        title = "Test",
        chapters = listOf(
            Chapter("One", listOf("The Café opened at noon.", "Nothing here.")),
            Chapter("Two", listOf("We don’t go to the cafe — ever. CAFE!")),
        ),
    )

    @Test
    fun ignoresCaseAccentsAndQuotes() {
        val matches = TextSearch.find(doc, "cafe")
        assertEquals(
            listOf(TextMatch(0, 0, 4, 8), TextMatch(1, 0, 19, 23), TextMatch(1, 0, 32, 36)),
            matches,
        )
        assertEquals(1, TextSearch.find(doc, "don't").size)
        assertEquals(1, TextSearch.find(doc, "cafe - ever").size)
    }

    @Test
    fun foldKeepsLength() {
        val s = "Ångström’s “naïve” façade — ﬁ"
        assertEquals(s.length, TextSearch.fold(s).length)
    }

    @Test
    fun blankQueryFindsNothing() {
        assertTrue(TextSearch.find(doc, "   ").isEmpty())
    }

    @Test
    fun limitStopsEarly() {
        assertEquals(2, TextSearch.find(doc, "cafe", limit = 2).size)
    }

    @Test
    fun snippetMarksTheMatch() {
        val text = "word ".repeat(40) + "needle " + "word ".repeat(40)
        val start = text.indexOf("needle")
        val s = TextSearch.snippet(text, start, start + 6, radius = 20)
        assertEquals("needle", s.text.substring(s.start, s.end))
        assertTrue(s.text.startsWith("…") && s.text.endsWith("…"))
        assertTrue(s.text.length < 60)
    }
}
