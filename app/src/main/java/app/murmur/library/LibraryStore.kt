package app.murmur.library

import android.content.Context
import androidx.core.content.edit
import app.murmur.text.Document
import app.murmur.text.Snippet
import app.murmur.text.TextSearch
import app.murmur.web.UrlNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

@Serializable
data class DocMeta(
    val id: String,
    val title: String,
    val author: String? = null,
    /** Where it came from: a URL, a file name, or "Shared text". */
    val source: String,
    val kind: String,
    val addedAt: Long,
    val segmentCount: Int,
    /** Duplicate-detection keys for web articles (shared address and canonical address). */
    val urlKeys: List<String> = emptyList(),
    val status: DocStatus = DocStatus.READY,
    /** Why a pending or failed import hasn't worked yet. */
    val error: String? = null,
    /** 0 when not counted yet. */
    val wordCount: Int = 0,
    /** The article looks cut off by a paywall or sign-in prompt. */
    val incomplete: Boolean = false,
    val siteName: String? = null,
    /** Has tables waiting to be summarized when the on-device model can run. */
    val pendingTables: Boolean = false,
) {
    val isWeb: Boolean get() = kind == Importer.KIND_WEB && source.startsWith("http")
}

/** A library search result. [snippet] and [count] describe matches in the text, when searched. */
data class SearchHit(val meta: DocMeta, val titleMatch: Boolean, val snippet: Snippet?, val count: Int)

@Serializable
enum class DocStatus {
    READY,
    /** A link saved while offline; [ImportWorker] imports it when the network is back. */
    PENDING,
    /** A link that can't be imported, with the reason in [DocMeta.error]. */
    FAILED,
}

/**
 * File-based library: an index plus one JSON file per document. Books can be megabytes of
 * text, which is simpler to keep in files than in database rows. Reading progress (the
 * current segment index) lives in SharedPreferences because it changes every sentence.
 */
