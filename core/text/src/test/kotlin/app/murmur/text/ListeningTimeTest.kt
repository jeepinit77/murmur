package app.murmur.text

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningTimeTest {
    private val wpm = ListeningTime.WORDS_PER_MINUTE

    @Test fun shortLabelForShortItems() {
        assertEquals("< 1 min", ListeningTime.shortLabel(wpm / 2))
        assertEquals("8 min", ListeningTime.shortLabel(wpm * 8))
    }

    @Test fun unreadShowsTotal() {
        assertEquals("8 min", ListeningTime.remainingLabel(wpm * 8, progress = 0, segmentCount = 40))
    }

    @Test fun partwayShowsRemainingAndTotal() {
        assertEquals("2 min (8 min total)", ListeningTime.remainingLabel(wpm * 8, progress = 30, segmentCount = 40))
    }

    @Test fun speedShortensBoth() {
        assertEquals("1 min (4 min total)", ListeningTime.remainingLabel(wpm * 8, progress = 30, segmentCount = 40, speed = 2f))
    }

    @Test fun nearTheEnd() {
        assertEquals("< 1 min (8 min total)", ListeningTime.remainingLabel(wpm * 8, progress = 39, segmentCount = 40))
    }
}
