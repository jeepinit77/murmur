package app.murmur.web

import org.jsoup.nodes.Element
import org.jsoup.nodes.Document as HtmlDocument

/** Decides whether a plain fetch gave real article text or a page that needs a browser to render. */
object QualityCheck {
    const val MIN_WORDS = 150

    private val JS_REQUIRED = Regex(
        """(enable javascript|javascript is (required|disabled)|turn on javascript|please (enable|allow) (javascript|cookies)|""" +
            """checking (if the site connection is secure|your browser)|just a moment\.\.\.|verify(ing)? you are (a )?human|""" +
            """we (use|value your) (cookies|privacy)|accept (all )?cookies|before you continue|consent to (the use of )?cookies)""",
        RegexOption.IGNORE_CASE,
    )
    private val APP_ROOTS = listOf("#root", "#__next", "#app", "#__nuxt", "[data-reactroot]", "#___gatsby")

    fun assess(raw: HtmlDocument, words: Int, readabilityFound: Boolean, content: Element?): ExtractionQuality {
        val bodyText = raw.body()?.text().orEmpty()
        val reason = when {
            !readabilityFound && words < MIN_WORDS -> "no article found"
            words < MIN_WORDS && JS_REQUIRED.containsMatchIn(bodyText.take(4000)) -> "page asks for JavaScript or consent"
            words < MIN_WORDS && isAppShell(raw) -> "page is built by JavaScript"
            words < 60 -> "very little text"
            content != null && linkDensity(content) > 0.5 -> "mostly links"
            else -> null
        }
        return ExtractionQuality(words, needsRender = reason != null, reason = reason)
    }

    private fun isAppShell(raw: HtmlDocument): Boolean {
        val scripts = raw.select("script").sumOf { it.data().length }
        val text = raw.body()?.text()?.length ?: 0
        val emptyRoot = APP_ROOTS.any { sel -> raw.selectFirst(sel)?.let { it.text().length < 200 } == true }
        return emptyRoot || (scripts > 20_000 && text < 1500)
    }

    private fun linkDensity(content: Element): Double {
        val total = content.text().length
        if (total == 0) return 1.0
        val linked = content.select("a").sumOf { it.text().length }
        return linked.toDouble() / total
    }
}

/** Notices articles that are cut off by a paywall or a sign-in prompt. */
object PaywallDetector {
    private val SELECTORS = listOf(
        ".paywall", "[class*=paywall]", "[id*=paywall]", "[class*=regwall]", "#piano",
        ".tp-modal", ".tp-container-inner", "[class*=subscriber-only]", "[class*=premium-content]",
        "[data-testid=paywall]",
    )
    private val ENDING_PROMPT = Regex(
        """(subscribe|sign in|log in|create (a free )?account|already a subscriber|become a (member|subscriber)|""" +
            """to continue reading|keep reading|unlock (this|the full)|start your (free )?trial|members only|read the full)""",
        RegexOption.IGNORE_CASE,
    )

    fun isIncomplete(raw: HtmlDocument, metadata: PageMetadata, words: Int, lastParagraphs: List<String>): Boolean {
        if (metadata.paywalled && words < 1500) return true
        val tail = lastParagraphs.takeLast(2).joinToString(" ")
        if (tail.length < 300 && ENDING_PROMPT.containsMatchIn(tail)) return true
        val gated = raw.select(SELECTORS.joinToString(", ")).any { it.text().length < 2000 }
        return gated && words < 600
    }
}

/** Finds the next page of a multi-page article. */
object Pagination {
    private val NEXT_TEXT = Regex("""^(next( page)?|next\s*[›»>→]|[›»→]|page suivante|siguiente|weiter)$""", RegexOption.IGNORE_CASE)
    private val PAGE_PARAM = Regex("""([?&](?:page|pg|p)=)(\d+)""")
    private val PAGE_PATH = Regex("""(/page/)(\d+)(/?)$""")

    fun nextPage(raw: HtmlDocument, url: String): String? {
        val host = UrlNormalizer.host(url) ?: return null
        val candidates = sequence {
            raw.selectFirst("link[rel=next]")?.absUrl("href")?.let { yield(it) }
            raw.select("a[rel=next]").forEach { yield(it.absUrl("href")) }
            raw.select("a[href]").forEach { a ->
                if (NEXT_TEXT.matches(a.text().trim()) || NEXT_TEXT.matches(a.attr("aria-label").trim())) yield(a.absUrl("href"))
            }
            expectedNext(url)?.let { want ->
                raw.select("a[href]").map { it.absUrl("href") }.firstOrNull { UrlNormalizer.key(it) == UrlNormalizer.key(want) }
                    ?.let { yield(it) }
            }
        }
        val current = UrlNormalizer.key(url)
        return candidates.firstOrNull { c ->
            c.startsWith("http") && UrlNormalizer.host(c)?.removePrefix("www.") == host.removePrefix("www.") &&
                UrlNormalizer.key(c) != current && !c.contains("#")
        }
    }

    /** The address of page N+1 when the current address carries a page number. */
    fun expectedNext(url: String): String? {
        PAGE_PARAM.find(url)?.let { m ->
            return url.replaceRange(m.range, m.groupValues[1] + (m.groupValues[2].toInt() + 1))
        }
        PAGE_PATH.find(url)?.let { m ->
            return url.replaceRange(m.range, m.groupValues[1] + (m.groupValues[2].toInt() + 1) + m.groupValues[3])
        }
        return null
    }
}

/** A last-resort language guess from common words, for pages that don't declare one. */
object LanguageGuess {
    private val STOP_WORDS = mapOf(
        "en" to setOf("the", "and", "of", "to", "is", "in", "that", "it", "was", "for", "with", "this"),
        "es" to setOf("el", "la", "de", "que", "y", "en", "los", "las", "por", "una", "con", "para"),
        "fr" to setOf("le", "la", "les", "de", "et", "des", "est", "une", "que", "dans", "pour", "pas"),
        "de" to setOf("der", "die", "und", "das", "ist", "nicht", "ein", "eine", "mit", "den", "auf", "sich"),
        "it" to setOf("il", "di", "che", "e", "la", "per", "un", "una", "non", "sono", "della", "nel"),
        "pt" to setOf("o", "de", "que", "e", "do", "da", "em", "um", "para", "com", "não", "uma"),
        "nl" to setOf("de", "het", "een", "en", "van", "is", "dat", "op", "niet", "met", "voor", "zijn"),
    )

    fun guess(text: String): String? {
        val words = Regex("""\p{L}+""").findAll(text.take(5000).lowercase()).map { it.value }.toList()
        if (words.size < 30) return null
        val scores = STOP_WORDS.mapValues { (_, set) -> words.count { it in set } }
        val best = scores.maxByOrNull { it.value } ?: return null
        return best.key.takeIf { best.value >= words.size / 20 }
    }
}
