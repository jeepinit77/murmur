package app.murmur.library

import android.content.Context
import androidx.core.content.edit
import app.murmur.text.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
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
)

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
        }
    }

    suspend fun add(document: Document, source: String, kind: String, segmentCount: Int): DocMeta =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val meta = DocMeta(
                    id = UUID.randomUUID().toString(),
                    title = document.title,
                    author = document.author,
                    source = source,
                    kind = kind,
                    addedAt = System.currentTimeMillis(),
                    segmentCount = segmentCount,
                )
                File(dir, "${meta.id}.json").writeText(json.encodeToString(Document.serializer(), document))
                val list = listOf(meta) + _docs.value
                writeIndex(list)
                _docs.value = list
                meta
            }
        }

    suspend fun load(id: String): Document? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(Document.serializer(), File(dir, "$id.json").readText()) }.getOrNull()
    }

    fun meta(id: String): DocMeta? = _docs.value.firstOrNull { it.id == id }

    fun delete(id: String) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                File(dir, "$id.json").delete()
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
}
