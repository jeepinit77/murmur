package app.murmur.web

import app.murmur.text.Chapter
import app.murmur.text.Document
import app.murmur.text.HtmlParagraphs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.time.Instant

/**
 * Sites with a cleaner source than their web page. Each handler maps a page address to an
 * API request and turns the response into an article; the caller does the networking.
 */
interface SiteHandler {
    /** The API address to fetch for [url], or null when this handler doesn't apply. */
    fun apiUrl(url: String): String?

    /** @param tables describes tables in [TableMode.SUMMARIZE]. */
    fun parse(url: String, body: String, options: ExtractOptions, tables: TableSummarizer = TableReader): WebArticle
}

object SiteHandlers {
    val all: List<SiteHandler> = listOf(WikipediaHandler, RedditHandler, HackerNewsHandler)

    fun find(url: String): Pair<SiteHandler, String>? =
        all.firstNotNullOfOrNull { h -> h.apiUrl(url)?.let { h to it } }

    /** Comment threads read per discussion page, and how deep replies go. */
    const val MAX_THREADS = 10
    const val MAX_DEPTH = 3
    const val MAX_REPLIES = 3

    internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

object WikipediaHandler : SiteHandler {
    private val PAGE = Regex("""^https?://([a-z][a-z0-9-]*)\.(?:m\.)?wikipedia\.org/wiki/([^?#]+)""", RegexOption.IGNORE_CASE)
    private val NAMESPACES = setOf(
        "special", "file", "talk", "user", "user_talk", "wikipedia", "category", "template", "help", "portal", "draft", "module",
        "media", "image", "mediawiki",
    )
    private val END_SECTIONS = setOf(
        "see also", "references", "notes", "footnotes", "citations", "external links", "further reading", "bibliography",
        "sources", "works cited", "explanatory notes", "notes and references", "references and notes", "general references",
        "gallery",
    )
    private const val NOISE =
        ".infobox, .navbox, .vertical-navbox, .hatnote, .metadata, .reference, .reflist, .references, .mw-references-wrap, " +
            ".mw-editsection, .mbox-small, .ambox, .sidebar, .noprint, .mw-empty-elt, style, link, .geo, #coordinates, " +
            "math, .mwe-math-element, sup, .shortdescription, .portalbox, .side-box, .thumb, .gallery, .toc, " +
            ".mw-cite-backlink, .navigation-not-searchable, .sistersitebox, [role=note], .ext-phonos"

    override fun apiUrl(url: String): String? {
        val m = PAGE.find(url) ?: return null
        val lang = m.groupValues[1].lowercase()
        val title = m.groupValues[2]
        val ns = title.substringBefore(':', "").lowercase()
        if (ns in NAMESPACES || title.lowercase() == "main_page") return null
        return "https://$lang.wikipedia.org/api/rest_v1/page/html/$title?redirect=true"
    }

