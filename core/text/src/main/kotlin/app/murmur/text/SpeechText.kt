package app.murmur.text

/** Helpers that prepare segment text for the speech model without changing what is displayed. */
object SpeechText {

    private val URL = Regex("""\bhttps?://[^\s)\]]+|\bwww\.[^\s)\]]+""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""\b[\w.+-]+@[\w-]+\.[\w.]+\b""")
    private val MARKDOWN = Regex("""(\*\*|__|\*|`|#{1,6}\s)""")
    private val BRACKET_REF = Regex("""\[\d+(,\s*\d+)*]""") // citation markers like [12]
    private val WHITESPACE = Regex("""\s+""")
    private val REPEATED_PUNCT = Regex("""([!?.]){2,}""")

    /** Normalizes text for a TTS model: drops URLs/markup noise, expands a few symbols. */
    fun normalize(text: String): String {
        var t = text
        t = URL.replace(t) { m -> linkName(m.value) }
        t = EMAIL.replace(t) { m -> m.value.replace("@", " at ").replace(".", " dot ") }
        t = BRACKET_REF.replace(t, "")
        t = MARKDOWN.replace(t, "")
        t = t.replace("&", " and ")
            .replace("%", " percent")
            .replace("…", "...")
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
        t = REPEATED_PUNCT.replace(t) { it.value.first().toString() }
        return WHITESPACE.replace(t, " ").trim()
    }

    private fun linkName(url: String): String {
        val host = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore('/').substringBefore('?')
        return if (host.isBlank()) "link" else "link to " + host.replace(".", " dot ")
    }

    /**
     * Splits [text] so the first piece is short and starts speaking quickly. Returns the
     * input as a single element when it is already short or has no good break point.
     */
    fun splitForFastStart(text: String, maxHead: Int = 80, minHead: Int = 15): List<String> {
        if (text.length <= maxHead + minHead) return listOf(text)
        val window = text.substring(0, maxHead)
        var cut = -1
        for (i in minHead until window.length) {
            val c = window[i]
            if ((c == ',' || c == ';' || c == ':' || c == '—') &&
                (i + 1 < text.length && text[i + 1].isWhitespace())
            ) {
                cut = i + 1
                break
            }
        }
        if (cut == -1) {
            val space = window.lastIndexOf(' ')
            if (space < minHead) return listOf(text)
            cut = space + 1
        }
        val head = text.substring(0, cut).trim()
        val tail = text.substring(cut).trim()
        return if (tail.isEmpty()) listOf(head) else listOf(head, tail)
    }
}
