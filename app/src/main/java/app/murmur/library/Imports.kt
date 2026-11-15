package app.murmur.library

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.murmur.settings.Settings
import app.murmur.text.Document
import app.murmur.text.SentenceSegmenter
import app.murmur.text.WebArticleExtractor
import app.murmur.web.PositionMapper
import app.murmur.web.SharedLink
import app.murmur.web.UrlNormalizer
import app.murmur.web.WebImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Brings things into the library: shared text, files and links. Links already in the library
 * open the saved copy, and links that fail for network reasons are saved to retry later.
 */
class Imports(
    private val context: Context,
    private val importer: Importer,
    private val web: WebImporter,
    private val library: LibraryStore,
    private val settings: Settings,
) {
    /** A document in the library, and whether it was already there. */
    class Outcome(val meta: DocMeta, val document: Document, val existing: Boolean)

    /**
     * Imports shared or pasted text, fetching the page when the text is a link share.
     * [importAgain] replaces a saved copy of the page instead of returning it (see [link]).
     */
    suspend fun text(
        text: String,
        subject: String? = null,
        onStage: (String) -> Unit = {},
        importAgain: Boolean = false,
    ): Outcome {
        val link = SharedLink.parse(text, subject)
        if (link != null) return link(link.url, link.titleHint, onStage, importAgain)
        return save(importer.fromText(text, subject))
    }

    /**
     * Imports a web page. If the same article is already in the library, returns it instead,
     * unless [importAgain] is set: then the page is fetched again and replaces the saved copy,
     * starting from the beginning. A network failure saves the link as pending and rethrows,
     * so the caller can say so; when importing again, the saved copy is left as it was.
     */
    suspend fun link(
        url: String,
        titleHint: String? = null,
        onStage: (String) -> Unit = {},
        importAgain: Boolean = false,
    ): Outcome {
        val normalized = UrlNormalizer.normalize(url)
        if (!importAgain) existing(UrlNormalizer.key(normalized))?.let { return it }
        val result = try {
            web.import(normalized, settings.extractOptions(), titleHint, onStage)
        } catch (e: ImportException) {
            if (e.retryable && !(importAgain && existing(UrlNormalizer.key(normalized)) != null)) {
                val title = titleHint ?: WebArticleExtractor.slugTitle(normalized) ?: UrlNormalizer.displayHost(normalized) ?: normalized
                val pending = library.findByUrlKey(UrlNormalizer.key(normalized))
                    ?: library.addPending(title, normalized, listOf(UrlNormalizer.key(normalized)), e.message)
                scheduleRetry(pending.id)
                throw PendingImportException(e.message ?: "Couldn't load the page", pending)
            }
            throw e
        }
        if (!importAgain) existing(*result.urlKeys.toTypedArray())?.let { return it }
        // The same link was saved earlier (while offline, or a copy being imported again):
        // fill in that entry instead of adding another.
        library.findByUrlKey(*result.urlKeys.toTypedArray())?.takeIf { importAgain || it.status != DocStatus.READY }?.let { saved ->
            val doc = result.imported.document
            val count = withContext(Dispatchers.Default) { SentenceSegmenter().segment(doc).size }
            library.replace(saved.id, doc, result.imported.source, result.imported.kind, count, result.urlKeys)
                ?.let {
                    library.setProgress(it.id, 0)
                    return Outcome(it, doc, existing = false)
                }
        }
        return save(result.imported, result.urlKeys)
    }

    suspend fun save(imported: Imported, urlKeys: List<String> = emptyList()): Outcome {
        val count = withContext(Dispatchers.Default) { SentenceSegmenter().segment(imported.document).size }
        val meta = library.add(imported.document, imported.source, imported.kind, count, urlKeys)
        return Outcome(meta, imported.document, existing = false)
    }

    /**
     * Fetches a web article again with the current settings, keeping its place in the library
     * and the listening position. Returns the new segment index to continue from.
     */
    suspend fun reload(id: String, onStage: (String) -> Unit = {}): Pair<Outcome, Int> {
        val meta = library.meta(id) ?: throw ImportException("This item is no longer in the library")
        val old = library.load(id)
        val result = web.import(meta.source, settings.extractOptions(), meta.title, onStage)
        val doc = result.imported.document
        val segmenter = SentenceSegmenter()
        val (newSegments, index) = withContext(Dispatchers.Default) {
            val segments = segmenter.segment(doc)
            val index = old?.let { o ->
                val anchor = PositionMapper.anchor(o, segmenter.segment(o), library.progressOf(id))
                anchor?.let { PositionMapper.map(it, doc, segments) }
            } ?: 0
            segments to index
        }
        val updated = library.replace(id, doc, result.imported.source, result.imported.kind, newSegments.size, result.urlKeys)
            ?: throw ImportException("This item is no longer in the library")
        library.setProgress(id, index)
        return Outcome(updated, doc, existing = false) to index
    }

    /** Imports a link saved while offline. Used by [ImportWorker]. */
    suspend fun completePending(id: String): Outcome {
        val meta = library.meta(id) ?: throw ImportException("This item is no longer in the library")
        try {
            val result = web.import(meta.source, settings.extractOptions(), meta.title)
            val doc = result.imported.document
            val count = withContext(Dispatchers.Default) { SentenceSegmenter().segment(doc).size }
            val updated = library.replace(id, doc, result.imported.source, result.imported.kind, count, result.urlKeys)
                ?: throw ImportException("This item is no longer in the library")
            return Outcome(updated, doc, existing = false)
        } catch (e: ImportException) {
            library.setStatus(id, if (e.retryable) DocStatus.PENDING else DocStatus.FAILED, e.message)
            throw e
        }
    }

    /** Tries a pending or failed link again now (or as soon as there's a network). */
    suspend fun retry(id: String) {
        library.setStatus(id, DocStatus.PENDING, null)
        scheduleRetry(id)
    }

    /** A ready saved copy of [url] in the library, if there is one. */
    suspend fun savedCopy(url: String): DocMeta? =
        library.findByUrlKey(UrlNormalizer.key(UrlNormalizer.normalize(url)))?.takeIf { it.status == DocStatus.READY }

    private suspend fun existing(vararg keys: String): Outcome? {
        val meta = library.findByUrlKey(*keys)?.takeIf { it.status == DocStatus.READY } ?: return null
        val doc = library.load(meta.id) ?: return null
        return Outcome(meta, doc, existing = true)
    }

    private fun scheduleRetry(id: String) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(ImportWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("import-$id", ExistingWorkPolicy.REPLACE, request)
    }
}

/** A link that couldn't be loaded now and was saved to the library to import later. */
class PendingImportException(message: String, val meta: DocMeta) : java.io.IOException(message)
