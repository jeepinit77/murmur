package app.murmur.web

/**
 * A link found in shared text. Apps share pages as a bare URL, "Title https://…", or
 * "Check this out: Title https://… via @app"; any of those should import the page.
 */
data class SharedLink(val url: String, val titleHint: String?) {

    companion object {
        /** Surrounding text longer than this is treated as something to read, not a link share. */
        const val MAX_SURROUNDING = 1000
        private const val MAX_LINES = 6

        private val HTTP_URL = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
        private val BARE_DOMAIN = Regex(
            """^(?:www\.)?[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\.[a-z]{2,24}(?:/\S*)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val LEAD_IN = Regex(
            """^(check (this|it) out|look at this|read this|thought you('d| would) (like|enjoy) this|interesting( read)?|""" +
                """worth (a|the) read|have you seen this\??|fyi|shared)\s*[:!.\-–—]*\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val TRAILERS = listOf(
            Regex("""\s*(via|from)\s+@\w+\s*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*(shared|sent) (from|via|with) .{1,40}$""", RegexOption.IGNORE_CASE),
            Regex("""\s*sent from my .{1,40}$""", RegexOption.IGNORE_CASE),
            Regex("""\s*[-–—|]\s*$"""),
        )

        /** Returns the link when [text] contains exactly one, plus a title hint from the rest. */
        fun parse(text: String, subject: String? = null): SharedLink? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            // A bare "example.com/article" with no scheme, and nothing else.
            if (!trimmed.contains(Regex("\\s")) && !trimmed.contains("://") && BARE_DOMAIN.matches(trimmed) &&
                (trimmed.contains('/') || trimmed.startsWith("www.", ignoreCase = true))
            ) {
                return SharedLink("https://$trimmed", subject.cleanHint())
            }
            val matches = HTTP_URL.findAll(trimmed).map { cleanEnd(it.value) to it.range }.toList()
            val distinct = matches.map { UrlNormalizer.key(it.first) }.distinct()
            if (distinct.size != 1) return null
            val url = matches.first().first
            var rest = trimmed
            matches.sortedByDescending { it.second.first }.forEach { (_, range) -> rest = rest.removeRange(range) }
            rest = rest.trim()
            if (rest.length > MAX_SURROUNDING) return null
            if (rest.lines().count { it.isNotBlank() } > MAX_LINES) return null
            val hint = subject.cleanHint() ?: cleanSurrounding(rest)
            return SharedLink(url, hint)
        }

        private fun String?.cleanHint(): String? =
            this?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) }?.let(::cleanSurrounding)

        private fun cleanSurrounding(rest: String): String? {
            var s = rest.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            s = s.replace(LEAD_IN, "")
            repeat(2) { TRAILERS.forEach { r -> s = s.replace(r, "") } }
            s = s.trim().trim('"', '“', '”', '\'', ':', '-', '–', '—', '|').trim()
            return s.takeIf { it.length >= 3 && it.any(Char::isLetter) }?.take(200)
        }

        /** Drops sentence punctuation stuck to the end of a link, keeping balanced parentheses (Wikipedia). */
        private fun cleanEnd(url: String): String {
            var u = url
            while (u.isNotEmpty()) {
                val last = u.last()
                u = when {
                    last in ".,;:!?'\"”’>]}" -> u.dropLast(1)
                    last == ')' && u.count { it == '(' } < u.count { it == ')' } -> u.dropLast(1)
                    else -> return u
                }
            }
            return u
        }
    }
}