class LibraryStore(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val progressPrefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    /** Loaded documents kept for repeat searches; the GC may drop them. */
    private val searchCache = ConcurrentHashMap<String, SoftReference<Document>>()

    private val _docs = MutableStateFlow<List<DocMeta>>(emptyList())
    val docs: StateFlow<List<DocMeta>> = _docs.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val list = runCatching {
                if (indexFile.exists()) json.decodeFromString(ListSerializer(DocMeta.serializer()), indexFile.readText())
                else emptyList()
            }.getOrDefault(emptyList())
            _docs.value = list.sortedByDescending { it.addedAt }
            _progress.value = list.associate { it.id to progressPrefs.getInt(it.id, 0) }
            loaded.complete(Unit)
            backfill()
        }
    }

    /** Fills in fields added after a document was saved: URL keys and word counts. */
    private suspend fun backfill() {
        val missing = _docs.value.filter { it.status == DocStatus.READY && (it.wordCount == 0 || (it.isWeb && it.urlKeys.isEmpty())) }
        if (missing.isEmpty()) return
        val updates = missing.associate { meta ->
            val words = if (meta.wordCount == 0) load(meta.id)?.wordCount() ?: 0 else meta.wordCount
            val keys = if (meta.isWeb && meta.urlKeys.isEmpty()) listOf(UrlNormalizer.key(meta.source)) else meta.urlKeys
            meta.id to meta.copy(wordCount = words, urlKeys = keys)
        }
        mutex.withLock {
            val list = _docs.value.map { updates[it.id] ?: it }
            writeIndex(list)
            _docs.value = list
        }
    }

    suspend fun add(
        document: Document,
        source: String,
        kind: String,
        segmentCount: Int,
        urlKeys: List<String> = emptyList(),
    ): DocMeta = withContext(Dispatchers.IO) {
        mutex.withLock {
            val meta = DocMeta(
                id = UUID.randomUUID().toString(),
                title = document.title,
                author = document.author,
                source = source,
                kind = kind,
                addedAt = System.currentTimeMillis(),
                segmentCount = segmentCount,
            ).withDocument(document, urlKeys)
            File(dir, "${meta.id}.json").writeText(json.encodeToString(Document.serializer(), document))
            val list = listOf(meta) + _docs.value
            writeIndex(list)
            _docs.value = list
            meta
        }
    }

    /** Adds a link that couldn't be imported yet, so it can be retried later. */
    suspend fun addPending(title: String, url: String, urlKeys: List<String>, error: String?): DocMeta =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val meta = DocMeta(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    source = url,
                    kind = Importer.KIND_WEB,
                    addedAt = System.currentTimeMillis(),
                    segmentCount = 0,
                    urlKeys = urlKeys,
                    status = DocStatus.PENDING,
                    error = error,
                )
                val list = listOf(meta) + _docs.value
                writeIndex(list)
                _docs.value = list
                meta
            }
        }

    /** Replaces a document's content in place, keeping its id, position in the list and added date. */
    suspend fun replace(id: String, document: Document, source: String, kind: String, segmentCount: Int, urlKeys: List<String>): DocMeta? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val old = _docs.value.firstOrNull { it.id == id } ?: return@withContext null
                File(dir, "$id.json").writeText(json.encodeToString(Document.serializer(), document))
                searchCache.remove(id)
                val meta = old.copy(
                    title = document.title, author = document.author, source = source, kind = kind,
                    segmentCount = segmentCount, status = DocStatus.READY, error = null,
                ).withDocument(document, (old.urlKeys + urlKeys).distinct())
                val list = _docs.value.map { if (it.id == id) meta else it }
                writeIndex(list)
                _docs.value = list
                meta
            }
        }

    /** Updates the status of a pending or failed link. */
    suspend fun setStatus(id: String, status: DocStatus, error: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _docs.value.map { if (it.id == id) it.copy(status = status, error = error) else it }
            writeIndex(list)
            _docs.value = list
        }
    }

    /** A web article (ready or pending) saved under any of [keys]. */
    suspend fun findByUrlKey(vararg keys: String): DocMeta? {
        loaded.await()
        return _docs.value.firstOrNull { meta -> keys.any { it in meta.urlKeys } }
    }

    private fun DocMeta.withDocument(document: Document, keys: List<String>) = copy(
        urlKeys = keys,
        wordCount = document.wordCount(),
        incomplete = document.incomplete,
        siteName = document.siteName,
        pendingTables = document.pendingTables.isNotEmpty(),
    )

    suspend fun load(id: String): Document? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(Document.serializer(), File(dir, "$id.json").readText()) }.getOrNull()
    }

    /**
     * Documents whose title, author or site matches [query], and with [fullText] also those whose
     * text does. Title matches come first; otherwise the library order is kept.
     */
    suspend fun search(query: String, fullText: Boolean): List<SearchHit> = withContext(Dispatchers.IO) {
        val folded = TextSearch.prepare(query) ?: return@withContext emptyList()
        loaded.await()
        val hits = ArrayList<SearchHit>()
        for (meta in _docs.value) {
            ensureActive()
            val titleMatch = listOfNotNull(meta.title, meta.author, meta.siteName).any { TextSearch.contains(it, folded) }
            if (!fullText || meta.status != DocStatus.READY) {
                if (titleMatch) hits += SearchHit(meta, true, null, 0)
                continue
            }
            val doc = searchCache[meta.id]?.get() ?: load(meta.id)?.also { searchCache[meta.id] = SoftReference(it) }
            val matches = doc?.let { TextSearch.find(it, query, limit = MAX_COUNTED_MATCHES) }.orEmpty()
            if (!titleMatch && matches.isEmpty()) continue
            val snippet = matches.firstOrNull()?.let { m ->
                TextSearch.snippet(doc!!.chapters[m.chapter].paragraphs[m.paragraph], m.start, m.end)
            }
            hits += SearchHit(meta, titleMatch, snippet, matches.size)
        }
        hits.sortedByDescending { it.titleMatch }
    }

    fun meta(id: String): DocMeta? = _docs.value.firstOrNull { it.id == id }

    /** Every item, once the library has loaded. */
    suspend fun all(): List<DocMeta> {
        loaded.await()
        return _docs.value
    }

    fun delete(id: String) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                File(dir, "$id.json").delete()
                searchCache.remove(id)
                val list = _docs.value.filterNot { it.id == id }
                writeIndex(list)
                _docs.value = list
                progressPrefs.edit { remove(id) }
                _progress.value = _progress.value - id
            }
        }
    }

    fun progressOf(id: String): Int = _progress.value[id] ?: progressPrefs.getInt(id, 0)

    fun setProgress(id: String, segmentIndex: Int) {
        _progress.value = _progress.value + (id to segmentIndex)
        progressPrefs.edit { putInt(id, segmentIndex) }
    }

    private fun writeIndex(list: List<DocMeta>) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(DocMeta.serializer()), list))
        tmp.renameTo(indexFile)
    }

    companion object {
        /** Match counts above this show as "100+". */
        const val MAX_COUNTED_MATCHES = 100
    }
}
