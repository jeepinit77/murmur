package app.murmur.text

import app.murmur.web.ArticleShaper
import app.murmur.web.BoilerplateStripper
import app.murmur.web.ExtractOptions
import app.murmur.web.LanguageGuess
import app.murmur.web.PageMetadata
import app.murmur.web.Pagination
import app.murmur.web.PaywallDetector
import app.murmur.web.QualityCheck
import app.murmur.web.TableReader
import app.murmur.web.TableSummarizer
import app.murmur.web.UrlNormalizer
import app.murmur.web.WebArticle
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Turns a downloaded web page into a clean, readable [Document] (Mozilla Readability port). */
object WebArticleExtractor {

    private val TITLE_SEPARATOR = Regex("""\s+[|\-–—·•:»]{1,2}\s+""")
    private val SUBTITLE_SELECTORS =
        "[class*=subtitle], [class*=standfirst], [class*=dek], [class*=subhead], [class*=deck], [itemprop=alternativeHeadline]"

    fun extract(
        url: String,
        html: String,
        options: ExtractOptions = ExtractOptions(),
        titleHint: String? = null,
        headerLanguage: String? = null,
        tables: TableSummarizer = TableReader,
    ): WebArticle {
        val raw = Jsoup.parse(html, url)
        val meta = PageMetadata.read(raw)
        val nextPage = Pagination.nextPage(raw, url)

        val cleaned = raw.clone()
        cleaned.body()?.let {
            BoilerplateStripper.stripDom(it)
            // Before Readability, which drops figures, short code notices and some tables.
            ArticleShaper.applyOptions(it, options, tables)
        }
        val article = runCatching { Readability4J(url, cleaned.outerHtml()).parse() }.getOrNull()
        val content = article?.content?.takeIf { it.isNotBlank() && Jsoup.parseBodyFragment(it).text().length > 200 }
        val root: Element = if (content != null) {
            Jsoup.parseBodyFragment(content, url).body()
        } else {
            (cleaned.body() ?: Element("body")).also { it.select("header, footer, nav, [role=navigation], [role=banner]").remove() }
        }

        val title = chooseTitle(url, meta, article?.title, raw.title(), raw.selectFirst("h1")?.text(), titleHint)
        val author = meta.author ?: article?.byline?.let(::cleanByline)
        val bodyStart = root.text().take(600)

        val lead = buildList {
            add(title)
            if (options.spokenIntro) {
                subtitle(raw, meta, title, bodyStart)?.let(::add)
                introLine(author, meta.siteName, meta.published)?.let(::add)
            }
        }
        val chapters = ArticleShaper.chapters(root, options, title, lead)
        val document = Document(title = title, author = author, chapters = chapters)
        val words = document.wordCount() - lead.sumOf { ListeningTime.words(it) }
        val language = meta.language
            ?: headerLanguage?.let(PageMetadata::primaryLanguage)
            ?: LanguageGuess.guess(document.plainText())
        val lastParagraphs = chapters.lastOrNull()?.paragraphs.orEmpty()
        val quality = QualityCheck.assess(raw, words, readabilityFound = content != null, content = root)
        return WebArticle(
            document = document.copy(
                url = meta.canonicalUrl ?: url,
                siteName = meta.siteName,
                published = meta.published,
                language = language,
                incomplete = PaywallDetector.isIncomplete(raw, meta, words, lastParagraphs),
            ),
            canonicalUrl = meta.canonicalUrl,
            nextPageUrl = nextPage,
            quality = quality,
        )
    }

    /** "By Jane Doe. The Atlantic. March 3, 2026." from whichever parts exist. */
    fun introLine(author: String?, siteName: String?, published: String?): String? {
        val parts = listOfNotNull(
            author?.let { "By $it" },
            siteName?.takeIf { s -> author == null || !s.equals(author, ignoreCase = true) },
            PageMetadata.formatDate(published),
        )
        return if (parts.isEmpty()) null else parts.joinToString(". ") { it.trimEnd('.') } + "."
    }

    fun chooseTitle(
        url: String,
        meta: PageMetadata,
        readabilityTitle: String?,
        htmlTitle: String?,
        h1: String?,
        titleHint: String?,
    ): String {
        val host = UrlNormalizer.displayHost(url)
        return listOf(meta.title, meta.headline, readabilityTitle, htmlTitle, h1, titleHint)
            .asSequence()
            .mapNotNull { it?.replace(Regex("\\s+"), " ")?.trim() }
            .map { stripSiteName(it, meta.siteName, host) }
            .firstOrNull { it.length >= 3 && it.any(Char::isLetter) && !it.startsWith("http", ignoreCase = true) }
            ?: slugTitle(url)
            ?: host
            ?: "Web page"
    }

    /** "Why cats purr | The Science Desk" -> "Why cats purr", when the suffix is the site's name. */
    fun stripSiteName(title: String, siteName: String?, host: String?): String {
        val parts = title.split(TITLE_SEPARATOR)
        if (parts.size < 2) return title
        val names = listOfNotNull(siteName, host, host?.substringBeforeLast('.')).map(::squash).filter { it.isNotEmpty() }
        fun isSite(part: String): Boolean {
            val p = squash(part)
            return p.isNotEmpty() && names.any { n -> p == n || n.startsWith(p) || p.startsWith(n) }
        }
        return when {
            isSite(parts.last()) -> title.substring(0, title.lastIndexOf(parts.last())).replace(Regex("""[\s|\-–—·•:»]+$"""), "")
            isSite(parts.first()) -> title.substring(parts.first().length).replace(Regex("""^[\s|\-–—·•:»]+"""), "")
            else -> title
        }.ifBlank { title }
    }

    /** "/2026/11/why-cats-purr-12345.html" -> "Why cats purr". */
    fun slugTitle(url: String): String? {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val segment = path.split('/').lastOrNull { s -> s.isNotBlank() && s.any(Char::isLetter) } ?: return null
        val words = java.net.URLDecoder.decode(segment.substringBeforeLast('.'), "UTF-8")
            .replace(Regex("""[-_+]\d{4,}$"""), "")
            .replace(Regex("""^\d{4,}[-_]"""), "")
            .split('-', '_', '+', ' ')
            .filter { it.isNotBlank() }
        if (words.size < 2 && (words.firstOrNull()?.length ?: 0) < 4) return null
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    private fun squash(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    private fun cleanByline(byline: String): String? = byline.trim()
        .replace(Regex("""^by\s+""", RegexOption.IGNORE_CASE), "")
        .substringBefore('|').substringBefore('\n').trim()
        .takeIf { it.length in 3..80 }

    private fun subtitle(raw: org.jsoup.nodes.Document, meta: PageMetadata, title: String, bodyStart: String): String? {
        val fromPage = raw.select(SUBTITLE_SELECTORS).asSequence().map { it.text().trim() }
            .firstOrNull { it.length in 20..400 && !it.equals(title, ignoreCase = true) }
        val candidate = fromPage ?: meta.description?.trim()?.takeIf { it.length in 20..400 }
        return candidate?.takeIf { c ->
            val probe = c.take(60).trimEnd('.', '…')
            !bodyStart.contains(probe, ignoreCase = true) && !BoilerplateStripper.isClutter(c)
        }
    }
}
