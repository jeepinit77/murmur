package app.murmur.web

import app.murmur.text.Chapter
import app.murmur.text.Document
import app.murmur.text.ListeningTime
import app.murmur.text.SentenceSegmenter
import app.murmur.text.WebArticleExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebImportTest {

    private val prose = "This sentence is ordinary article prose with enough words to look like real content to the extractor."

    private fun para(n: Int, prefix: String = "Paragraph") =
        (1..n).joinToString("") { "<p>$prefix $it. $prose $prose</p>" }

    private fun page(body: String, head: String = "<title>Why Cats Purr | Science Desk</title>") =
        "<html lang=\"en-US\"><head>$head</head><body><nav><a href='/'>Home</a></nav><article>$body</article></body></html>"

    private fun all(doc: Document) = doc.chapters.flatMap { it.paragraphs }

    // ---- URLs and shares --------------------------------------------------------------

    @Test fun normalizerStripsTrackingAndUnwrapsRedirects() {
        assertEquals(
            "https://example.com/a?id=3",
            UrlNormalizer.normalize("https://Example.com/a?utm_source=x&id=3&fbclid=abc#section"),
        )
        assertEquals(
            "https://news.site/story",
            UrlNormalizer.normalize("https://www.google.com/url?q=https%3A%2F%2Fnews.site%2Fstory%3Futm_medium%3Dx&sa=D"),
        )
        assertEquals("https://news.site/2026/story", UrlNormalizer.normalize("https://www.google.com/amp/s/news.site/2026/story"))
        assertEquals("https://a.org/x", UrlNormalizer.normalize("https://l.facebook.com/l.php?u=https%3A%2F%2Fa.org%2Fx&h=AT0"))
    }

    @Test fun normalizerKeyMatchesVariantsOfTheSameArticle() {
        val key = UrlNormalizer.key("https://www.example.com/story/")
        assertEquals(key, UrlNormalizer.key("http://example.com/story?utm_campaign=y"))
        assertEquals(key, UrlNormalizer.key("https://m.example.com/story#top"))
        assertEquals(key, UrlNormalizer.key("https://example.com/story/amp"))
        assertEquals(
            UrlNormalizer.key("https://en.wikipedia.org/wiki/Cat"),
            UrlNormalizer.key("https://en.m.wikipedia.org/wiki/Cat?utm_source=x"),
        )
        assertFalse(key == UrlNormalizer.key("https://example.com/other"))
    }

    @Test fun sharedLinkFindsOneLinkAndATitleHint() {
        assertEquals(SharedLink("https://a.com/x", null), SharedLink.parse("https://a.com/x"))
        assertEquals("Why cats purr", SharedLink.parse("Why cats purr https://a.com/x")?.titleHint)
        assertEquals("Why cats purr", SharedLink.parse("Check this out: Why cats purr https://a.com/x via @newsapp")?.titleHint)
        assertEquals("Subject wins", SharedLink.parse("Other text https://a.com/x", subject = "Subject wins")?.titleHint)
        assertEquals("https://a.com/x", SharedLink.parse("Read this (https://a.com/x).")?.url)
        assertEquals(
            "https://en.wikipedia.org/wiki/Cat_(disambiguation)",
            SharedLink.parse("https://en.wikipedia.org/wiki/Cat_(disambiguation)")?.url,
        )
        assertEquals("https://example.com/a/b", SharedLink.parse("example.com/a/b")?.url)
        // The same link twice is still one link.
        assertNotNull(SharedLink.parse("https://a.com/x\nhttps://a.com/x?utm_source=share"))
    }

    @Test fun sharedLinkLeavesTextSharesAlone() {
        assertNull(SharedLink.parse("Two links https://a.com/x and https://b.com/y"))
        assertNull(SharedLink.parse("No links here at all."))
        assertNull(SharedLink.parse("Node.js is great"))
        assertNull(SharedLink.parse("x".repeat(SharedLink.MAX_SURROUNDING + 10) + " https://a.com/x"))
    }

    // ---- Article extraction -----------------------------------------------------------

    @Test fun headingsBecomeChapters() {
        val html = page("<h1>Why Cats Purr</h1>${para(3)}<h2>The muscles</h2>${para(3, "Muscle")}<h2>The brain</h2>${para(3, "Brain")}")
        val doc = WebArticleExtractor.extract("https://science.example/why-cats-purr", html).document
        assertEquals("Why Cats Purr", doc.title)
        assertEquals(listOf(null, "The muscles", "The brain"), doc.chapters.map { it.title })
        assertEquals("Why Cats Purr", doc.chapters[0].paragraphs.first())
        assertEquals("The muscles", doc.chapters[1].paragraphs.first())
        assertTrue(doc.chapters[2].paragraphs[1].startsWith("Brain 1."))
        assertEquals("en", doc.language)
    }

    @Test fun headingsStayInlineWhenChaptersAreOff() {
        val html = page("${para(3)}<h2>The muscles</h2>${para(3, "Muscle")}")
        val doc = WebArticleExtractor.extract("https://science.example/a", html, ExtractOptions(headingsAsChapters = false)).document
        assertEquals(1, doc.chapters.size)
        assertTrue("The muscles" in doc.chapters[0].paragraphs)
    }

    @Test fun titleDropsSiteNameAndFallsBackToSlug() {
        assertEquals("Why Cats Purr", WebArticleExtractor.stripSiteName("Why Cats Purr | Science Desk", "Science Desk", null))
        assertEquals("Why Cats Purr", WebArticleExtractor.stripSiteName("Why Cats Purr - NYTimes", null, "nytimes.com"))
        assertEquals("Ask HN: Who is hiring?", WebArticleExtractor.stripSiteName("Ask HN: Who is hiring?", null, "news.ycombinator.com"))
        assertEquals("Why cats purr", WebArticleExtractor.slugTitle("https://a.com/2026/11/why-cats-purr-123456.html"))
        val doc = WebArticleExtractor.extract("https://a.com/2026/why-cats-purr", "<html><body>${para(4)}</body></html>").document
        assertEquals("Why cats purr", doc.title)
    }

    @Test fun metadataFeedsTheSpokenIntro() {
        val head = """
            <title>Why Cats Purr</title>
            <meta property="og:site_name" content="Science Desk">
            <meta property="og:description" content="New research shows how a cat's larynx makes the sound.">
            <link rel="canonical" href="https://science.example/cats">
            <script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"NewsArticle",
              "headline":"Why Cats Purr","datePublished":"2026-03-03T10:00:00Z","author":[{"@type":"Person","name":"Jane Doe"}]}]}</script>
        """.trimIndent()
        val result = WebArticleExtractor.extract("https://science.example/cats?x=1", page(para(4), head), ExtractOptions(spokenIntro = true))
        val doc = result.document
        assertEquals("Jane Doe", doc.author)
        assertEquals("Science Desk", doc.siteName)
        assertEquals("https://science.example/cats", result.canonicalUrl)
        val first = doc.chapters[0].paragraphs
        assertEquals("Why Cats Purr", first[0])
        assertEquals("New research shows how a cat's larynx makes the sound.", first[1])
        assertEquals("By Jane Doe. Science Desk. March 3, 2026.", first[2])
        assertFalse(doc.incomplete)
    }

    @Test fun introIsOffByDefault() {
        val head = "<title>T</title><meta name=\"author\" content=\"Jane Doe\">"
        val doc = WebArticleExtractor.extract("https://a.com/why-cats-purr", page(para(4), head)).document
        assertFalse(all(doc).any { it.startsWith("By Jane") })
    }

    @Test fun tablesAreSkippedOrReadByRow() {
        val table = "<table><caption>Purr frequencies</caption><thead><tr><th>Cat</th><th>Hertz</th></tr></thead>" +
            "<tbody><tr><td>House cat</td><td>25</td></tr><tr><td>Cheetah</td><td>20</td></tr></tbody></table>"
        val html = page(para(2) + table + para(2, "After"))
        val skipped = all(WebArticleExtractor.extract("https://a.com/x", html).document)
        assertFalse(skipped.any { "Cheetah" in it })
        val read = all(WebArticleExtractor.extract("https://a.com/x", html, ExtractOptions(tables = TableMode.READ)).document)
        assertTrue("Table: Purr frequencies." in read)
        assertTrue("Cat: House cat. Hertz: 25." in read)
        assertTrue("End of table." in read)
    }

    @Test fun largeTablesAreDescribed() {
        val rows = (1..20).joinToString("") { "<tr><td>Row $it</td><td>$it</td><td>x</td></tr>" }
        val table = org.jsoup.Jsoup.parse("<table><tr><th>Name</th><th>Value</th><th>Note</th></tr>$rows</table>").selectFirst("table")!!
        assertEquals(listOf("Table with 20 rows and 3 columns.", "Columns: Name, Value, Note."), TableReader.describe(table))
    }

    @Test fun codeBlocksFollowTheCodeSetting() {
        val html = page(para(2) + "<pre><code>val x = 1\nprintln(x)</code></pre><pre>more()</pre>" + para(2, "After"))
        val skip = all(WebArticleExtractor.extract("https://a.com/x", html).document)
        assertFalse(skip.any { "println" in it || it == ExtractOptions.CODE_NOTICE })
        val notice = all(WebArticleExtractor.extract("https://a.com/x", html, ExtractOptions(code = CodeMode.NOTICE)).document)
        assertEquals(notice.toString(), 1, notice.count { it == ExtractOptions.CODE_NOTICE })
        val read = all(WebArticleExtractor.extract("https://a.com/x", html, ExtractOptions(code = CodeMode.READ)).document)
        assertTrue(read.toString(), "println(x)" in read)
    }

    @Test fun captionsFollowTheCaptionSetting() {
        val figure = "<figure><img src='a.jpg' alt='A cat asleep on a sunny windowsill'><figcaption>Mochi naps after lunch.</figcaption></figure>"
        val html = page(para(2) + figure + para(2, "After"))
        assertFalse(all(WebArticleExtractor.extract("https://a.com/x", html).document).any { "Mochi" in it })
        val read = all(WebArticleExtractor.extract("https://a.com/x", html, ExtractOptions(readCaptions = true)).document)
        assertTrue(read.toString(), "Image: Mochi naps after lunch." in read)
    }

    @Test fun clutterParagraphsAreRemoved() {
        val junk = "<p>Advertisement</p><p>Story continues below advertisement</p><p>Photo: Jane Smith / Getty Images</p>" +
            "<p>Updated 3 hours ago</p><p>Subscribe</p>"
        val doc = WebArticleExtractor.extract("https://a.com/x", page(para(2) + junk + para(2, "After"))).document
        val text = all(doc)
        assertFalse(text.any { it == "Advertisement" || it.startsWith("Story continues") || it.startsWith("Photo:") || it == "Subscribe" })
        assertTrue(text.any { it.startsWith("After 2.") })
    }

    @Test fun clutterRulesKeepRealSentences() {
        listOf(
            "Support for the bill grew among voters.",
            "Updated software fixed the bug last week.",
            "Share prices fell sharply on Monday.",
            "The photo shows a cat on a windowsill.",
        ).forEach { assertFalse(it, BoilerplateStripper.isClutter(it)) }
        assertTrue(BoilerplateStripper.isClutter("5 min read"))
        assertTrue(BoilerplateStripper.isClutter("1.2K shares"))
        assertTrue(BoilerplateStripper.isClutter("Thanks for reading! Subscribe for free to receive new posts."))
    }

    @Test fun wikipediaPronunciationIsRemoved() {
        assertEquals(
            "William Shakespeare was an English playwright.",
            BoilerplateStripper.cleanParagraph("William Shakespeare (/ˈʃeɪkspɪər/ SHAYK-speer) was an English playwright."),
        )
        assertEquals("History[1] begins here.".replace("[1]", ""), BoilerplateStripper.cleanParagraph("History begins here.[edit]"))
    }

    @Test fun newsletterBoilerplateIsStripped() {
        val body = para(3) + "<div class='subscription-widget-wrap'><p>Subscribe to keep reading</p><form><input></form></div>" +
            para(2, "Later") + "<h3>Related posts</h3><ul><li><a href='/a'>Another post entirely</a></li></ul>"
        val doc = WebArticleExtractor.extract("https://x.substack.com/p/post", page(body)).document
        val text = all(doc)
        assertFalse(text.any { "keep reading" in it || "Another post" in it })
        assertTrue(text.any { it.startsWith("Later 2.") })
    }

    @Test fun javascriptShellNeedsRendering() {
        val html = "<html><head><title>App</title></head><body><div id='root'></div><noscript>You need to enable JavaScript to run this app.</noscript>" +
            "<script>${"var a=1;".repeat(4000)}</script></body></html>"
        val result = WebArticleExtractor.extract("https://spa.example/post/1", html)
        assertTrue(result.quality.needsRender)
        val ok = WebArticleExtractor.extract("https://a.com/x", page(para(6)))
        assertFalse(ok.quality.needsRender)
    }

    @Test fun paywallIsFlagged() {
        val head = "<title>T</title><script type='application/ld+json'>{\"@type\":\"NewsArticle\",\"isAccessibleForFree\":false}</script>"
        assertTrue(WebArticleExtractor.extract("https://a.com/x", page(para(3), head)).document.incomplete)
        val prompt = para(3) + "<p>Subscribe to continue reading this article.</p>"
        assertTrue(WebArticleExtractor.extract("https://a.com/x", page(prompt)).document.incomplete)
    }

    @Test fun findsTheNextPage() {
        val html = page(para(4), "<title>T</title><link rel='next' href='/story?page=2'>")
        assertEquals("https://a.com/story?page=2", WebArticleExtractor.extract("https://a.com/story", html).nextPageUrl)
        val byText = page(para(4) + "<a href='https://a.com/story/page/3'>Next</a>")
        assertEquals("https://a.com/story/page/3", WebArticleExtractor.extract("https://a.com/story/page/2", byText).nextPageUrl)
        assertEquals("https://a.com/s?page=3", Pagination.expectedNext("https://a.com/s?page=2"))
        val offSite = page(para(4), "<title>T</title><link rel='next' href='https://other.com/x'>")
        assertNull(WebArticleExtractor.extract("https://a.com/story", offSite).nextPageUrl)
    }

    // ---- Site handlers ----------------------------------------------------------------

    @Test fun wikipediaUsesSectionsAndDropsReferences() {
        val url = "https://en.m.wikipedia.org/wiki/Cat?utm=x"
        assertEquals("https://en.wikipedia.org/api/rest_v1/page/html/Cat?redirect=true", WikipediaHandler.apiUrl(url))
        assertNull(WikipediaHandler.apiUrl("https://en.wikipedia.org/wiki/Special:Random"))
        val body = """
            <html><head><title>Cat</title></head><body>
            <section data-mw-section-id="0"><table class="infobox"><tr><td>Infobox junk</td></tr></table>
              <p>The cat (<span>/kæt/</span>) is a small mammal.<sup class="reference">[1]</sup></p></section>
            <section data-mw-section-id="1"><h2>Behavior</h2><p>Cats sleep a lot.</p>
              <section data-mw-section-id="2"><h3>Purring</h3><p>Cats purr.</p></section></section>
            <section data-mw-section-id="3"><h2>References</h2><ol class="references"><li>Ref</li></ol></section>
            </body></html>
        """.trimIndent()
        val doc = WikipediaHandler.parse(url, body, ExtractOptions()).document
        assertEquals("Cat", doc.title)
        assertEquals(listOf(null, "Behavior"), doc.chapters.map { it.title })
        assertEquals(listOf("Cat", "The cat is a small mammal."), doc.chapters[0].paragraphs)
        assertEquals(listOf("Behavior", "Cats sleep a lot.", "Purring", "Cats purr."), doc.chapters[1].paragraphs)
        assertEquals("en", doc.language)
        assertEquals("Wikipedia", doc.siteName)
    }

    @Test fun redditReadsPostAndTopComments() {
        assertEquals(
            "https://www.reddit.com/comments/abc123.json?raw_json=1&sort=top&limit=60&depth=4",
            RedditHandler.apiUrl("https://www.reddit.com/r/cats/comments/abc123/why_do_cats_purr/?share_id=x"),
        )
        assertNull(RedditHandler.apiUrl("https://www.reddit.com/r/cats/"))
        val json = """
            [{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"Why do cats purr?","author":"op_user",
              "subreddit":"cats","is_self":true,"selftext":"Mine purrs **all** the time.\n\n> Quoted line","created_utc":1772532000}}]}},
             {"kind":"Listing","data":{"children":[
               {"kind":"t1","data":{"author":"AutoModerator","body":"Rules","stickied":true}},
               {"kind":"t1","data":{"author":"vet_jane","body":"It's the larynx.","replies":{"kind":"Listing","data":{"children":[
                 {"kind":"t1","data":{"author":"curious","body":"Neat!","replies":""}},
                 {"kind":"t1","data":{"author":"[deleted]","body":"[deleted]","replies":""}}]}}}},
               {"kind":"t1","data":{"author":"someone","body":"[removed]","replies":""}},
               {"kind":"more","data":{"count":5}}]}}]
        """.trimIndent()
        val doc = RedditHandler.parse("https://www.reddit.com/r/cats/comments/abc123/x/", json, ExtractOptions(spokenIntro = true)).document
        assertEquals("Why do cats purr?", doc.title)
        assertEquals(
            listOf("Why do cats purr?", "Posted in r/cats by op_user on March 3, 2026.", "Mine purrs **all** the time.", "Quoted line"),
            doc.chapters[0].paragraphs,
        )
        assertEquals(listOf(null, "Comment by vet_jane"), doc.chapters.map { it.title })
        assertEquals(listOf("Comment by vet_jane", "It's the larynx.", "Reply from curious:", "Neat!"), doc.chapters[1].paragraphs)
    }

    @Test fun hackerNewsReadsStoryAndBusiestThreads() {
        assertEquals("https://hn.algolia.com/api/v1/items/42", HackerNewsHandler.apiUrl("https://news.ycombinator.com/item?id=42"))
        val json = """
            {"id":42,"title":"Show HN: A purr detector","author":"pg","url":"https://purr.example/x","text":null,"children":[
              {"author":"quiet","text":"<p>Cool.</p>","children":[]},
              {"author":"busy","text":"How does it work?<p>Second paragraph.</p>","children":[
                {"author":"pg","text":"Microphones.","children":[]},{"author":null,"text":null,"children":[]}]}]}
        """.trimIndent()
        val doc = HackerNewsHandler.parse("https://news.ycombinator.com/item?id=42", json, ExtractOptions()).document
        assertEquals("Show HN: A purr detector", doc.title)
        assertEquals(listOf("Show HN: A purr detector", "Link to purr.example."), doc.chapters[0].paragraphs)
        assertEquals(listOf(null, "Comment by busy", "Comment by quiet"), doc.chapters.map { it.title })
        assertEquals(
            listOf("Comment by busy", "How does it work?", "Second paragraph.", "Reply from pg:", "Microphones."),
            doc.chapters[1].paragraphs,
        )
    }

    // ---- Position, language, time -----------------------------------------------------

    @Test fun positionSurvivesAReload() {
        val segmenter = SentenceSegmenter()
        val before = Document("T", chapters = listOf(Chapter(null, listOf("T", "Intro text here.", "Target one. Target two.", "End."))))
        val beforeSegs = segmenter.segment(before)
        val at = beforeSegs.first { it.text == "Target two." }.index
        val anchor = PositionMapper.anchor(before, beforeSegs, at)!!
        val after = Document(
            "T",
            chapters = listOf(
                Chapter(null, listOf("T", "A new subtitle.", "By someone.", "Intro text here.")),
                Chapter("Part", listOf("Part", "Target one. Target two.", "End.")),
            ),
        )
        val afterSegs = segmenter.segment(after)
        assertEquals("Target two.", afterSegs[PositionMapper.map(anchor, after, afterSegs)].text)
    }

    @Test fun guessesLanguageFromCommonWords() {
        assertEquals("fr", LanguageGuess.guess("Le chat est sur la table et il ne veut pas descendre pour le dîner. ".repeat(5)))
        assertEquals("en", LanguageGuess.guess("The cat is on the table and it does not want to come down for dinner. ".repeat(5)))
        assertNull(LanguageGuess.guess("Too short"))
    }

    @Test fun listeningTimeLabels() {
        assertEquals("Less than 1 min", ListeningTime.label(100))
        assertEquals("10 min", ListeningTime.label(1600))
        assertEquals("5 min", ListeningTime.label(1600, speed = 2f))
        assertEquals("1 hr 5 min", ListeningTime.label(160 * 65))
    }
}
