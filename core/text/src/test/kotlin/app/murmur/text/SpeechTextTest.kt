package app.murmur.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun normalizesUrlsAndSymbols() {
        assertEquals(
            "See link to example dot com and 50 percent off",
            SpeechText.normalize("See https://www.example.com/a?b=1 & 50% off"),
        )
        assertEquals("Fact.", SpeechText.normalize("Fact.[12]"))
        assertEquals("Bold text", SpeechText.normalize("**Bold** text"))
    }

    @Test fun fastStartSplitsAtFirstClause() {
        val text = "When the morning came, the travellers packed their bags and set off along the old road toward the hills."
        val parts = SpeechText.splitForFastStart(text)
        assertEquals("When the morning came,", parts[0])
        assertEquals(2, parts.size)
    }

    @Test fun fastStartLeavesShortTextAlone() {
        assertEquals(listOf("Short sentence."), SpeechText.splitForFastStart("Short sentence."))
    }

    @Test fun fastStartFallsBackToSpace() {
        val text = "a".repeat(10) + " " + "word ".repeat(40)
        val parts = SpeechText.splitForFastStart(text.trim())
        assertTrue(parts[0].length <= 80)
        assertEquals(2, parts.size)
    }
}
