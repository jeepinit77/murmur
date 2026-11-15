package app.murmur.web

import android.util.Log
import app.murmur.library.ImportException
import app.murmur.library.Imported
import app.murmur.library.Importer
import app.murmur.text.Chapter
import app.murmur.text.Document
import app.murmur.text.PendingTable
import app.murmur.text.PlainText
import app.murmur.text.WebArticleExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A web import: the article plus every address it can be found under again. */
class WebImport(val imported: Imported, val urlKeys: List<String>)

/**
 * Imports a web page as well as possible: site APIs where they're better (Wikipedia, Reddit,
 * Hacker News), a plain download otherwise, a rendering browser when the download has no
 * readable text, and following "next page" links for multi-page articles.
 */
class WebImporter(
    private val fetcher: PageFetcher,
    private val renderer: WebViewRenderer,
    private val pdf: suspend (bytes: ByteArray, name: String) -> Document,
    /** Summarizes tables in [TableMode.SUMMARIZE]; null where no on-device model is available. */
    private val tableAi: TableAi? = null,
) {

    /**
     * @param onStage short progress text for the notification, such as "Loading page".
     */
    suspend fun import(
        rawUrl: String,
        options: ExtractOptions,
        titleHint: String? = null,
        onStage: (String) -> Unit = {},
    ): WebImport {
        val url = UrlNormalizer.normalize(rawUrl)
        val keys = linkedSetOf(UrlNormalizer.key(url))

        SiteHandlers.find(url)?.let { (handler, api) ->
            onStage(STAGE_LOADING)
            fromHandler(handler, api, url, options, onStage)?.let { return done(it.document, keys, it.canonicalUrl, url) }
        }

        onStage(STAGE_LOADING)
        val page = fetcher.fetch(url)
        keys += UrlNormalizer.key(page.finalUrl)
        // Short links (t.co, reddit.com/r/x/s/…) only reveal a supported site after the redirect.
        if (page.finalUrl != url) {
            SiteHandlers.find(page.finalUrl)?.let { (handler, api) ->
                fromHandler(handler, api, page.finalUrl, options, onStage)?.let { return done(it.document, keys, it.canonicalUrl, page.finalUrl) }
            }
        }
        when {
            page.isPdf -> {
                val name = page.finalUrl.substringBefore('?').substringAfterLast('/').ifBlank { "Document.pdf" }
                val doc = pdf(page.bytes, name)
                return WebImport(Imported(doc, page.finalUrl, Importer.KIND_PDF), keys.toList())
            }
            page.isPlainText -> {
                val title = titleHint ?: WebArticleExtractor.slugTitle(page.finalUrl)
                val doc = TableSummaries.extract(options, tableAi, { onStage(STAGE_TABLES) }, { d, later -> d.copy(pendingTables = later) }) { tables ->
                    withContext(Dispatchers.Default) { PlainText.toDocument(page.text(), title, options.tables, tables) }
                }
                if (doc.isEmpty) throw ImportException(NO_TEXT)
                return WebImport(Imported(doc, page.finalUrl, Importer.KIND_TEXT), keys.toList())
            }
            !page.isHtml -> throw ImportException("This link isn't a web page or PDF")
        }

        var article = extract(page.finalUrl, { page.text() }, options, titleHint, page.headerLanguage, onStage)
        if (article.quality.needsRender) {
            Log.i(TAG, "Rendering ${page.finalUrl}: ${article.quality.reason}")
            onStage(STAGE_RENDERING)
            val rendered = try {
                renderer.render(page.finalUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Render failed", e)
                null
            }
            if (rendered != null) {
                val second = extract(rendered.url, { rendered.html }, options, titleHint, page.headerLanguage, onStage)
                if (second.quality.score > article.quality.score) article = second
            }
        }
        var document = article.document
        if (document.isEmpty || article.quality.wordCount < MIN_WORDS) throw ImportException(NO_TEXT)

        document = followPages(document, article, options, onStage)
        return done(document, keys, article.canonicalUrl, page.finalUrl)
    }

    private suspend fun extract(
        url: String,
        html: () -> String,
        options: ExtractOptions,
        hint: String?,
        lang: String?,
        onStage: (String) -> Unit,
    ): WebArticle {
        val source by lazy(html)
        return TableSummaries.extract(options, tableAi, { onStage(STAGE_TABLES) }, ::withPending) { tables ->
            withContext(Dispatchers.Default) { WebArticleExtractor.extract(url, source, options, hint, lang, tables) }
        }
    }

    private suspend fun fromHandler(
        handler: SiteHandler,
        api: String,
        url: String,
        options: ExtractOptions,
        onStage: (String) -> Unit,
    ): WebArticle? =
        try {
            val body = fetcher.fetch(api, PageFetcher.ACCEPT_JSON).text()
            TableSummaries.extract(options, tableAi, { onStage(STAGE_TABLES) }, ::withPending) { tables ->
                withContext(Dispatchers.Default) { handler.parse(url, body, options, tables) }
            }.takeIf { !it.document.isEmpty }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fall back to reading the page itself.
            Log.w(TAG, "${handler::class.simpleName} failed for $url", e)
            null
        }

    /** Appends the following pages of a multi-page article, up to [MAX_PAGES]. */
    private suspend fun followPages(first: Document, article: WebArticle, options: ExtractOptions, onStage: (String) -> Unit): Document {
        var next = article.nextPageUrl ?: return first
        val chapters = first.chapters.toMutableList()
        val seen = mutableSetOf(UrlNormalizer.key(article.document.url ?: ""), UrlNormalizer.key(next))
        val seenText = first.chapters.flatMap { it.paragraphs }.filter { it.length > 60 }.toMutableSet()
        val pending = first.pendingTables.toMutableList()
        var pageNumber = 2
        val pageOptions = options.copy(spokenIntro = false)
        while (pageNumber <= MAX_PAGES) {
            onStage("Loading page $pageNumber")
            val page = try {
                fetcher.fetch(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                break
            }
            if (!page.isHtml) break
            val more = extract(page.finalUrl, { page.text() }, pageOptions, null, null, onStage)
            val paragraphs = more.document.chapters.flatMap { it.paragraphs }
            val fresh = paragraphs.filter { it.length > 60 }
            if (fresh.isEmpty() || fresh.count { it in seenText } > fresh.size / 2) break
            seenText += fresh
            pending += more.document.pendingTables
            val label = "Page $pageNumber"
            // Drop the repeated title at the start of each page.
            val pageChapters = more.document.chapters.mapIndexed { i, ch ->
                if (i == 0) ch.copy(title = label, paragraphs = listOf(label) + ch.paragraphs.drop(1)) else ch
            }
            chapters += if (options.headingsAsChapters) pageChapters
            else listOf(Chapter(label, listOf(label) + pageChapters.flatMap { it.paragraphs.drop(if (it === pageChapters.first()) 1 else 0) }))
            val candidate = more.nextPageUrl ?: break
            if (!seen.add(UrlNormalizer.key(candidate))) break
            next = candidate
            pageNumber++
        }
        return first.copy(chapters = chapters, pendingTables = pending)
    }

    private fun withPending(article: WebArticle, later: List<PendingTable>) =
        article.copy(document = article.document.copy(pendingTables = later))

    private fun done(document: Document, keys: MutableSet<String>, canonical: String?, url: String): WebImport {
        canonical?.let { keys += UrlNormalizer.key(it) }
        val source = canonical ?: document.url ?: url
        return WebImport(Imported(document.copy(url = source), source, Importer.KIND_WEB), keys.toList())
    }

    companion object {
        private const val TAG = "MurmurWeb"
        const val MAX_PAGES = 10
        private const val MIN_WORDS = 30
        const val STAGE_LOADING = "Loading page"
        const val STAGE_RENDERING = "Rendering page"
        const val STAGE_TABLES = "Summarizing tables"
        const val NO_TEXT = "Couldn't find readable text on that page"
    }
}
