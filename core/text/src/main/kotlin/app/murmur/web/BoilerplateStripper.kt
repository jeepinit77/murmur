package app.murmur.web

import org.jsoup.nodes.Element

/**
 * Removes things that are on the page but aren't the article: subscribe boxes, share bars,
 * "related articles" lists, ads, and the stray one-line prompts newsletters scatter through posts.
 */
object BoilerplateStripper {

    /** Removed only when small, so a container that happens to match never takes the article with it. */
    private val NOISE = listOf(
        // Substack
        ".subscription-widget-wrap", ".subscription-widget", ".subscribe-widget", ".share-dialog", ".post-footer",
        ".button-wrapper", ".footer-wrap", ".post-ufi", ".captioned-button-wrap", ".digest-post-embed",
        // Ghost, beehiiv, Medium, WordPress
        ".kg-signup-card", ".gh-subscribe", ".gh-cta", ".beehiiv__footer", ".pw-multi-vote-count", ".sharedaddy",
        ".jp-relatedposts", ".wp-block-jetpack-subscriptions",
        // Common patterns
        "[class*=newsletter]", "[class*=subscribe]", "[class*=signup]", "[class*=sign-up]", "[class*=share-bar]",
        "[class*=sharing]", "[class*=social-share]", "[class*=related]", "[class*=recommend]", "[class*=promo]",
        "[class*=advert]", "[id*=advert]", "[class*=ad-slot]", "[class*=ad-container]", "[class*=ad-wrapper]",
        "[data-ad]", "[aria-label*=advertisement i]", "[class*=read-more]", "[class*=more-stories]",
        "[class*=author-bio]", "[class*=comments]", "#comments", "aside",
    )
    private const val SMALL_TEXT = 600

    private val END_SECTIONS = Regex(
        """^(related( articles| stories| posts| reading)?|read (more|next)|more (from|on|stories).{0,40}|recommended( for you| reading)?|""" +
            """you (may|might) also like|share this( (post|article|story))?|comments?|most (popular|read)|popular (posts|stories)|""" +
            """trending( now)?|latest (news|stories)|sponsored( content)?|up next)$""",
        RegexOption.IGNORE_CASE,
    )

    private val CLUTTER = listOf(
        """advertisement""",
        """(scroll|continue reading|story continues|article continues)( (below|to continue|the main story|after (the )?advertisement))?( advertisement)?""",
        """(subscribe( now| today| for free)?|sign up( now| for free)?|share( this( post| article| story)?)?|leave a comment|tweet|email|print|copy link|follow us.{0,40})""",
        """thanks for reading.{0,100}""",
        """(photo|image|illustration|graphic|video|photograph|credit|photo credit|image credit|source: getty images)\s*:.{0,120}""",
        """\(?(ap|reuters|afp|getty images|shutterstock|ap photo)( photo)?(/.{0,80})?\)?""",
        """.{0,80}(/|\| )(getty images|shutterstock|reuters|ap|afp)\)?""",
        """(updated|published|posted|last updated)?:?\s*\d+ (seconds?|minutes?|mins?|hours?|hrs?|days?|weeks?) ago""",
        """(updated|last updated|published)\s*:?\s*(on\s+)?([a-z]{3,9}\.? \d{1,2},? \d{4}|\d{1,2} [a-z]{3,9},? \d{4}|\d{4}-\d{2}-\d{2}).{0,24}""",
        """[\d.,]+k? (shares?|comments?|likes?|views|reactions)""",
        """listen to (this|the) (article|story|episode).{0,40}""",
        """\d+\s*(min|mins|minute|minutes) (read|listen)""",
        """(sign up for|get|join) (our|the|my) .{0,60}newsletter.{0,80}""",
        """(support (us|our work|this (newsletter|publication|blog|site))|become a (paid )?(subscriber|member)|upgrade to paid).{0,80}""",
        """(open|view) (in|on) (the )?app""",
        """(click|tap) here to .{0,60}""",
    ).map { Regex("^(?:$it)[.!]*$", RegexOption.IGNORE_CASE) }

    /** "(/ˈʃeɪk.spɪər/ ; born …)" style pronunciation notes in Wikipedia lead sentences. */
    private val PRONUNCIATION = Regex("""\s*\((?:[^()]*?(?:/[^/()]{2,60}/|\[[^\]]*ˈ[^\]]*]|ⓘ|listen|pronounced)[^()]*?)\)""")
    private val EDIT_MARK = Regex("""\s*\[edit]""", RegexOption.IGNORE_CASE)

    /** Strips noise elements before extraction. */
    fun stripDom(root: Element) {
        root.select(NOISE.joinToString(", ")).forEach { el ->
            if (el.parent() != null && (el.text().length < SMALL_TEXT || el.selectFirst("form, input, button") != null)) el.remove()
        }
        // "Related articles" and similar sections at the end of the body: drop the heading and what follows it.
        root.select("h2, h3, h4").forEach { h ->
            if (h.parent() == null || !END_SECTIONS.matches(h.text().trim().trimEnd(':'))) return@forEach
            var sib = h.nextElementSibling()
            while (sib != null && sib.normalName() !in setOf("h1", "h2", "h3")) {
                val next = sib.nextElementSibling()
                sib.remove()
                sib = next
            }
            h.remove()
        }
    }

    /** True for a paragraph that is page furniture rather than article text. */
    fun isClutter(paragraph: String): Boolean {
        val p = paragraph.trim()
        if (p.length > 200) return false
        return CLUTTER.any { it.matches(p) }
    }

    /** Removes pronunciation notes and "[edit]" markers from a paragraph. */
    fun cleanParagraph(paragraph: String): String =
        paragraph.replace(EDIT_MARK, "").replace(PRONUNCIATION, "").replace(Regex("""\s+([,.;:])"""), "$1").trim()
}