    override fun parse(url: String, body: String, options: ExtractOptions, tables: TableSummarizer): WebArticle {
        val m = PAGE.find(url)
        val lang = m?.groupValues?.get(1)?.lowercase() ?: "en"
        val html = Jsoup.parse(body, url)
        val title = html.title().trim().ifEmpty {
            java.net.URLDecoder.decode(m?.groupValues?.get(2).orEmpty(), "UTF-8").replace('_', ' ')
        }
        html.select(NOISE).remove()
        val sections = html.body().children().filter { it.normalName() == "section" }
            .ifEmpty { listOf(html.body()) }
        val lead = buildList {
            add(title)
            if (options.spokenIntro) add("From Wikipedia, the free encyclopedia.")
        }
        val chapters = ArrayList<Chapter>()
        for ((i, section) in sections.withIndex()) {
            val heading = section.children().firstOrNull { it.normalName().matches(Regex("h[1-6]")) }
            val headingText = heading?.text()?.trim()
            if (headingText != null && headingText.lowercase() in END_SECTIONS) continue
            heading?.remove()
            ArticleShaper.applyOptions(section, options, tables)
            if (i == 0 || headingText == null) {
                val lines = ArticleShaper.clean(HtmlParagraphs.extract(section))
                if (chapters.isEmpty()) chapters += Chapter(null, lead + lines)
                else chapters[chapters.lastIndex] = chapters.last().let { it.copy(paragraphs = it.paragraphs + lines) }
                continue
            }
            val chapter = ArticleShaper.section(section, headingText) ?: continue
            if (options.headingsAsChapters || chapters.isEmpty()) {
                chapters += chapter
            } else {
                chapters[chapters.lastIndex] = chapters.last().let { it.copy(paragraphs = it.paragraphs + chapter.paragraphs) }
            }
        }
        if (chapters.isEmpty()) chapters += Chapter(null, lead)
        val canonical = m?.let { "https://$lang.wikipedia.org/wiki/${it.groupValues[2]}" }
        val doc = Document(
            title = title, chapters = chapters, url = canonical ?: url, siteName = "Wikipedia", language = lang,
        )
        return WebArticle(doc, canonicalUrl = canonical)
    }
}

object RedditHandler : SiteHandler {
    private val POST = Regex(
        """^https?://(?:www\.|old\.|new\.|np\.|m\.)?reddit\.com/(?:r/[^/]+/)?comments/([a-z0-9]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val SHORT = Regex("""^https?://redd\.it/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val SKIP_AUTHORS = setOf("[deleted]", "AutoModerator")
    private val SKIP_BODIES = setOf("[deleted]", "[removed]")

    override fun apiUrl(url: String): String? {
        val id = (POST.find(url) ?: SHORT.find(url))?.groupValues?.get(1) ?: return null
        return "https://www.reddit.com/comments/$id.json?raw_json=1&sort=top&limit=60&depth=${SiteHandlers.MAX_DEPTH + 1}"
    }

    override fun parse(url: String, body: String, options: ExtractOptions, tables: TableSummarizer): WebArticle {
        val root = SiteHandlers.json.parseToJsonElement(body).jsonArray
        val post = root[0].jsonObject.data().children().first().data()
        val title = post.str("title") ?: "Reddit post"
        val author = post.str("author")?.takeIf { it !in SKIP_AUTHORS }
        val subreddit = post.str("subreddit")
        val first = buildList {
            add(title)
            if (options.spokenIntro) {
                val line = listOfNotNull(
                    subreddit?.let { "Posted in r/$it" },
                    author?.let { "by $it" },
                    post.long("created_utc")?.let { PageMetadataDates.fromEpoch(it) }?.let { "on $it" },
                ).joinToString(" ")
                if (line.isNotBlank()) add("$line.")
            }
            if (post.bool("is_self") == false) post.str("domain")?.let { add("Link to $it.") }
            addAll(markdownParagraphs(post.str("selftext").orEmpty()))
        }
        val chapters = arrayListOf(Chapter(null, first))
        val comments = root.getOrNull(1)?.jsonObject?.data()?.children().orEmpty()
            .filter { it.kind() == "t1" }.map { it.data() }
            .filter { it.bool("stickied") != true && isReadable(it) }
            .take(SiteHandlers.MAX_THREADS)
        comments.forEach { c ->
            val name = c.str("author") ?: "someone"
            val paragraphs = ArrayList<String>()
            paragraphs += "Comment by $name"
            paragraphs += markdownParagraphs(c.str("body").orEmpty())
            addReplies(c, depth = 2, out = paragraphs)
            chapters += Chapter("Comment by $name", paragraphs)
        }
        val doc = Document(
            title = title, author = author, chapters = chapters, url = url, siteName = "Reddit",
            published = post.long("created_utc")?.let { Instant.ofEpochSecond(it).toString() },
        )
        return WebArticle(doc)
    }

    private fun addReplies(comment: JsonObject, depth: Int, out: MutableList<String>) {
        if (depth > SiteHandlers.MAX_DEPTH) return
        val replies = (comment["replies"] as? JsonObject)?.data()?.children().orEmpty()
            .filter { it.kind() == "t1" }.map { it.data() }.filter(::isReadable).take(SiteHandlers.MAX_REPLIES)
        replies.forEach { r ->
            out += "Reply from ${r.str("author") ?: "someone"}:"
            out += markdownParagraphs(r.str("body").orEmpty())
            addReplies(r, depth + 1, out)
        }
    }

    private fun isReadable(c: JsonObject): Boolean =
        c.str("author") !in SKIP_AUTHORS && c.str("body")?.trim() !in SKIP_BODIES && !c.str("body").isNullOrBlank()

    private fun JsonObject.data(): JsonObject = this["data"]?.jsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.children(): List<JsonObject> = (this["children"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.kind(): String? = (this["kind"] as? JsonPrimitive)?.contentOrNull
}

object HackerNewsHandler : SiteHandler {
    private val ITEM = Regex("""^https?://news\.ycombinator\.com/item\?(?:.*&)?id=(\d+)""", RegexOption.IGNORE_CASE)

    override fun apiUrl(url: String): String? =
        ITEM.find(url)?.groupValues?.get(1)?.let { "https://hn.algolia.com/api/v1/items/$it" }

    override fun parse(url: String, body: String, options: ExtractOptions, tables: TableSummarizer): WebArticle {
        val item = SiteHandlers.json.parseToJsonElement(body).jsonObject
        val author = item.str("author")
        val title = item.str("title") ?: author?.let { "Comment by $it on Hacker News" } ?: "Hacker News discussion"
        val first = buildList {
            add(title)
            if (options.spokenIntro) {
                listOfNotNull(author?.let { "Posted by $it" }, PageMetadata.formatDate(item.str("created_at")))
                    .takeIf { it.isNotEmpty() }?.let { add(it.joinToString(". ") + ".") }
            }
            item.str("url")?.let { link -> UrlNormalizer.displayHost(link)?.let { add("Link to $it.") } }
            addAll(htmlParagraphs(item.str("text")))
        }
        val chapters = arrayListOf(Chapter(null, first))
        val threads = children(item).filter(::isReadable).sortedByDescending(::descendants).take(SiteHandlers.MAX_THREADS)
        threads.forEach { c ->
            val name = c.str("author") ?: "someone"
            val paragraphs = arrayListOf("Comment by $name")
            paragraphs += htmlParagraphs(c.str("text"))
            addReplies(c, depth = 2, out = paragraphs)
            chapters += Chapter("Comment by $name", paragraphs)
        }
        val doc = Document(
            title = title, author = author, chapters = chapters, url = url, siteName = "Hacker News",
            published = item.str("created_at"), language = "en",
        )
        return WebArticle(doc)
    }

    private fun addReplies(c: JsonObject, depth: Int, out: MutableList<String>) {
        if (depth > SiteHandlers.MAX_DEPTH) return
        children(c).filter(::isReadable).sortedByDescending(::descendants).take(SiteHandlers.MAX_REPLIES).forEach { r ->
            out += "Reply from ${r.str("author") ?: "someone"}:"
            out += htmlParagraphs(r.str("text"))
            addReplies(r, depth + 1, out)
        }
    }

    private fun children(o: JsonObject): List<JsonObject> = (o["children"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun descendants(o: JsonObject): Int = children(o).sumOf { 1 + descendants(it) }

    private fun isReadable(o: JsonObject) = !o.str("author").isNullOrBlank() && !o.str("text").isNullOrBlank()

    private fun htmlParagraphs(html: String?): List<String> =
        if (html.isNullOrBlank()) emptyList() else HtmlParagraphs.extract(Jsoup.parseBodyFragment(html.replace("<p>", "\n<p>")).body())
}

internal object PageMetadataDates {
    fun fromEpoch(seconds: Long): String? = PageMetadata.formatDate(Instant.ofEpochSecond(seconds).toString())
}

/** Reddit-flavored Markdown to plain paragraphs; inline markup is cleaned later by SpeechText. */
internal fun markdownParagraphs(md: String): List<String> = md
    .replace("\r\n", "\n")
    .split(Regex("""\n\s*\n"""))
    .map { block ->
        block.lines().joinToString(" ") { line ->
            line.trim().removePrefix(">").trim()
                .replace(Regex("""^[-*+]\s+"""), "")
                .replace(Regex("""\[([^\]]+)]\((https?://[^)]+)\)"""), "$1")
                .replace(Regex("""^#+\s*"""), "")
                .replace(Regex("""~~(.+?)~~"""), "$1")
                .replace("&nbsp;", " ")
        }.replace(Regex("""\s+"""), " ").trim()
    }
    .filter { it.isNotBlank() && it.any(Char::isLetterOrDigit) && it != "&#x200B;" }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
